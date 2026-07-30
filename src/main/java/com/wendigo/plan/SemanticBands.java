package com.wendigo.plan;

import com.wendigo.spatial.CaveScaleScanner.CaveScale;

/**
 * Maps the schema's semantic enums (speed, distance, search_radius, duration, max_iterations)
 * to real engine values, plus a few fixed tuning constants. Nothing here is scientifically
 * tuned - first-pass numbers to make the executor behave reasonably, adjust by feel.
 */
final class SemanticBands {
	private SemanticBands() {
	}

	static double speedMultiplier(String speed) {
		return switch (speed) {
			case "slow" -> 1.25;
			case "fast" -> 1.75;
			default -> 1.5; // "normal"
		};
	}

	// Representative block distance for a semantic band - movement.reposition's own tactical
	// close/medium/far, distinct from the ProximityBands ladder predicate.player_distance and the
	// wave prompt use (see ProximityBands - that one's public/shared, this one's reposition-only).
	static double distanceBlocks(String distance) {
		return switch (distance) {
			case "close" -> 10.0;
			case "far" -> 26.0;
			default -> 18.0; // "medium"
		};
	}

	static double searchRadiusBlocks(String radius) {
		return switch (radius) {
			case "far" -> 30.0;
			case "near" -> 10.0;
			default -> 20.0;
		};
	}

	/** [minTicks, maxTicks] - the engine rolls a random point in range so waits don't feel identical. */
	static int[] waitTicks(String duration) {
		return switch (duration) {
			case "immediate" -> new int[] {0, 10};
			case "short" -> new int[] {20, 40};
			case "long" -> new int[] {140, 220};
			default -> new int[] {60, 100}; // "medium"
		};
	}

	static int maxIterations(String band) {
		return switch (band) {
			case "few" -> 3;
			case "many" -> 20;
			default -> 8; // "some"
		};
	}

	// Raw light level at/below which a position counts as "dark" - matches vanilla's hostile
	// mob spawn threshold, not independently tuned.
	static final int DARKNESS_LIGHT_THRESHOLD = 2;

	/**
	 * Angle within which a player's look vector counts as "facing" the wendigo, by named band -
	 * corner_of_eye is deliberately capped at 60: past that it stops reading as "in the corner of
	 * your eye" even with a normal FOV and starts counting as behind the player. dead_stare was
	 * widened from a stricter 10 - with wall/ceiling climbing (AWCAPI), the visible model can read as
	 * clearly stared at while the underlying hitbox sits tucked slightly behind a block corner from
	 * the camera's exact angle, which under the old tight tolerance meant a held stare-hold loop
	 * would never see a real dead-on look register at all (control.while never ends, the wendigo
	 * never continues) until the player physically closed enough distance to also straighten out that
	 * small hitbox/visual offset - not an intentional gameplay gate, just geometry slop this widening
	 * absorbs.
	 */
	static double lookAngleDegrees(String band) {
		return switch (band) {
			case "corner_of_eye" -> 60.0;
			case "dead_stare" -> 18.0;
			default -> 30.0; // "in_view"
		};
	}

	// Cap on the "distance between wendigo and player when the enclosing control.while started"
	// baseline used by predicate.player_approaching/predicate.player_undetected's approach_band - a
	// wave that spawned very far away shouldn't need an unrealistic amount of closing to trigger, so
	// anything above this is treated as if the loop had started exactly this far away.
	static final double APPROACH_BASELINE_CAP_BLOCKS = 18.0;

	/** Fraction of the (capped) loop-start baseline distance the player must have closed toward the
	 * wendigo to satisfy this band - low/medium/high scale linearly off a shared unit (2/9 of the
	 * baseline), e.g. an 18-block baseline gives low=4, medium=8, high=12 blocks closed. */
	static double approachCoverageFraction(String band) {
		return switch (band) {
			case "low" -> 2.0 / 9.0;
			case "high" -> 6.0 / 9.0;
			default -> 4.0 / 9.0; // "medium"
		};
	}

	static final double ARRIVAL_DISTANCE = 2.5;
	// How close combat.lunge_attack needs to close to before it lands a hit.
	static final double MELEE_RANGE = 3.0;
	// How close combat.break_torch needs to reach its target before it breaks it. Was tighter (2.0)
	// but the pathfinder's actual stopping point near a wall-mounted block routinely landed just
	// outside that - see isBreakTorchResolved's fallback for the other half of that fix (a clean,
	// non-failed navigation finish counts as "arrived" regardless of this exact number).
	static final double TORCH_BREAK_RANGE = 3.0;
	// Safety net so a movement/timing action can never hang the plan forever (e.g. an
	// unreachable path target) - overridden per-action where a real duration is known.
	static final int ACTION_TIMEOUT_TICKS = 200;
	static final double NEAREST_PLAYER_RADIUS = 64.0;
	// The band PlanRunner's internal.orbit primitive tries to hold from its locked target while no
	// plan is active - pathfind away below the min, toward above the max, hold position in between.
	// Tightens in smaller caves (see CaveScaleScanner) - nowhere to hold a wide ring in a mineshaft
	// corridor. First pass, adjust by feel.
	static double orbitMinDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 12.0;
			case MASSIVE -> 28.0;
			default -> 20.0; // NORMAL
		};
	}

	static double orbitMaxDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 18.0;
			case MASSIVE -> 40.0;
			default -> 30.0; // NORMAL
		};
	}
}
