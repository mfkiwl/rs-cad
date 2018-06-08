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
import edu.byu.ece.rapidSmith.device.*
import edu.byu.ece.rapidSmith.util.getSitePinConnection
import java.util.*

class BasicPathFinderRouterFactory<in T: PackUnit>(
	private val packUnits: PackUnitList<T>,
	private val preferredPin: PinMapper,
	private val wireInvalidator: (PackUnit, Source, Terminal) -> Set<Wire>,
	private val maxIterations: Int = 10 //10
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
	//private val netBelPinMap: Map<CellNet, List<BelPin>>()


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
		val routeTreeMap = HashMap<CellNet, ArrayList<RouteTreeWithCost>>()
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

			if (i > maxIterations)
				println("Ran out of routing iterations for cluster " + cluster.name)

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
		// that now exist in the cluster.
		// FIXME: (This is an incorrect description)
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
				net.sourcePin.isPartitionPin -> {
					// TODO: Handle partition pins intelligently
					// This net has no source because the source is out of the boundaries of the partial device.
					initOutsidePartialDeviceSource(source)
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

		private fun initOutsidePartialDeviceSource(source: Source.Builder) {
			// The source (out of the partial device) will be able to drive the general fabric
			source.wires += template.inputs
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

			// TODO: Handle partition pins intelligently
				val sourceInCluster = (!net.sourcePin.isPartitionPin && net.sourcePin.cell.getCluster<Cluster<*, *>>() === cluster)
				// update the sinks with external routes now
				val netSinks = Sinks.Builder()
				for (sinkPin in net.sinkPins) {
					val sinkCluster = if (sinkPin.isPartitionPin) null else sinkPin.cell.getCluster<Cluster<*, *>>()
					if (!sinkPin.isPartitionPin && sinkCluster === cluster) {
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

			// What if there is contention over the BelPin wire?
			// (ex: One net has sink A5LUT.A1 and another net has sink A6LUT.A1).
			// These nets obviously can't both route to the shared A1 wire.
			// maybe use sinkPinsInCluster to check? If a sink pin WIRE has already been used by a different net,
			// then try another pin.

			val pinMap = Terminal.Builder()
			pinMap.cellPin = sinkPin
			pinMap.belPins.addAll(belPins)
			pinMap.wires += belPins.map { it.wire!! }
			sinks.sinkPinsInCluster += pinMap

			//val cellBelPinMap = HashMap<CellPin, List<BelPin>>()
			//cellBelPinMap.put(sinkPin, belPins)
			//belPinMap[net] = cellBelPinMap
		}

		/**
		 * Update the info of a sink placed in another cluster.
		 */
		private fun initOutsideClusterSink(sinks: Sinks.Builder, sinkPin: CellPin) {
			// The source cell has already been placed so we know where it is and
			// where it enters this cluster.

			// A partition pin has no corresponding cell or bel
			if (sinkPin.isPartitionPin) {
				// println("part pin...init outside cluster sink?")
				sinks.mustRouteExternal = true
				return
			}

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
				// TODO: Verbosity print.
				// println("Route net " + net.name)

				// If the net has a source, add it to the list of contention nets (WHY?)
				if (net.isSourced) // && !net.sourcePin.isPartitionPin
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

		private fun noContentionForRoute(sourceTrees: List<RouteTreeWithCost>): Boolean {
			for (sourceTree in sourceTrees) {
				val stack = Stack<RouteTreeWithCost>()
				stack.push(sourceTree)

				while (!stack.isEmpty()) {
					val rt = stack.pop()
					val wire = rt.wire
					val wireInfo = wireUsage[wire]!!
					val occupancy = wireInfo.occupancy
					if (occupancy > 1) {
						//println("Wire " + wire.name + " has occupancy " + occupancy)
						return false
					}

					rt.children.forEach { stack.push(it as RouteTreeWithCost) }
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

			val sourceTrees = buildSources(source, pinMap, net.isStaticNet)

			var foundExternalPath = false

			val sinkTrees = HashSet<RouteTreeWithCost>()
			for (sink in sinks.sinkPinsInCluster) {
				//println("  to sink " + sink.cellPin + " - " + sink.belPins) //TODO: Verbosity print

				val (status, treeSink, terminal) = routeToSink(
					sourceTrees, sinkTrees, source, sink, net)

				if (status == RouteStatus.IMPOSSIBLE)
					return RouteStatus.IMPOSSIBLE

				pinMap.compute(sink.cellPin!!) { _, v ->
					if (v == null)
						listOf(terminal as BelPin)
					else
						v + (terminal as BelPin) }
				updateSourceTrees(treeSink)

				// If VCC/GND and the source is O5 or O6 of a LUT, then we are planning on using
				// this BEL as a static source. Add it to a list for the router to be aware of.
				//if (net.isStaticNet && (treeSink?.connectedBelPin?.name.equals("O5") || treeSink?.connectedBelPin?.name.equals("O6"))) {
					
				//}
			}
			// take care of sink pins out of the cluster
			if (sinks.mustRouteExternal) {

				val (status, treeSink, terminal) =
					routeToSink(sourceTrees, sinkTrees, source, clusterOutputs, net)
				if (status == RouteStatus.IMPOSSIBLE)
					return RouteStatus.IMPOSSIBLE

				assert(terminal is Wire)
				foundExternalPath = true
				updateSourceTrees(treeSink)
			}

			for (sink in sinks.requiredCarryChains) {
				val (status, treeSink, terminal) =
					routeToSink(sourceTrees, sinkTrees, source, sink, net)

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
					routeToSink(sourceTrees, sinkTrees, source, sink, net)

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

		private fun buildSources(source: Source, pinMap: HashMap<CellPin, List<BelPin>>, isPowerNet: Boolean): ArrayList<RouteTreeWithCost> {
			val sourceTrees = ArrayList<RouteTreeWithCost>()
			for (sourceWire in source.wires) {
				val rt = RouteTreeWithCost(sourceWire)
				wireUsage.computeIfAbsent(sourceWire) { OccupancyHistoryPair() }
				rt.cost = calculateSourceCost(sourceWire)

				if (isPowerNet) {
					// Add an extra cost if the source tree's pin is a LUT output pin and the net is VCC/GND
					// Vivado prefers to not use LUT static sources and will first use the AX, BX, ... , DX pins to route
					// power nets and will only use LUT static sources as a last resort.
					// TODO: Don't use a string comparison
					if (sourceWire.source?.bel != null) {
						if (sourceWire.source.bel.name.contains("LUT")) {
							rt.cost += 50
						}
					}

					// Intrasite tie-offs should be preferred - even if GND is already being routed from sitepin AX to Carry4.DI0.
					// Not using the tie-offs leads to errors with manual routing.
					// Increase cost of all non-tie off sources by 1 in order to prefer tie-offs.
					// TODO: Don't use a string comparison (or at least use equals)
					//if (!sourceWire.name.contains("CYINITGND.0") && !sourceWire.name.contains("SRUSEDGND.0") && !sourceWire.name.contains("CYINITVCC.1") && !sourceWire.name.contains("CEUSEDVCC.1")) {

						//for (tree in rt.typedIterator<RouteTreeWithCost>()) {
						//	rt.cost += 50
						//	tree.cost += 50
						//}
					//}
				}

				sourceTrees.add(rt)
			}
			if (source.belPin != null)
				pinMap[source.cellPin!!] = listOf(source.belPin!!)
			return sourceTrees
		}

		private fun updateSourceTrees(sinkTree: RouteTreeWithCost?) {
			var rt = sinkTree
			while (rt != null) {
				rt.cost = 0
				rt = rt.getParent()
			}
		}

		private fun pruneSourceTrees(
			sourceTrees: ArrayList<RouteTreeWithCost>,
			sinkTrees: Set<RouteTreeWithCost>, removeSources: Boolean
		) {
			sourceTrees.removeIf { rt -> !rt.prune(sinkTrees) && removeSources }
		}

		private fun routeToSink(
			sourceTrees: ArrayList<RouteTreeWithCost>,
			sinkTrees: MutableSet<RouteTreeWithCost>, // sinks routed to so far for this net/cluster
			source: Source,
			sink: Terminal,
			net: CellNet
		): RouteToSinkReturn {
			val pq = PriorityQueue<RouteTreeWithCost>()
			val wireCosts = HashMap<Wire, Int>()

			for (sourceTree in sourceTrees) {

				// If routing VCC/GND to CYINIT of a carry cell, use the tie-off.
				//TODO: Add other tie-off cases, improve code

				if (net.isVCCNet) {
					if (sink.belPins.size == 1 && sink.belPins[0].bel.name.equals("CARRY4") && sink.belPins[0].template.name.equals("CYINIT")) {
						if (sourceTree.wire.name.contains("CYINITVCC.1")) {
							for (rt in sourceTree.typedIterator<RouteTreeWithCost>()) {
								pq.add(rt)
								wireCosts[rt.wire] = rt.cost
							}
						}
					}
					else {
						for (rt in sourceTree.typedIterator<RouteTreeWithCost>()) {
							pq.add(rt)
							wireCosts[rt.wire] = rt.cost
						}
					}
				}
				else if (net.isGNDNet) {
					if (sink.belPins.size == 1 && sink.belPins[0].bel.name.equals("CARRY4") && sink.belPins[0].template.name.equals("CYINIT")) {
						if (sourceTree.wire.name.contains("CYINITGND.0")) {
							for (rt in sourceTree.typedIterator<RouteTreeWithCost>()) {
								pq.add(rt)
								wireCosts[rt.wire] = rt.cost
							}
						}
					}
					else {
						for (rt in sourceTree.typedIterator<RouteTreeWithCost>()) {
							pq.add(rt)
							wireCosts[rt.wire] = rt.cost
						}
					}

				}
				else {
					for (rt in sourceTree.typedIterator<RouteTreeWithCost>()) {
						pq.add(rt)
						wireCosts[rt.wire] = rt.cost
					}
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

					val terminal = wire.terminal
					if (terminal != null) {
						ret.terminal = terminal
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
						// TODO Support BEL route throughs?
						if (wire is TileWire || !c.isRouteThrough) {
							val sinkTree = lowestCost.connect<RouteTreeWithCost>(c)
							sinkTree.cost = wireCost
							pq.add(sinkTree)
						}
					}
				}

				wire.getSitePinConnection(true)?.let {
					val sinkWire = it.sinkWire

					val wireCost = lowestCost.cost + calculateWireCost(sinkWire)
					if (wireCost < wireCosts.getOrDefault(sinkWire, Integer.MAX_VALUE)) {
						val sinkTree = lowestCost.connect<RouteTreeWithCost>(it)
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

		private fun commitRoute(net: CellNet, sourceTrees: ArrayList<RouteTreeWithCost>) {
			for (sourceTree in sourceTrees) {
				val stack = Stack<RouteTreeWithCost>()
				stack.push(sourceTree)

				while (!stack.isEmpty()) {
					val rt = stack.pop()
					val pair = wireUsage[rt.wire]!!
					pair.occupancy += 1
					// only increment the historical for shared wires
					if (pair.occupancy > 1)
						pair.history += 1
					rt.children.forEach { stack.push(it as RouteTreeWithCost) }
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
	var treeSink: RouteTreeWithCost? = null,
	var terminal: Any? = null
)

private class StatusNetsPair(
	var status: RouteStatus? = null,
	var contentionNets: MutableList<CellNet> = ArrayList()
)

private class RouteTreeWithCost(wire: Wire) : RouteTree(wire), Comparable<RouteTreeWithCost> {
	var cost = 0

	override fun newInstance(wire: Wire): RouteTree = RouteTreeWithCost(wire)

	override fun compareTo(other: RouteTreeWithCost): Int {
		return Integer.compare(cost, other.cost)
	}
}
