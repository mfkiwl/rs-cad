package edu.byu.ece.rapidSmith.cad.pack.rsvpack.rules

import edu.byu.ece.rapidSmith.cad.cluster.Cluster
import edu.byu.ece.rapidSmith.cad.cluster.locationInCluster
import edu.byu.ece.rapidSmith.cad.families.Ram
import edu.byu.ece.rapidSmith.cad.families.RamMaker
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRule
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleFactory
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackRuleResult
import edu.byu.ece.rapidSmith.cad.pack.rsvpack.PackStatus
import edu.byu.ece.rapidSmith.design.subsite.Cell
import edu.byu.ece.rapidSmith.design.subsite.CellDesign

/**
  different RAMs have to go in different locations.
 */
class RamPositionsPackRuleFactory(private val ramMaker: RamMaker) : PackRuleFactory {
	private var rams: Map<Cell, Ram>? = null

	override fun init(design: CellDesign) {
		rams = ramMaker.make(design)
	}

	override fun make(cluster: Cluster<*, *>): PackRule {
		return Rule(checkNotNull(rams))
	}

	inner class Rule(private val rams: Map<Cell, Ram>) : PackRule {
		override fun validate(changedCells: Collection<Cell>): PackRuleResult {
			// check LUT is placed at a valid location
			val changedRamCells = changedCells.filter { c -> c in rams }

			if (changedRamCells.isEmpty()) {
				return PackRuleResult(PackStatus.VALID, null)
			}

			val status = if (validRamPositions(changedRamCells))
				PackStatus.VALID
			else
				PackStatus.INFEASIBLE

			return PackRuleResult(status, null)
		}

		private fun validRamPositions(changedRamCells: List<Cell>): Boolean {
			for (cell in changedRamCells) {
				val location = cell.locationInCluster!!
				val ramPosition = cell.ramPosition
				val locationName = location.name
				assert(locationName.matches("[A-D][5-6]LUT".toRegex()))
				if (ramPosition.indexOf(locationName[0]) == -1)
					return false
			}
			return true
		}

		override fun revert() {
			// do nothing
		}

		private val Cell.ramPosition : String
			get() {
				return rams[this]!!.positions[this]!!
			}
	}
}
