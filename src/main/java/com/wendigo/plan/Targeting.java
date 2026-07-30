package com.wendigo.plan;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import com.wendigo.entity.WendigoEntity;

/** Player targeting: whoever's nearest for movement/distance purposes, re-resolved on demand rather
 * than cached. See nearbyPlayers for the multiplayer stare-detection case, where any player within
 * range should be able to trigger a look-based check, not just the single nearest one. */
final class Targeting {
	private Targeting() {
	}

	/** WendigoEntity.getLockedTarget() (see its own field comment) wins over a plain nearest-player
	 * lookup whenever it's set and still valid (alive, same level) - this is the single point that
	 * makes orbit (and, by extension, everything PlanRunner does once a plan starts from orbit)
	 * commit to whichever player/group it's already locked onto instead of drifting toward whoever
	 * is physically closest at any given moment. Every existing distance/movement call site already
	 * goes through this method, so locking behavior falls out for free with no other changes. */
	static Player nearestPlayer(WendigoEntity self) {
		ServerPlayer locked = self.getLockedTarget();
		if (locked != null && locked.isAlive() && locked.level() == self.level()) {
			return locked;
		}
		return self.level().getNearestPlayer(self, SemanticBands.NEAREST_PLAYER_RADIUS);
	}

	/** Every player within the same radius nearestPlayer uses, not just the closest one - stare
	 * detection is meant to be "global FOV" in multiplayer: any player looking at the wendigo counts,
	 * regardless of who the wave is actually targeting/chasing. */
	static List<? extends Player> nearbyPlayers(WendigoEntity self) {
		double radius = SemanticBands.NEAREST_PLAYER_RADIUS;
		double radiusSq = radius * radius;
		return self.level().players().stream()
			.filter(player -> self.distanceToSqr(player) <= radiusSq)
			.toList();
	}
}
