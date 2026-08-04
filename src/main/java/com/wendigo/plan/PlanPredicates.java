package com.wendigo.plan;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.wendigo.entity.WendigoEntity;

/** Evaluates a predicate JsonObject (see action_schema.json $defs.predicate) against live game state. */
final class PlanPredicates {
	private PlanPredicates() {
	}

	/** Bundles the small amount of per-run state a predicate evaluation might need beyond the live
	 * entity/world itself, threaded through the whole recursive evaluate/evaluateSimple chain as one
	 * value instead of an ever-growing raw parameter list. whileBaselineDistance is the wendigo-to-
	 * player distance captured when the currently-active control.while began (NaN if there isn't
	 * one - see PlanRunner, the only source of a real value). inViewStreakTicks/cornerOfEyeStreakTicks
	 * are PlanRunner's own graduated-look-band streak counters (see
	 * isLookedAtByAnyoneGraduated's own comment) - tracked continuously every tick regardless of
	 * while-state, so a streak that started before a stare-hold loop even began still counts once the
	 * loop actually starts checking it. */
	record Context(double whileBaselineDistance, int inViewStreakTicks, int cornerOfEyeStreakTicks) {
		static final Context NONE = new Context(Double.NaN, 0, 0);
	}

	static boolean evaluate(JsonObject predicate, WendigoEntity self) {
		return evaluate(predicate, self, Context.NONE);
	}

	static boolean evaluate(JsonObject predicate, WendigoEntity self, Context context) {
		String type = predicate.get("type").getAsString();
		return switch (type) {
			case "predicate.not" -> !evaluate(predicate.getAsJsonObject("operand"), self, context);
			case "predicate.and" -> allOperandsTrue(predicate, self, context);
			case "predicate.or" -> anyOperandTrue(predicate, self, context);
			default -> evaluateSimple(type, predicate, self, context);
		};
	}

