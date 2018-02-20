package edu.byu.ece.rapidSmith.cad.pack.rsvpack.configurations

import edu.byu.ece.rapidSmith.cad.cluster.*
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouter
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.ClusterRouterResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.router.PinMapper
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules.Routability
import edu.byu.ece.rapidSmith.design.NetType
import edu.byu.ece.rapidSmith.design.subsite.CellNet
import edu.byu.ece.rapidSmith.design.subsite.CellPin
import edu.byu.ece.rapidSmith.design.subsite.RouteTree
import edu.byu.ece.rapidSmith.device.BelPin
import edu.byu.ece.rapidSmith.device.Site
import edu.byu.ece.rapidSmith.device.Wire
import edu.byu.ece.rapidSmith.util.getSitePinConnection
import java.util.*

class BasicPathFinderRouterFactory<in T: PackUnit>(
	private val packUnits: PackUnitList<T>,
	private val preferredPin: PinMapper,
	private val wireInvalidator: (PackUnit, Source, Terminal) -> Set<Wire>,
	private val maxIterations: Int = 10
) : ClusterRouterFactory<T> {
	private val routers = HashMap<T, ClusterRouter<T>>()

	override fun get(packUnit: T): ClusterRouter<T> {
		return routers.computeIfAbsent(packUnit) {
			val wireCosts = buildWireCosts(packUnit.template)
			BasicPathFinderRouter(
				packUnit, wireCosts, packUnits,
				preferredPin, wireInvalidator, maxIterations)
		}
	}
}

