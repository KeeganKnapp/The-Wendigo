package com.wendigo.plan;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import com.wendigo.entity.WendigoEntity;

/** Player targeting: whoever's nearest for movement/distance purposes, re-resolved on demand rather
 * than cached. See nearbyPlayers for the multiplayer stare-detection case, where any player within
 * range should be able to trigger a look-based check, not just the single nearest one. Both methods
 * filter out anyone currently at y&gt;=0 entirely - the wendigo can't follow a player back above
 * ground (user's own explicit rule, matching WendigoManager.canSpawnNear's own "never above y=0"
 * spawn gate). A single filtering point here rather than scattered per-primitive checks: every
 * existing consumer (tickOrbit, combat.chase/lunge, movement resolution, every predicate) already
 * treats "no player found" as a legitimate, already-handled case (give up, lose the target, fail to
 * resolve) - see nearestPlayer's null return - so a player who steps back above y=0 simply
 * disappears from the wendigo's targeting entirely, with no other code needed to make that stick. */
public final class Targeting {
	private Targeting() {
	}

	/** WendigoEntity.getLockedTarget() (see its own field comment) wins over a plain nearest-player
	 * lookup whenever it's set and still valid (alive, same level, below y=0) - this is the single
	 * point that makes orbit (and, by extension, everything PlanRunner does once a plan starts from
	 * orbit) commit to whichever player/group it's already locked onto instead of drifting toward
	 * whoever is physically closest at any given moment. Every existing distance/movement call site
	 * already goes through this method, so locking behavior falls out for free with no other changes.
	 * Doesn't just fall through to vanilla's own getNearestPlayer when the locked target is
	 * disqualified - that has no way to skip a too-high player and keep searching for someone lower,
	 * so a real below-y=0 player nearby would otherwise go unnoticed if the closest one happened to be
	 * above ground.
	 * <p>
	 * Public (not just this package's own callers) - the same narrow-exception precedent
	 * PlanPredicates.isLookingAtSelf/isDeadStare already established - so WendigoEntity
	 * (com.wendigo.entity) can sample distance-to-target every tick for its own rolling
	 * playerDirection() history without duplicating this locked-target-aware lookup. */
	public static Player nearestPlayer(WendigoEntity self) {
		ServerPlayer locked = self.getLockedTarget();
		if (locked != null && locked.isAlive() && locked.level() == self.level() && locked.getY() < 0) {
			return locked;
		}
		double radiusSq = SemanticBands.NEAREST_PLAYER_RADIUS * SemanticBands.NEAREST_PLAYER_RADIUS;
		Player nearest = null;
		double nearestDistSq = Double.MAX_VALUE;
		for (Player player : self.level().players()) {
			if (player.getY() >= 0) {
				continue;
			}
			double distSq = self.distanceToSqr(player);
			if (distSq <= radiusSq && distSq < nearestDistSq) {
				nearest = player;
				nearestDistSq = distSq;
			}
		}
		return nearest;
	}

	/** Every player within the same radius nearestPlayer uses, not just the closest one - stare
	 * detection is meant to be "global FOV" in multiplayer: any player looking at the wendigo counts,
	 * regardless of who the wave is actually targeting/chasing. */
	static List<? extends Player> nearbyPlayers(WendigoEntity self) {
		double radius = SemanticBands.NEAREST_PLAYER_RADIUS;
		double radiusSq = radius * radius;
		return self.level().players().stream()
			.filter(player -> player.getY() < 0 && self.distanceToSqr(player) <= radiusSq)
			.toList();
	}
}
