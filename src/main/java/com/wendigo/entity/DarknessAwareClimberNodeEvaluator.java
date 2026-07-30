package com.wendigo.entity;

import com.nyfaria.awcapi.entity.movement.AdvancedWalkNodeProcessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;

/**
 * AWCAPI's climbing-aware neighbor generation (floor, wall, and ceiling nodes already unified into
 * one graph by the superclass), with the same light/cobweb cost malus the wendigo's ordinary
 * pathfinding always applied layered on top (see DarknessMalus) - so a climbing route still
 * prefers darkness the same way a floor-only route always did. Supersedes the old floor-only
 * DarknessNodeEvaluator/DarknessAwareNavigation pair entirely - this is the wendigo's only
 * navigation now (see WendigoEntity.createNavigation), not a second one it switches into.
 */
public class DarknessAwareClimberNodeEvaluator extends AdvancedWalkNodeProcessor {
	private final DarknessMalus malus = new DarknessMalus();

	/** See DarknessMalus.lightTolerant. */
	public void setLightTolerant(boolean lightTolerant) {
		this.malus.setLightTolerant(lightTolerant);
	}

	/** See DarknessMalus.severityPercent/HARD_BLOCK_MAX_PERCENT. */
	public void setSeverityPercent(int severityPercent) {
		this.malus.setSeverityPercent(severityPercent);
	}

	@Override
	public void prepare(PathNavigationRegion region, Mob mob) {
		super.prepare(region, mob);
		this.malus.prepare(mob);
	}

	@Override
	public void done() {
		this.malus.done();
		super.done();
	}

	// getPathTypeOfMob deliberately NOT overridden here, unlike the old DarknessNodeEvaluator's own
	// rail-downgrade fix. AdvancedWalkNodeProcessor supplies its own full implementation (verified
	// via javap: routed entirely through getDirectionalPathNodeType, not vanilla
	// WalkNodeEvaluator's rail-specific codepath the old fix existed to undo) - whether the same
	// rails-surrounding-the-player bug reproduces here is unconfirmed; verify live before porting
	// that fix blind.

	@Override
	public int getNeighbors(Node[] neighbors, Node node) {
		int count = super.getNeighbors(neighbors, node);
		return this.malus.apply(this.mob, neighbors, count);
	}
}
