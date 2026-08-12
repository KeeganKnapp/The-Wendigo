package com.wendigo.plan;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.wendigo.entity.WendigoEntity;

/** Evaluates a predicate JsonObject (see action_schema.json $defs.predicate) against live game state.
 * Public (unlike most of this package - see PositionBands/SchemaBuilder's own precedent for the same
 * exception) specifically so com.wendigo.wave.WendigoManager can reuse isLookingAtSelf directly to
 * report whether the player is already looking at an already-active wendigo at plan-build time,
 * without duplicating the eye-position/dot-product math this class already owns. */
public final class PlanPredicates {
	private PlanPredicates() {
	}

	/** Bundles the small amount of per-run state a predicate evaluation might need beyond the live
	 * entity/world itself, threaded through the whole recursive evaluate/evaluateSimple chain as one
	 * value instead of an ever-growing raw parameter list. whileBaselineDistance is the wendigo-to-
	 * player distance captured when the currently-active control.while began (NaN if there isn't
	 * one - see PlanRunner, the only source of a real value). inViewStreakTicks/cornerOfEyeStreakTicks
	 * are PlanRunner's own graduated-look-band streak counters (see
	 * isLookedAtByTargetGraduated's own comment) - tracked continuously every tick regardless of
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
	 * Looking state reflects only the target (see isLookedAtByTarget), and is the RAW (non-graduated)
	 * check - a diagnostic snapshot should show what's actually true right now, not the leniency plan
	 * predicates apply. */
	static String debugSnapshot(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return "no player in range";
		}
		return String.format("distance=%.1f looking(corner_of_eye)=%b looking(in_view)=%b looking(dead_stare)=%b",
			self.distanceTo(player),
			isLookedAtByTarget(self, "corner_of_eye"),
			isLookedAtByTarget(self, "in_view"),
			isLookedAtByTarget(self, "dead_stare"));
	}

	private static boolean isDark(Level level, BlockPos pos) {
		return level.getMaxLocalRawBrightness(pos) <= SemanticBands.DARKNESS_LIGHT_THRESHOLD;
	}

	/** Target-only stare detection - the user's own explicit "changing player detected to just if the
	 * target detects them" reversal of the earlier "any player within range" multiplayer FOV design
	 * (see git history/this method's own prior revision): a group member noticing the wendigo from
	 * the side while it's actually haunting someone ELSE no longer counts as "spotted" - only the
	 * wave's own actual target (Targeting.nearestPlayer, which already resolves to the locked target
	 * first when one's set - see its own doc comment) does. Approach-based detection
	 * (hasApproachedByAnyone/predicate.player_approaching) deliberately stays "any player" - a
	 * DIFFERENT group member closing distance mid-stare is still a real threat/opportunity worth
	 * reacting to even if they're not who the encounter is officially "for" (see
	 * PlanRunner.isAnyPlayerApproachingDuringStare, the new hardcoded rule built on exactly that
	 * distinction). Graduated (see isLookedAtByTargetGraduated) - a control-flow predicate, not a raw
	 * fact, so the leniency applies here. */
	private static boolean playerLookingAtSelf(JsonObject predicate, WendigoEntity self, Context context) {
		return isLookedAtByTargetGraduated(self, predicate.get("band").getAsString(), context);
	}

	/** Used by PlanRunner's per-tick outcome polling (see EncounterHistory) - independent of whether
	 * the plan itself ever checks predicate.player_looking_at_self(dead_stare). Same target-only
	 * semantics as playerLookingAtSelf, but deliberately the RAW (non-graduated) check - this is a
	 * factual outcome record ("did a real dead stare happen"), not a control-flow predicate that
	 * should get the graduated-timeout leniency. Public (unlike most of this package - see
	 * PositionBands/SchemaBuilder/isLookingAtSelf's own precedent for the same exception) specifically
	 * so com.wendigo.wave.WendigoManager can reuse it directly for its own orbit exposure check
	 * (checkOrbitExposure's stage-1-only dead_stare trigger). */
	public static boolean isDeadStare(WendigoEntity self) {
		return isLookedAtByTarget(self, "dead_stare");
	}

	/** Package-visible so PlanRunner's own updateLookStreaks can poll it every tick to feed the
	 * graduated-look-band streak counters (see isLookedAtByTargetGraduated). Target-only - see
	 * playerLookingAtSelf's own doc comment for why this changed from "any nearby player." */
	static boolean isLookedAtByTarget(WendigoEntity self, String band) {
		Player target = Targeting.nearestPlayer(self);
		return target != null && isLookingAtSelf(target, self, band);
	}

	/** RAW line-of-sight only - no facing-angle requirement at all, unlike isLookedAtByTarget/
	 * isLookingAtSelf above - true if ANY nearby player currently has an unobstructed view of self's
	 * own live position, purely a geometry question independent of whether they're actually looking
	 * that way right now. Backs PlanRunner's own pre-stare obstruction check (see its own comment) -
	 * the user's own explicit request: before committing to a held stare, make sure it would even be
	 * VISIBLE at all if the player turned to look, not just currently unwatched because of angle
	 * (which is all isLookedAtByTarget/predicate.player_looking_at_self can tell you). Package-visible
	 * for that same PlanRunner consumer.
	 * <p>NOTE: currently unreferenced (PlanRunner.ensureVisibleBeforeStaring, the consumer this doc
	 * comment describes, doesn't exist in the current codebase - left as-is, out of scope for this
	 * pass, since it predates and is unrelated to the target-only stare-detection change above). */
	static boolean isVisibleToAnyPlayer(WendigoEntity self) {
		return isPositionVisibleToAnyPlayer(self, self.getVisualEyePosition());
	}

	/** Same raw line-of-sight test as isVisibleToAnyPlayer above, but against a HYPOTHETICAL eye
	 * position rather than self's own current live one - self itself is only ever used for
	 * getBbWidth()/self-exclusion inside hasLineOfSightToSelf (see its own doc comment), never its
	 * live position, so this is safe to call for a candidate spot the entity hasn't actually moved to
	 * yet. Backs PlanRunner's own stare-reposition candidate search (see its own comment) - each
	 * candidate is evaluated before committing to walk there for real. */
	static boolean isPositionVisibleToAnyPlayer(WendigoEntity self, Vec3 candidateEyes) {
		for (Player player : Targeting.nearbyPlayers(self)) {
			if (hasLineOfSightToSelf(player, self, candidateEyes)) {
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

	/** True if band is currently satisfied outright (isLookedAtByTarget), OR if the next-weaker band
	 * has been continuously satisfied for SemanticBands.GRADUATED_LOOK_STREAK_TICKS - see that
	 * constant's own comment. E.g. wanting dead_stare but only ever reaching in_view still counts
	 * once the player's held in_view for 3 straight seconds; wanting in_view but only reaching
	 * corner_of_eye works the same way one band out. Prevents a stare-hold control.while from
	 * needing an exact dead-on look that a player who's merely glancing over might never quite give
	 * it - see PlanRunner's own control.while-start handling for the matching restriction on using
	 * predicate.player_distance to gate a stare instead. */
	private static boolean isLookedAtByTargetGraduated(WendigoEntity self, String band, Context context) {
		if (isLookedAtByTarget(self, band)) {
			return true;
		}
		String weaker = nextWeakerBand(band);
		return weaker != null && streakTicksForBand(weaker, context) >= SemanticBands.GRADUATED_LOOK_STREAK_TICKS;
	}

	/** Package-visible so PlanRunner can reuse this exact facing check for its own graduated-
	 * look-streak tracking (updateLookStreaks) instead of duplicating the eye-position/dot-product
	 * math.
	 * <p>Deliberately targets WendigoEntity.getVisualEyePosition(), NOT self.getEyePosition() (left
	 * at vanilla's own default - see that method's own doc comment for the real suffocation bug that
	 * happened trying to raise it instead). The line-of-sight half needs the same substitution - see
	 * hasLineOfSightToSelf's own comment for why that can no longer just be the simple
	 * player.hasLineOfSight(self)/4-arg-overload convenience call. */
	public static boolean isLookingAtSelf(Player player, WendigoEntity self, String band) {
		return isLookingAtSelfWithinAngle(player, self, SemanticBands.lookAngleDegrees(band));
	}

	/** Same facing/line-of-sight check as isLookingAtSelf(band) above, but against a raw angle in
	 * degrees instead of one of the fixed named bands - for callers that need a value the band
	 * vocabulary doesn't cover, a continuum of values rather than one of dead_stare/in_view/
	 * corner_of_eye. isLookingAtSelf(band) itself now just resolves its band to a degrees value and
	 * delegates here - the two are the same check, just different entry points. */
	public static boolean isLookingAtSelfWithinAngle(Player player, WendigoEntity self, double angleDegrees) {
		Vec3 selfEyes = self.getVisualEyePosition();
		Vec3 toSelf = selfEyes.subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toSelf);
		double cosThreshold = Math.cos(Math.toRadians(angleDegrees));
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
	 * could still clip), sample a small ring of points immediately around that same head position (see
	 * HEAD_RING_POINTS/HEAD_SAMPLE_RADIUS right below) and count it as seen if ANY of them has clear
	 * sight - "shooting multiple rays at every corner of the hitbox" rather than trusting one exact
	 * center point to always be representative.
	 * <p>
	 * SIMPLIFIED (the user's own explicit later request) from an earlier version that sampled a much
	 * wider, posture-aware grid spanning the whole body (separate tall/narrow-vs-short/wide vertical-
	 * offset and horizontal-reach tables for standing vs crawling, matching the entity's own real
	 * hitbox dimensions) back down to a small, POSTURE-AGNOSTIC ring right at the head itself: "we just
	 * need to make sure where his head is at in worldspace is visible to the player... maybe still use
	 * multiple rays but just near his head/eyes." No posture branching is needed here anymore -
	 * selfEyes (getVisualEyePosition()) already resolves to the correct rig-driven head position for
	 * whichever pose is currently active, so the sample ring just needs to sit close to that one point,
	 * not re-derive the whole body's own footprint on top of it. Shared unchanged by both stare
	 * DETECTION (isLookingAtSelf above, angle + this) and stare OBSTRUCTION checks
	 * (PlanRunner.ensureVisibleBeforeStaring, via isVisibleToAnyPlayer/isPositionVisibleToAnyPlayer
	 * below, this alone with no angle requirement) - the same single head-position check now backs
	 * both, rather than each reasoning about "the wendigo's visibility" independently. */
	private static final int HEAD_RING_POINTS = 6;
	private static final double HEAD_SAMPLE_RADIUS = 0.25;

	/** Public specifically so com.wendigo.sound.WendigoSounds can reuse this exact per-player head-
	 * visibility check for sound.ambient_cue(stare)'s own live "obstructed line of sight" gate - the
	 * user's own explicit request: a stare sound should only actually reach a player who can genuinely
	 * see the wendigo's face right now, not everyone in the level regardless of walls between them. */
	public static boolean hasLineOfSightToSelf(Player player, WendigoEntity self, Vec3 selfEyes) {
		Vec3 from = player.getEyePosition();
		if (hasClearRayTo(player, from, selfEyes)) {
			return true;
		}
		for (int i = 0; i < HEAD_RING_POINTS; i++) {
			double angle = 2.0 * Math.PI * i / HEAD_RING_POINTS;
			Vec3 target = selfEyes.add(Math.cos(angle) * HEAD_SAMPLE_RADIUS, 0.0, Math.sin(angle) * HEAD_SAMPLE_RADIUS);
			if (hasClearRayTo(player, from, target)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasClearRayTo(Player player, Vec3 from, Vec3 target) {
		ClipContext context = new ClipContext(from, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
		return player.level().clip(context).getType() == HitResult.Type.MISS;
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

	/** Package-visible so PlanRunner's own isAnyPlayerApproachingDuringStare can reuse this exact
	 * "any player" approach check directly for its new hardcoded stare-interrupt rule - see that
	 * method's own doc comment for why approach detection deliberately stays multiplayer-global even
	 * though look detection (isLookedAtByTarget) no longer is. */
	static boolean hasApproachedByAnyone(WendigoEntity self, String band, double whileBaselineDistance) {
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
	 * hand-compose predicate.and(predicate.not(...), predicate.not(...)) for this idiom. The looking
	 * half is target-only now (see isLookedAtByTargetGraduated/playerLookingAtSelf's own comment on
	 * why); the approach half stays multiplayer-global (any nearby player closing in counts) - see
	 * hasApproachedByAnyone. The approach half's distance scaling still always comes from
	 * whileBaselineDistance, captured once from whoever was closest the moment the loop began - "any
	 * player" only changes who gets checked against that baseline, not how it's computed. The looking
	 * half is graduated the same as predicate.player_looking_at_self itself - a sustained near-miss
	 * glance counts as "detected" here too, not just an exact look. */
	private static boolean playerUndetected(JsonObject predicate, WendigoEntity self, Context context) {
		if (Targeting.nearestPlayer(self) == null) {
			return false; // nothing to hide from - not the same as "safely undetected", so don't loop forever on this
		}
		if (isLookedAtByTargetGraduated(self, predicate.get("band").getAsString(), context)) {
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

	// The user's own explicit request: a wendigo hanging on a ceiling directly above a player 15
	// blocks below reads as "far" under plain 3D distance even though it's one movement.drop away
	// from being right on top of them - meaning a control.while(farther_than lunge_distance) wait
	// meant to hold until the player is genuinely close would never resolve while perched up there,
	// no matter how close the player actually walks underneath. Matches PlanRunner's own
	// DROP_MAX_SEARCH_HEIGHT (same 30-block reach a real movement.drop's own column scan uses to find
	// a landing floor) - not literally shared, private to a different class, but deliberately the same
	// value so this predicate can't read "close" for a drop that DROP_MAX_SEARCH_HEIGHT itself
	// wouldn't actually be able to find a landing spot for.
	private static final double CYLINDER_MAX_DROP_HEIGHT = 30.0;

	/** Effective distance-to-player for predicate.player_distance: plain 3D Euclidean while genuinely
	 * on a floor (getGroundSide()==DOWN, the reliable live signal - see WendigoEntity's own doc
	 * comment) or while the player isn't below within droppable range, but horizontal-only (straight
	 * down a vertical cylinder from the wendigo's own column, ignoring the vertical gap entirely)
	 * whenever it's attached to a wall/ceiling with the player somewhere beneath it within
	 * CYLINDER_MAX_DROP_HEIGHT - the wendigo's own real option in that situation isn't "walk the
	 * remaining 3D distance," it's "drop straight down," so distance should reflect what a drop would
	 * actually close, not the misleading long diagonal a plain climbing route or straight-line
	 * distance would otherwise report. */
	private static double effectiveDistanceToPlayer(WendigoEntity self, Player player) {
		if (self.getGroundSide() == Direction.DOWN) {
			return self.distanceTo(player);
		}
		double verticalGap = self.getY() - player.getY();
		if (verticalGap < 0.0 || verticalGap > CYLINDER_MAX_DROP_HEIGHT) {
			return self.distanceTo(player);
		}
		double dx = self.getX() - player.getX();
		double dz = self.getZ() - player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static boolean playerDistance(JsonObject predicate, WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return false;
		}
		double threshold = ProximityBands.blocks(predicate.get("distance").getAsString());
		double actual = effectiveDistanceToPlayer(self, player);
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