	private static boolean allOperandsTrue(JsonObject predicate, WendigoEntity self, Context context) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (!evaluate(element.getAsJsonObject(), self, context)) {
				return false;
			}
		}
		return true;
	}

	private static boolean anyOperandTrue(JsonObject predicate, WendigoEntity self, Context context) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (evaluate(element.getAsJsonObject(), self, context)) {
				return true;
			}
		}
		return false;
	}

	private static boolean evaluateSimple(String type, JsonObject predicate, WendigoEntity self, Context context) {
		return switch (type) {
			case "predicate.player_looking_at_self" -> playerLookingAtSelf(predicate, self, context);
			case "predicate.player_approaching" -> playerApproaching(predicate, self, context.whileBaselineDistance());
			case "predicate.player_undetected" -> playerUndetected(predicate, self, context);
			case "predicate.self_in_darkness" -> isDark(self.level(), self.blockPosition());
			case "predicate.player_in_darkness" -> playerInDarkness(self);
			case "predicate.player_near_light_edge" -> playerNearLightEdge(self);
			case "predicate.dark_location_stored" -> self.getStoredDarkLocation() != null;
			case "predicate.player_distance" -> playerDistance(predicate, self);
			case "predicate.player_unreachable" -> self.isNavigationFailed();
			case "predicate.target_moving" -> targetMoving(self);
			case "predicate.target_is_stopped" -> targetIsStopped(self);
			default -> throw new IllegalArgumentException("Unknown predicate type: " + type);
		};
	}

	// The two ProximityBands values that don't represent "close enough to act" - see
	// containsWideDistanceGate's own comment.
	private static final String[] WIDE_DISTANCE_BANDS = {"medium", "far"};

	/** True if this predicate tree contains a predicate.player_distance node anywhere, regardless of
	 * band - a plain top-level type-string compare alone wouldn't catch it wrapped in
	 * predicate.not/and/or. Used by PlanRunner's control.while-start handling while a stare is
	 * currently held (modelIntendedStaring): a stare-hold must be gated purely on being noticed
	 * (predicate.player_looking_at_self/player_undetected), full stop, never on distance at any band
	 * - the "wait quietly near a lit spot without staring, then reveal once close enough to lunge"
	 * trap is the correct pattern for a combat-range wait instead, so a stare doesn't give away the
	 * face for nothing before the player's even in range. */
	static boolean containsDistanceGate(JsonObject predicate) {
		String type = predicate.get("type").getAsString();
		if (type.equals("predicate.player_distance")) {
			return true;
		}
		return switch (type) {
			case "predicate.not" -> containsDistanceGate(predicate.getAsJsonObject("operand"));
			case "predicate.and", "predicate.or" -> anyOperandHasDistanceGate(predicate);
			default -> false;
		};
	}

	private static boolean anyOperandHasDistanceGate(JsonObject predicate) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (containsDistanceGate(element.getAsJsonObject())) {
				return true;
			}
		}
		return false;
	}

	/** True if this predicate tree contains a predicate.player_distance node comparing against a
	 * non-combat-adjacent band (medium/far) anywhere. Used by PlanRunner's control.while-start
	 * handling regardless of whether a stare is involved: medium/far only ever amounts to "wait for
	 * the player to wander closer, or not" - they can just never do that, stalling the loop
	 * indefinitely. grab_distance/lunge_distance/close_quarters stay allowed for a non-staring wait -
	 * the schema's own bait-and-lunge example. */
	static boolean containsWideDistanceGate(JsonObject predicate) {
		String type = predicate.get("type").getAsString();
		if (type.equals("predicate.player_distance")) {
			String distance = predicate.get("distance").getAsString();
			for (String wide : WIDE_DISTANCE_BANDS) {
				if (wide.equals(distance)) {
					return true;
				}
			}
			return false;
		}
		return switch (type) {
			case "predicate.not" -> containsWideDistanceGate(predicate.getAsJsonObject("operand"));
			case "predicate.and", "predicate.or" -> anyOperandHasWideDistanceGate(predicate);
			default -> false;
		};
	}

	private static boolean anyOperandHasWideDistanceGate(JsonObject predicate) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (containsWideDistanceGate(element.getAsJsonObject())) {
				return true;
			}
		}
		return false;
	}

	/** Rewrites every predicate.player_distance node's "distance" field to lunge_distance in place,
	 * anywhere in this predicate tree (through not/and/or) - PlanRunner's own substitute for a
	 * medium/far band disallowed on a non-staring wait (see containsWideDistanceGate's own comment):
	 * narrows to a sensible combat-range default instead of discarding the model's chosen predicate
	 * type/comparator entirely, since (unlike the staring case, which swaps to a whole different
	 * predicate type) there's nothing wrong with the shape of what the model wrote here, just the
	 * specific band it picked. */
	static void narrowWideDistanceGatesToLungeDistance(JsonObject predicate) {
		String type = predicate.get("type").getAsString();
		if (type.equals("predicate.player_distance")) {
			predicate.addProperty("distance", "lunge_distance");
			return;
		}
		switch (type) {
			case "predicate.not" -> narrowWideDistanceGatesToLungeDistance(predicate.getAsJsonObject("operand"));
			case "predicate.and", "predicate.or" -> {
				for (var element : predicate.getAsJsonArray("operands")) {
					narrowWideDistanceGatesToLungeDistance(element.getAsJsonObject());
				}
			}
			default -> {
			}
		}
	}

	/** Human-readable live snapshot (distance to nearest player + looking state at every band) - used
	 * purely for debugSay diagnostics, e.g. explaining why a control.while ended after 0 iterations.
	 * Looking state reflects any nearby player (see isLookedAtByAnyone), not just the nearest one, and
	 * is the RAW (non-graduated) check - a diagnostic snapshot should show what's actually true right
	 * now, not the leniency plan predicates apply. */
	static String debugSnapshot(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return "no player in range";
		}
		return String.format("distance=%.1f looking(corner_of_eye)=%b looking(in_view)=%b looking(dead_stare)=%b",
			self.distanceTo(player),
			isLookedAtByAnyone(self, "corner_of_eye"),
			isLookedAtByAnyone(self, "in_view"),
			isLookedAtByAnyone(self, "dead_stare"));
	}

	private static boolean isDark(Level level, BlockPos pos) {
		return level.getMaxLocalRawBrightness(pos) <= SemanticBands.DARKNESS_LIGHT_THRESHOLD;
	}

	/** Global stare FOV for multiplayer: any player within range looking at the wendigo counts,
	 * regardless of who the wave is actually targeting/chasing - a group member noticing it from the
	 * side should "spot" it just as much as the one being stalked. Graduated (see
	 * isLookedAtByAnyoneGraduated) - a control-flow predicate, not a raw fact, so the leniency applies
	 * here. */
	private static boolean playerLookingAtSelf(JsonObject predicate, WendigoEntity self, Context context) {
		return isLookedAtByAnyoneGraduated(self, predicate.get("band").getAsString(), context);
	}

	/** Used by PlanRunner's per-tick outcome polling (see EncounterHistory) - independent of whether
	 * the plan itself ever checks predicate.player_looking_at_self(dead_stare). Same "any player"
	 * multiplayer semantics as playerLookingAtSelf, but deliberately the RAW (non-graduated) check -
	 * this is a factual outcome record ("did a real dead stare happen"), not a control-flow predicate
	 * that should get the graduated-timeout leniency. */
	static boolean isDeadStare(WendigoEntity self) {
		return isLookedAtByAnyone(self, "dead_stare");
	}

	/** Package-visible so PlanRunner's own updateLookStreaks can poll it every tick to feed the
	 * graduated-look-band streak counters (see isLookedAtByAnyoneGraduated). */
	static boolean isLookedAtByAnyone(WendigoEntity self, String band) {
		for (Player player : Targeting.nearbyPlayers(self)) {
			if (isLookingAtSelf(player, self, band)) {
				return true;
			}
		}
		return false;
	}

	/** The band one step wider (easier to satisfy) than the given one, or null if it's already the
	 * widest (corner_of_eye has no weaker fallback). */
	private static String nextWeakerBand(String band) {
		return switch (band) {
			case "dead_stare" -> "in_view";
			case "in_view" -> "corner_of_eye";
			default -> null; // "corner_of_eye"
		};
	}

	private static int streakTicksForBand(String band, Context context) {
		return switch (band) {
			case "in_view" -> context.inViewStreakTicks();
			case "corner_of_eye" -> context.cornerOfEyeStreakTicks();
			default -> 0;
		};
	}

	/** True if band is currently satisfied outright (isLookedAtByAnyone), OR if the next-weaker band
	 * has been continuously satisfied for SemanticBands.GRADUATED_LOOK_STREAK_TICKS - see that
	 * constant's own comment. E.g. wanting dead_stare but only ever reaching in_view still counts
	 * once the player's held in_view for 3 straight seconds; wanting in_view but only reaching
	 * corner_of_eye works the same way one band out. Prevents a stare-hold control.while from
	 * needing an exact dead-on look that a player who's merely glancing over might never quite give
	 * it - see PlanRunner's own control.while-start handling for the matching restriction on using
	 * predicate.player_distance to gate a stare instead. */
	private static boolean isLookedAtByAnyoneGraduated(WendigoEntity self, String band, Context context) {
		if (isLookedAtByAnyone(self, band)) {
			return true;
		}
		String weaker = nextWeakerBand(band);
		return weaker != null && streakTicksForBand(weaker, context) >= SemanticBands.GRADUATED_LOOK_STREAK_TICKS;
	}

	/** Package-visible so PlanRunner can reuse this exact facing check for the spear-defense
	 * mechanic (see isPlayerDefendingWithSpear) and its own graduated-look-streak tracking
	 * (updateLookStreaks) instead of duplicating the eye-position/dot-product math.
	 * <p>Deliberately targets WendigoEntity.getVisualEyePosition(), NOT self.getEyePosition() (left
	 * at vanilla's own default - see that method's own doc comment for the real suffocation bug that
	 * happened trying to raise it instead). The line-of-sight half needs the same substitution - see
	 * hasLineOfSightToSelf's own comment for why that can no longer just be the simple
	 * player.hasLineOfSight(self)/4-arg-overload convenience call. */
	static boolean isLookingAtSelf(Player player, WendigoEntity self, String band) {
		Vec3 selfEyes = self.getVisualEyePosition();
		Vec3 toSelf = selfEyes.subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toSelf);
		double cosThreshold = Math.cos(Math.toRadians(SemanticBands.lookAngleDegrees(band)));
		return alignment >= cosThreshold && hasLineOfSightToSelf(player, self, selfEyes);
	}

	/** Live-reported bug: on a tilted climbing surface, the visible model could read as clearly in
	 * view while a held stare-hold loop still never registered a real look. Root cause, confirmed by
	 * decompiling LivingEntity.hasLineOfSight(Entity, Block, Fluid, double): the 4-arg overload this
	 * used to call only substitutes the Y of its raycast target - the X/Z always come from self's own
	 * getX()/getZ(), the entity's raw physical anchor point, completely ignoring selfEyes' own X/Z.
	 * getVisualEyePosition() (the real rendered "head" bone's world position - see its own comment)
	 * can sit meaningfully offset from that raw anchor while climbing, so the alignment check above
	 * (correctly aimed at the visible head) and the old LOS raycast (aimed at a different point
	 * entirely) could disagree: the raycast traced to the physical anchor, still genuinely tucked
	 * behind a block corner, even though the visible head it was supposed to be checking had already
	 * cleared it - exactly why walking further around exposed it, tracing a real, different point.
	 * <p>
	 * Fixed two ways together, both the user's own explicit request: (1) trace toward selfEyes
	 * directly - its real X/Z, not just its Y grafted onto the physical anchor's X/Z - so the raycast
	 * finally checks the same point the alignment math already does; (2) rather than a single point
	 * even at the right location (still just one specific spot on the model, which a thin corner edge
	 * could still clip), sample a handful of points spread across the model's own real width
	 * (self.getBbWidth(), whichever pose is currently active) around that same head position and
	 * count it as seen if ANY of them has clear sight - "shooting multiple rays at every corner of
	 * the hitbox" rather than trusting one exact center point to always be representative. */
	private static boolean hasLineOfSightToSelf(Player player, WendigoEntity self, Vec3 selfEyes) {
		Vec3 from = player.getEyePosition();
		double halfWidth = self.getBbWidth() / 2.0;
		Vec3[] targets = {
			selfEyes,
			selfEyes.add(halfWidth, 0.0, halfWidth),
			selfEyes.add(halfWidth, 0.0, -halfWidth),
			selfEyes.add(-halfWidth, 0.0, halfWidth),
			selfEyes.add(-halfWidth, 0.0, -halfWidth),
		};
		for (Vec3 target : targets) {
			ClipContext context = new ClipContext(from, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
			if (player.level().clip(context).getType() == HitResult.Type.MISS) {
				return true;
			}
		}
		return false;
	}

	/** Backs predicate.player_approaching: true once ANY nearby player has closed at least this
	 * band's share of the distance-at-loop-start (whileBaselineDistance, capped - see SemanticBands.
	 * APPROACH_BASELINE_CAP_BLOCKS) toward the wendigo - multiplayer-aware, same "any player counts"
	 * idea as the stare-detection predicates, but the baseline/scaling itself is always anchored to
	 * whichever player was closest the moment the loop began (whileBaselineDistance's own capture
	 * point, unchanged), not recomputed per player. NaN baseline (no active control.while) reads as
	 * "nothing covered yet". */
	private static boolean playerApproaching(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		return hasApproachedByAnyone(self, predicate.get("band").getAsString(), whileBaselineDistance);
	}

	private static boolean hasApproachedByAnyone(WendigoEntity self, String band, double whileBaselineDistance) {
		if (Double.isNaN(whileBaselineDistance)) {
			return false;
		}
		double threshold = whileBaselineDistance * SemanticBands.approachCoverageFraction(band);
		for (Player player : Targeting.nearbyPlayers(self)) {
			double covered = Math.max(0.0, whileBaselineDistance - self.distanceTo(player));
			if (covered >= threshold) {
				return true;
			}
		}
		return false;
	}

	/** Backs predicate.player_undetected: !looking_at_self(band) && !hasApproached(approach_band), as
	 * one atomic check - see its schema description for why this exists instead of making the model
	 * hand-compose predicate.and(predicate.not(...), predicate.not(...)) for this idiom. Both halves
	 * are multiplayer-global now (any nearby player spotting it, or any nearby player closing in,
	 * counts) - see isLookedAtByAnyoneGraduated/hasApproachedByAnyone. The approach half's distance
	 * scaling still always comes from whileBaselineDistance, captured once from whoever was closest
	 * the moment the loop began - "any player" only changes who gets checked against that baseline,
	 * not how it's computed. The looking half is graduated the same as predicate.player_looking_at_self
	 * itself - a sustained near-miss glance counts as "detected" here too, not just an exact look. */
	private static boolean playerUndetected(JsonObject predicate, WendigoEntity self, Context context) {
		if (Targeting.nearestPlayer(self) == null) {
			return false; // nothing to hide from - not the same as "safely undetected", so don't loop forever on this
		}
		if (isLookedAtByAnyoneGraduated(self, predicate.get("band").getAsString(), context)) {
			return false;
		}
		return !hasApproachedByAnyone(self, predicate.get("approach_band").getAsString(), context.whileBaselineDistance());
	}

	private static boolean playerInDarkness(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		return player != null && isDark(self.level(), player.blockPosition());
	}

	private static boolean playerNearLightEdge(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null || !isDark(self.level(), player.blockPosition())) {
			return false;
		}
		BlockPos base = player.blockPosition();
		BlockPos[] ring = {base.north(2), base.south(2), base.east(2), base.west(2)};
		for (BlockPos neighbor : ring) {
			if (!isDark(self.level(), neighbor)) {
				return true;
			}
		}
		return false;
	}

	private static boolean playerDistance(JsonObject predicate, WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return false;
		}
		double threshold = ProximityBands.blocks(predicate.get("distance").getAsString());
		double actual = self.distanceTo(player);
		boolean closer = "closer_than".equals(predicate.get("comparator").getAsString());
		return closer ? actual < threshold : actual > threshold;
	}

	// Horizontal speed (blocks/tick) above which a player counts as genuinely moving - well above
	// idle physics jitter (a stationary player's deltaMovement isn't always exactly zero) but well
	// below even sneaking's own reduced speed, so walking/running/sneaking all still register.
	private static final double TARGET_MOVING_SPEED_THRESHOLD_SQR = 0.0009; // (~0.03 blocks/tick)^2

	/** Backs predicate.target_moving - the single target (Targeting.nearestPlayer, same as
	 * playerDistance/playerInDarkness) has real horizontal velocity right now. False with no target,
	 * same "nothing to check" default every other single-target predicate here uses. */
	private static boolean targetMoving(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		return player != null && horizontalSpeedSqr(player) > TARGET_MOVING_SPEED_THRESHOLD_SQR;
	}

	/** Backs predicate.target_is_stopped - deliberately NOT !targetMoving(self) (see the schema's own
	 * description): both read false when there's no target at all, rather than one of the two always
	 * being true regardless of whether there's actually a player to check. */
	private static boolean targetIsStopped(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		return player != null && horizontalSpeedSqr(player) <= TARGET_MOVING_SPEED_THRESHOLD_SQR;
	}

	private static double horizontalSpeedSqr(Player player) {
		Vec3 delta = player.getDeltaMovement();
		return delta.x * delta.x + delta.z * delta.z;
	}
}