private class BasicPathFinderRouter<T: PackUnit>(
	private val packUnit: PackUnit,
	private val wireCosts: Map<Wire, Int>,
	private val packUnits: PackUnitList<T>,
	private val preferredPin: PinMapper,
	private val wireInvalidator: (PackUnit, Source, Terminal) -> Set<Wire>,
	private val maxIterations: Int
) : ClusterRouter<T> {
	private val clusterOutputs: Terminal

	init {
		clusterOutputs = Terminal.Builder()
		clusterOutputs.wires += template.outputs
	}

	private val template: PackUnitTemplate
		get() = packUnit.template

	override fun route(cluster: Cluster<T, *>): ClusterRouterResult {
		val router = Impl(cluster)
		val result = router.routeCluster(cluster)
		return if (result == Routability.VALID)
			ClusterRouterResult(true, router.routeTreeMap, router.belPinMap)
		else
			ClusterRouterResult(false)
	}

	private inner class Impl(val cluster: Cluster<T, *>) {
		val routeTreeMap = HashMap<CellNet, ArrayList<RouteTree>>()
		val belPinMap = HashMap<CellNet, HashMap<CellPin, List<BelPin>>>()

		private val wireUsage = HashMap<Wire, OccupancyHistoryPair>()
		private val routePins = HashMap<CellNet, RoutePins>()
		private val invalidatedWires = HashSet<Wire>()

		fun routeCluster(cluster: Cluster<T, *>): Routability {
			initNets(cluster)

			var routeStatus: StatusNetsPair
			var i = 0
			do {
				routeStatus = routeNets()
				if (routeStatus.status != RouteStatus.CONTENTION)
					break
				i++
			} while (i <= maxIterations)

			return when (routeStatus.status!!) {
				RouteStatus.SUCCESS -> Routability.VALID
				RouteStatus.CONTENTION -> Routability.INFEASIBLE
				RouteStatus.IMPOSSIBLE -> Routability.INFEASIBLE
			}
		}

		// initializes the net info for each new net added to the cluster by this new
		// set of cells.  at this time, all pins in the newly created nets are
		// considered outside of the cluster.  the updateChangedNets method, which  is
		// called immediately after this method, will update the net info with any pins
		// that now exist in the cluster
		private fun initNets(cluster: Cluster<*, *>) {
			cluster.nets.map { net ->
				val source = initNetSource(net)
				val sinks = initNetSinks(net)
				net to RoutePins(source, sinks)
			}.toMap(routePins)
		}

		// initializes the source pin info for a new net. the source at this stage is
		// treated as being outside the cluster
		private fun initNetSource(net: CellNet): Source {
			val source = Source.Builder()
			when {
				net.type == NetType.VCC -> {
					source.wires += template.inputs
					source.wires += template.vccSources.map { it.wire }
				}
				net.type == NetType.GND -> {
					source.wires += template.inputs
					source.wires += template.gndSources.map { it.wire }
				}
				else -> {
					val sourcePin = net.sourcePin
					val sourceCell = sourcePin.cell

					source.cellPin = sourcePin
					val sourceCluster = sourceCell.getCluster<Cluster<*, *>>()

					// source is placed outside the cluster
					if (sourceCluster !== cluster) {
						initOutsideClusterSource(source, sourcePin)
					} else {
						initInsideClusterSource(source, sourcePin)
					}
				}
			}

			return source
		}

		private fun initOutsideClusterSource(source: Source.Builder, sourcePin: CellPin) {
			val sourceCell = sourcePin.cell
			val bel = sourceCell.locationInCluster!!

			val belPins = sourcePin.getPossibleBelPins(bel)
			val belPin = belPins.single()
			val endSiteIndex = bel.site.index
			if (belPin.drivesGeneralFabric)
				source.wires += template.inputs

			template.directSourcesOfCluster
				.filter { endSiteIndex == it.endSiteIndex && it.endPin == belPin.template }
				.mapTo(source.wires) { it.clusterExit }
		}

		private fun initInsideClusterSource(source: Source.Builder, sourcePin: CellPin) {
			val sourceCell = sourcePin.cell
			val bel = sourceCell.locationInCluster!!

			val belPins = sourcePin.getPossibleBelPins(bel)
			val belPin = belPins.single()
			source.cellPin = sourcePin
			source.belPin = belPin
			source.wires += belPin.wire
		}

		// Just create an object.  The sinks will be built when a source is added
		// into the cluster.
		private fun initNetSinks(net: CellNet): Sinks {
			val sourceInCluster = net.sourcePin.cell.getCluster<Cluster<*, *>>() === cluster
			// update the sinks with external routes now
			val netSinks = Sinks.Builder()
			for (sinkPin in net.sinkPins) {
				val sinkCluster = sinkPin.cell.getCluster<Cluster<*, *>>()
				if (sinkCluster === cluster) {
					initInsideClusterSink(netSinks, sinkPin)
				} else if (sinkCluster !== cluster && sourceInCluster) {
					initOutsideClusterSink(netSinks, sinkPin)
				}
			}
			return netSinks
		}

		/** Relocates [sinkPin] from outside the cluster to inside the cluster. */
		private fun initInsideClusterSink(sinks: Sinks.Builder, sinkPin: CellPin) {
			val sinkCell = sinkPin.cell
			val sinkBel = sinkCell.locationInCluster!!
			val belPins = preferredPin(sinkPin, sinkBel)

			val pinMap = Terminal.Builder()
			pinMap.cellPin = sinkPin
			pinMap.belPins.addAll(belPins)
			pinMap.wires += belPins.map { it.wire!! }
			sinks.sinkPinsInCluster += pinMap
		}

		/**
		 * Update the info of a sink placed in another cluster.
		 */
		private fun initOutsideClusterSink(sinks: Sinks.Builder, sinkPin: CellPin) {
			// The source cell has already been placed so we know where it is and
			// where it enters this cluster.
			val sinkBel = sinkPin.cell.locationInCluster!!
			val belPins = preferredPin(sinkPin, sinkBel)
			val endSiteIndex = sinkBel.site.index

			for (belPin in belPins) {
				// find any direct connections to this path
				var directSink = false
				val carrySinks = HashSet<Wire>()
				for (dc in template.directSinksOfCluster) {
					if (endSiteIndex == dc.endSiteIndex && dc.endPin == belPin.template) {
						carrySinks.add(dc.clusterExit)
						directSink = true
					}
				}

				// place in the correct location
				val drivenGenerally = belPin.drivenByGeneralFabric
				if (drivenGenerally && directSink) {
					val terminal = Terminal.Builder()
					terminal.cellPin = sinkPin
					terminal.wires += carrySinks
					terminal.wires += clusterOutputs.wires
					sinks.optionalCarryChains += terminal
				} else if (drivenGenerally) {
					sinks.mustRouteExternal = true
				} else {
					val terminal = Terminal.Builder()
					terminal.cellPin = sinkPin
					terminal.wires += carrySinks
					sinks.requiredCarryChains += terminal
				}
			}
		}

		private fun routeNets(): StatusNetsPair {
			val status = StatusNetsPair()
			status.status = RouteStatus.SUCCESS
			for ((net, pins) in routePins) {
				if (net in routeTreeMap) {
					val rts = routeTreeMap[net]!!
					if (noContentionForRoute(rts))
						continue
					unrouteNet(net)
				}

				if (net.isSourced)
					status.contentionNets.add(net)
				val netRouteStatus = routeNet(net, pins)
				// exit early if the route isn't feasible
				when (netRouteStatus) {
					RouteStatus.IMPOSSIBLE -> {
						status.status = netRouteStatus
						return status
					}
					RouteStatus.CONTENTION ->
						status.status = RouteStatus.CONTENTION
					RouteStatus.SUCCESS -> { /* do nothing */
					}
				}
			}

			return status
		}

		private fun noContentionForRoute(sourceTrees: List<RouteTree>): Boolean {
			for (sourceTree in sourceTrees) {
				val stack = Stack<RouteTree>()
				stack.push(sourceTree)

				while (!stack.isEmpty()) {
					val rt = stack.pop()
					val wire = rt.wire
					val wireInfo = wireUsage[wire]!!
					val occupancy = wireInfo.occupancy
					if (occupancy > 1)
						return false
					rt.sinkTrees.forEach { stack.push(it) }
				}
			}
			return true
		}

		private fun unrouteNet(net: CellNet) {
			val sourceTrees = routeTreeMap.remove(net)!!
			for (sourceTree in sourceTrees) {
				for (rt in sourceTree) {
					val pair = wireUsage[rt.wire]!!
					pair.occupancy -= 1
					assert(pair.occupancy >= 0)
				}
			}
			belPinMap.remove(net)
		}

		private fun routeNet(net: CellNet, routePins: RoutePins): RouteStatus {
			val (source, sinks) = routePins
			val pinMap = HashMap<CellPin, List<BelPin>>()
			belPinMap[net] = pinMap

			val sourceTrees = buildSources(source, pinMap)

			var foundExternalPath = false

			val sinkTrees = HashSet<RouteTree>()
			for (sink in sinks.sinkPinsInCluster) {
				val (status, treeSink, terminal) = routeToSink(
					sourceTrees, sinkTrees, source, sink)

				if (status == RouteStatus.IMPOSSIBLE)
					return RouteStatus.IMPOSSIBLE

				pinMap.compute(sink.cellPin!!) { _, v ->
					if (v == null)
						listOf(terminal as BelPin)
					else
						v + (terminal as BelPin) }
				updateSourceTrees(treeSink)
			}

			if (sinks.mustRouteExternal) {
				val (status, treeSink, terminal) =
					routeToSink(sourceTrees, sinkTrees, source, clusterOutputs)
				if (status == RouteStatus.IMPOSSIBLE)
					return RouteStatus.IMPOSSIBLE

				assert(terminal is Wire)
				foundExternalPath = true
				updateSourceTrees(treeSink)
			}

			for (sink in sinks.requiredCarryChains) {
				val (status, treeSink, terminal) =
					routeToSink(sourceTrees, sinkTrees, source, sink)

				if (status == RouteStatus.IMPOSSIBLE)
					return RouteStatus.IMPOSSIBLE

				assert(terminal is Wire)
				updateSourceTrees(treeSink)
			}

			for (sink in sinks.optionalCarryChains) {
				// Check if we've already accomplished this route
				if (foundExternalPath)
					break
				val (status, treeSink, terminal) =
					routeToSink(sourceTrees, sinkTrees, source, sink)

				if (status == RouteStatus.IMPOSSIBLE)
					break

				assert(terminal is Wire)
				if (terminal in clusterOutputs.wires)
					foundExternalPath = true

				updateSourceTrees(treeSink)
			}

			pruneSourceTrees(sourceTrees, sinkTrees, true)

			commitRoute(net, sourceTrees)
			return if (!noContentionForRoute(sourceTrees))
				RouteStatus.CONTENTION
			else
				RouteStatus.SUCCESS
		}

		private fun buildSources(source: Source, pinMap: HashMap<CellPin, List<BelPin>>): ArrayList<RouteTree> {
			val sourceTrees = ArrayList<RouteTree>()
			for (sourceWire in source.wires) {
				val rt = RouteTree(sourceWire)
				wireUsage.computeIfAbsent(sourceWire) { OccupancyHistoryPair() }
				rt.cost = calculateSourceCost(sourceWire)
				sourceTrees.add(rt)
			}
			if (source.belPin != null)
				pinMap[source.cellPin!!] = listOf(source.belPin!!)
			return sourceTrees
		}

		private fun updateSourceTrees(sinkTree: RouteTree?) {
			var rt = sinkTree
			while (rt != null) {
				rt.cost = 0
				rt = rt.sourceTree
			}
		}

		private fun pruneSourceTrees(
			sourceTrees: ArrayList<RouteTree>,
			sinkTrees: Set<RouteTree>, removeSources: Boolean
		) {
			sourceTrees.removeIf { rt -> !rt.prune(sinkTrees) && removeSources }
		}

		private fun routeToSink(
			sourceTrees: ArrayList<RouteTree>,
			sinkTrees: MutableSet<RouteTree>,
			source: Source,
			sink: Terminal
		): RouteToSinkReturn {
			val pq = PriorityQueue<RouteTree>()
			val wireCosts = HashMap<Wire, Int>()

			for (sourceTree in sourceTrees) {
				for (rt in sourceTree) {
					pq.add(rt)
					wireCosts[rt.wire] = rt.cost
				}
			}

			invalidatedWires += wireInvalidator(packUnit, source, sink)

			// Determine the terminal wires for this route
			val terminals = sink.wires

			val ret = RouteToSinkReturn()
			ret.status = RouteStatus.IMPOSSIBLE
			val processedWires = HashSet<Wire>()

			// Allows for rechecking for the output
			while (!pq.isEmpty()) {
				val lowestCost = pq.poll()
				val wire = lowestCost.wire
				if (!processedWires.add(wire))
					continue

				if (wire in terminals) {
					ret.status = RouteStatus.SUCCESS
					ret.treeSink = lowestCost
					sinkTrees.add(lowestCost)

					val terminal = wire.terminals.firstOrNull()
					if (terminal != null) {
						val belPin = terminal.belPin!!
						ret.terminal = belPin
					} else {
						ret.terminal = wire
					}
					break
				}

				wire.wireConnections.forEach { c ->
					// I don't think I care about route throughs.  Check the old cluster routing
					// if I do since I had some code to handle them there.
					val sinkWire = c.sinkWire

					val wireCost = lowestCost.cost + calculateWireCost(sinkWire)
					if (wireCost < wireCosts.getOrDefault(sinkWire, Integer.MAX_VALUE)) {
						// TODO Support route throughs?
						if (!c.isRouteThrough) {
							val sinkTree = lowestCost.addConnection(c)
							sinkTree.cost = wireCost
							pq.add(sinkTree)
						}
					}
				}

				wire.getSitePinConnection(true)?.let {
					val sinkWire = it.sinkWire

					val wireCost = lowestCost.cost + calculateWireCost(sinkWire)
					if (wireCost < wireCosts.getOrDefault(sinkWire, Integer.MAX_VALUE)) {
						val sinkTree = lowestCost.addConnection(it)
						sinkTree.cost = wireCost
						pq.add(sinkTree)
					}
				}
			}

			pruneSourceTrees(sourceTrees, sinkTrees, false)
			invalidatedWires.clear()

			return ret
		}

		private fun calculateSourceCost(wire: Wire): Int {
			return calculateWireCost(wire)
		}

		private fun calculateWireCost(wire: Wire): Int {
			if (wire in invalidatedWires)
				return 10000

			var cost = wireCosts.getOrDefault(wire, 1)
			val pair = wireUsage.computeIfAbsent(wire) { OccupancyHistoryPair() }
			cost += 4 * pair.occupancy
			cost += 2 * pair.history

			return cost
		}

		private fun commitRoute(net: CellNet, sourceTrees: ArrayList<RouteTree>) {
			for (sourceTree in sourceTrees) {
				val stack = Stack<RouteTree>()
				stack.push(sourceTree)

				while (!stack.isEmpty()) {
					val rt = stack.pop()
					val pair = wireUsage[rt.wire]!!
					pair.occupancy += 1
					// only increment the historical for shared wires
					if (pair.occupancy > 1)
						pair.history += 1
					rt.sinkTrees.forEach { stack.push(it) }
				}
			}
			routeTreeMap.put(net, sourceTrees)
		}

		// Convenience method for determining if pins drive/are driven by general fabric
		private val BelPin.drivesGeneralFabric: Boolean
			get() = this.name in packUnits.pinsDrivingGeneralFabric[this.bel.id]!!

		private val BelPin.drivenByGeneralFabric: Boolean
			get() = this.name in packUnits.pinsDrivenByGeneralFabric[this.bel.id]!!

	}
}

