package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.PropertyType
import edu.byu.ece.rapidSmith.device.Bel
import edu.byu.ece.rapidSmith.util.luts.InitString

/**
 * Dont pack 6 input function with a 5 input function onto the same frac lut.
 * Also make sure you dont pack a lutram cell and a non-lutram cell in the same cluster.
 */
class Mixing5And6LutsRuleFactory : PackRuleFactory {
	override fun make(cluster: Cluster<*, *>): PackRule {
		return Mixing5And6LutsRule(cluster, LUT6TYPES, LUT5TYPES)
	}

	// TODO make this a parameter
	companion object {
		private val LUT6TYPES: Set<String> = setOf(
			"LUT6", "SRLC32E", "RAMS64E", "RAMD64E"
		)
		private val LUT5TYPES: Set<String> = setOf(
			"LUT1", "LUT2", "LUT3", "LUT4", "LUT5",
			"SRL16E", "RAMS32", "RAMD32"
		)
	}
}

private class Mixing5And6LutsRule(
	private val cluster: Cluster<*, *>,
	private val LUT6TYPES: Set<String>,
	private val LUT5TYPES: Set<String>
) : PackRule {
	override fun validate(changedCells: Collection<Cell>): PackRuleResult {
		val status = PackStatus.VALID

		if (anyIncompatibleCells(cluster.cells))
			return PackRuleResult(PackStatus.INFEASIBLE, null)

		return PackRuleResult(status, emptyMap())
	}

	private fun anyIncompatibleCells(cells: Iterable<Cell>): Boolean {
		return cells.map { it.locationInCluster!! }
			.any { it.name.contains("LUT") && !isCompatible(it) }
	}

	private fun isCompatible(bel: Bel): Boolean {
		val site = bel.site
		val leName = bel.name[0]
		val lut6 = site.getBel(leName + "6LUT")
		val lut5 = site.getBel(leName + "5LUT")

		if (cluster.isBelOccupied(lut6) && cluster.isBelOccupied(lut5)) {
			val cellAtLut6 = cluster.getCellAtBel(lut6)!!

			// If the 6LUT BEL has a cell that uses all 6 inputs
			if (cellAtLut6.libCell.name in LUT6TYPES) {
				return false
			} else {
				assert(cellAtLut6.libCell.name in LUT5TYPES) { "LUT type is: ${cellAtLut6.libCell.name}" }

				// Check if these LUTs are compatible
				val cellAtLut5 = cluster.getCellAtBel(lut5)!!
				return areEquationsCompatible(cellAtLut6, cellAtLut5)
			}
		}

		return true
	}

	private fun areEquationsCompatible(cellAtLut6: Cell, cellAtLut5: Cell): Boolean {
		// There can be at most 5 unique inputs for both cells combined
		val lut5InputNets = cellAtLut5.inputNets
		val lut6InputNets = cellAtLut6.inputNets

		// Get the number of unique input nets between the two cells
		lut5InputNets.addAll(lut6InputNets)

		if (lut5InputNets.size > 5)
			return false
		return true
	}

	override fun revert() {}
}

private val INITSTRING_RE = """(\d+)'h([0-9a-fA-F]+)""".toRegex()

private val Cell.lutContents: Long
	get() {
		var cfg = properties.getValue("\$lutContents\$") as InitString?
		if (cfg == null) {
			val initString = properties.getStringValue("INIT")
			val parsed = INITSTRING_RE.matchEntire(initString)!!
			val numInputs = when(parsed.groupValues[1]) {
				"1" -> 0
				"2" -> 1
				"4" -> 2
				"8" -> 3
				"16" -> 4
				"32" -> 5
				"64" -> 6
				else -> error("illegal init string length")
			}
			cfg = InitString.parse("0x${parsed.groupValues[2]}", numInputs)!!
			properties.update("\$lutContents\$", PropertyType.USER, cfg)
		}
		return cfg.cfgValue
	}
