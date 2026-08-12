package com.wendigo.plan;

import net.minecraft.world.level.pathfinder.Path;

import com.wendigo.WendigoMod;
import com.wendigo.spatial.CaveScaleScanner.CaveScale;

/**
 * Maps the schema's semantic enums (speed, distance, search_radius, duration, max_iterations)
 * to real engine values, plus a few fixed tuning constants. Nothing here is scientifically
 * tuned - first-pass numbers to make the executor behave reasonably, adjust by feel.
 */
public final class SemanticBands {
	private SemanticBands() {
	}

	static double speedMultiplier(String speed) {
		return switch (speed) {
			case "slow" -> 1.25;
			case "fast" -> 1.75;
			default -> 1.5; // "normal"
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
			// The user's own explicit "20 seconds or more" request - a real "go dormant for a stretch"
			// option above long's own 11s ceiling, not just a bigger long.
			case "very_long" -> new int[] {400, 600};
			default -> new int[] {60, 100}; // "medium"
		};
	}

	/** Classifies an arbitrary elapsed-tick count into the nearest of this same duration ladder, by
	 * upper bound - used by PlanRunner's control.re_evaluate step log to describe how long an
	 * already-completed step actually took, not to roll a fresh wait. Monotonic: anything past long's
	 * own 220-tick ceiling reads as very_long, there's no higher band to fall back to. */
	static String classifyTicksAsDurationBand(int ticks) {
		if (ticks <= 10) {
			return "immediate";
		}
		if (ticks <= 40) {
			return "short";
		}
		if (ticks <= 100) {
			return "medium";
		}
		if (ticks <= 220) {
			return "long";
		}
		return "very_long";
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
	 * widened from a stricter 10 to 18 to compensate for a tilted-climbing-surface bug (see
	 * WendigoEntity.CRAWLING_DIMENSIONS' own comment): the visible model could read as clearly stared
	 * at while the underlying hitbox's real anchor sat tucked slightly behind a block corner from the
	 * camera's exact angle, so a held stare-hold loop would never see a real dead-on look register at
	 * all until the player physically closed enough distance to straighten out that offset. Brought
	 * back down to 14 (still looser than the original 10, not a full revert) now that
	 * CRAWLING_DIMENSIONS' own width bump gives that case some real margin on its own - the user's own
	 * follow-up report that flat floor/ceiling stares (unaffected by the tilted-surface bug either
	 * way) were registering too easily, in particular over-triggering the new stage-1 orbit exposure
	 * despawn (see WendigoManager.checkStage1Exposure), which needs an actually-narrow "truly spotted"
	 * signal to stay meaningful.
	 */
	static double lookAngleDegrees(String band) {
		return switch (band) {
			case "corner_of_eye" -> 60.0;
			case "dead_stare" -> 14.0;
			default -> 30.0; // "in_view"
		};
	}

	// How long a player has to continuously sit in the next-weaker look band before
	// PlanPredicates.isLookedAtByTargetGraduated treats the narrower band as satisfied anyway - user's
	// own explicit call. Exists so a stare-hold loop can't be stuck forever waiting for a dead-on look
	// that's merely close (a sustained corner_of_eye/in_view glance reads as "noticed" too, just more
	// slowly than an actual dead_stare would).
	static final int GRADUATED_LOOK_STREAK_TICKS = 60; // 3s

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

	static final double ARRIVAL_DISTANCE = 10;
	// How close combat.lunge_attack needs to close to before it lands a hit.
	static final double MELEE_RANGE = 1.0;
	// How close combat.break_torch needs to reach its target before it breaks it. Was tighter (2.0)
	// but the pathfinder's actual stopping point near a wall-mounted block routinely landed just
	// outside that - see isBreakTorchResolved's fallback for the other half of that fix (a clean,
	// non-failed navigation finish counts as "arrived" regardless of this exact number).
	static final double TORCH_BREAK_RANGE = 2.0;
	// Safety net so a movement/timing action can never hang the plan forever (e.g. an
	// unreachable path target) - overridden per-action where a real duration is known.
	static final int ACTION_TIMEOUT_TICKS = 200;
	static final double NEAREST_PLAYER_RADIUS = 64.0;
	// The band PlanRunner's internal.orbit primitive tries to hold from its locked target while no
	// plan is active - pathfind away below the min, toward above the max, hold position in between.
	// Tightens in smaller caves (see CaveScaleScanner) - nowhere to hold a wide ring in a mineshaft
	// corridor. Tightened from an earlier, wider pass (TIGHT 12-18/NORMAL 20-30/MASSIVE 28-40) now
	// that the wendigo can act directly from wherever it's orbiting (movement.approach_spot handles
	// any repositioning a plan actually needs) rather than always having to walk to a chosen spot
	// first - being farther out by default no longer buys anything, so there's no reason to hold as
	// wide a ring as before. First pass, adjust by feel.
	// Pushed 4 blocks further out on both ends (from TIGHT 8-14/NORMAL 12-20/MASSIVE 20-30), then
	// pulled back in 4 blocks again (the user's own explicit "push in the orbit distances a little
	// bit" follow-up, same session) - landing back on the original tightened-pass numbers documented
	// above, not a coincidence worth re-deriving, just how the two modest same-magnitude adjustments
	// happened to net out. Public (not package-private) specifically so WendigoManager
	// (com.wendigo.wave) can call these directly instead of maintaining its own hand-copied version -
	// that copy had already drifted stale (still held the pre-push-and-pull-back numbers) by the time
	// this was caught, exactly the kind of silent divergence a single shared source of truth avoids.
	public static double orbitMinDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 10.0;
			case MASSIVE -> 24.0;
			default -> 16.0; // NORMAL
		};
	}