private fun buildWireCosts(template: PackUnitTemplate): Map<Wire, Int> {
	val wireCosts = HashMap<Wire, Int>()
	val sites = HashSet<Site>()
	for (bel in template.bels) {
		var lutOutput: Wire? = null
		if (bel.name.matches("[A-D]6LUT".toRegex())) {
			lutOutput = bel.getWireOfPin("O6")
		} else if (bel.name.matches("[A-D][5]LUT".toRegex())) {
			lutOutput = bel.getWireOfPin("O5")
		}
		if (lutOutput != null)
			wireCosts.put(lutOutput, 2)
		sites.add(bel.site)
	}

	sites.asSequence()
		.flatMap { it.wires.asSequence() }
		.filter { it.name.matches(".*[A-D]OUTMUX\\.OUT".toRegex()) }
		.associateTo(wireCosts) { it to 3 }
	return wireCosts
}

/**
 * The possible sources for a net in the cluster.
 */
abstract class Source {
	// Terminals from leaving the cluster.  Includes carry chain terminals.
	abstract val wires: List<Wire>
	abstract val cellPin: CellPin?
	abstract var belPin: BelPin?

	// separates the construction from the usage
	class Builder: Source() {
		override val wires = ArrayList<Wire>()
		override var cellPin: CellPin? = null
		override var belPin: BelPin? = null
	}
}

/**
 * The different sinks in a net that must be reached.
 */
private abstract class Sinks {
	// Must this net leave to the general routing fabric (ie are there one or more
	// sinks on this net that are placed outside by accessible only via the general
	// routing fabric.
	abstract val mustRouteExternal: Boolean
	// Sinks that are packed into this cluster.
	abstract val sinkPinsInCluster: Set<Terminal>
	// Carry chains outside this cluster
	abstract val requiredCarryChains: Set<Terminal>
	// Carry Chains that may also be reached via general routing.  Is this guy used?
	abstract val optionalCarryChains: Set<Terminal>

	/** Builder class to separate usage from creation */
	class Builder : Sinks() {
		override var mustRouteExternal: Boolean = false
		override val sinkPinsInCluster = HashSet<Terminal>()
		override val requiredCarryChains = HashSet<Terminal>()
		override val optionalCarryChains = HashSet<Terminal>()
	}
}

private data class RoutePins(val source: Source, val sinks: Sinks)

abstract class Terminal {
	abstract val cellPin: CellPin?
	abstract val belPins: List<BelPin>
	abstract val wires: Set<Wire>

	fun isPinMapping() = belPins.isNotEmpty()

	class Builder: Terminal() {
		override var cellPin: CellPin? = null
		override var belPins = ArrayList<BelPin>()
		override val wires = HashSet<Wire>()
	}
}

private data class OccupancyHistoryPair(
	var occupancy: Int = 0,
	var history: Int = 0
)

private enum class RouteStatus {
	SUCCESS, IMPOSSIBLE, CONTENTION
}

private data class RouteToSinkReturn(
	var status: RouteStatus? = null,
	var treeSink: RouteTree? = null,
	var terminal: Any? = null
)

private class StatusNetsPair(
	var status: RouteStatus? = null,
	var contentionNets: MutableList<CellNet> = ArrayList()
)