	public static double orbitMaxDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> 16.0;
			case MASSIVE -> 34.0;
			default -> 24.0; // NORMAL
		};
	}

	// First pass, ~2.5x TIGHT's own 16-block max orbit distance - AWCAPI's climbing-aware evaluator
	// produces roughly one path node per traversed block in ordinary corridor movement, so a
	// genuinely winding but still-connected mineshaft corridor to a spot 12-16 blocks away in
	// straight-line terms should rarely need more than ~30 real steps; this leaves real slack for a
	// winding path while still catching "a small hole into a barely-connected pocket 60+ blocks of
	// real tunnel away" - the user's own explicit reported symptom. Adjust by feel once live-tested,
	// same as every other band in this class.
	private static final int TIGHT_ORBIT_NODE_COUNT_MAX = 80;

	/**
	 * Shared judgment for every orbit position-search call site (WendigoManager.tryEnterOrbit/
	 * relocateOrDiscard, PlanRunner.tickOrbit) - given a real AWCAPI path the caller already computed
	 * to/from a candidate position, decides whether that candidate is actually good enough to commit
	 * to. Always requires path.canReach() (vanilla's own "the search genuinely reached the target"
	 * flag, not a best-effort nearest approach). For TIGHT specifically (the user's own explicit
	 * scope call - NOT NORMAL, NOT MASSIVE, after weighing it against the original "two smallest,
	 * maybe three" framing), ALSO requires the path's own node count at/below
	 * TIGHT_ORBIT_NODE_COUNT_MAX: a technically-reachable candidate through a tiny connecting hole
	 * into a mostly-disconnected pocket can still demand an absurdly long real walk, which reads as
	 * "not really connected" even though canReach() alone says yes. NORMAL/MASSIVE skip the node-count
	 * check entirely - their own wider block-distance bands and open geometry make a long winding path
	 * a normal shape, not the cramped-pinch-point failure mode this check targets.
	 */
	public static boolean isAcceptableOrbitPath(Path path, CaveScale caveScale) {
		if (path == null || !path.canReach()) {
			return false;
		}
		if (caveScale != CaveScale.TIGHT) {
			return true;
		}
		return path.getNodeCount() <= TIGHT_ORBIT_NODE_COUNT_MAX;
	}

	/** Minimum search distance (blocks) movement.approach_spot/combat.teleport's own destination
	 * resolvers start from, per cave scale - reads WendigoTuningConfig.*CaveActionMinDistance (see
	 * their own field comments: the user's own explicit call to start all three equal for now, kept as
	 * 3 distinct config fields for later differentiation). See actionSearchMaxDistance for the other
	 * end of the search range - deliberately its own separate (not per-cave-scale) config value. */
	public static double actionSearchMinDistance(CaveScale caveScale) {
		return switch (caveScale) {
			case TIGHT -> WendigoMod.tuningConfig.tightCaveActionMinDistance;
			case MASSIVE -> WendigoMod.tuningConfig.massiveCaveActionMinDistance;
			default -> WendigoMod.tuningConfig.normalCaveActionMinDistance; // NORMAL
		};
	}

	/** Hard ceiling (blocks) movement.approach_spot/combat.teleport's own destination resolvers search
	 * out to - see WendigoTuningConfig.actionMaxSearchDistance's own field comment for why this is a
	 * dedicated value rather than reusing orbitDespawnDistance the way this used to. Not cave-scale-
	 * dependent (unlike actionSearchMinDistance) - the user's own explicit ask was one flat limit. */
	public static double actionSearchMaxDistance() {
		return WendigoMod.tuningConfig.actionMaxSearchDistance;
	}
}
