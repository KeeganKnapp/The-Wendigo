package com.wendigo.plan;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.wendigo.entity.WendigoEntity;

/** Evaluates a predicate JsonObject (see action_schema.json $defs.predicate) against live game state. */
final class PlanPredicates {
	private PlanPredicates() {
	}

	/** No active control.while baseline - predicate.player_approaching/player_undetected's
	 * approach_band always reads as "nothing covered yet" when evaluated this way (e.g. from
	 * control.if or a global_rule, neither of which loop). */
	static boolean evaluate(JsonObject predicate, WendigoEntity self) {
		return evaluate(predicate, self, Double.NaN);
	}

	/** whileBaselineDistance is the wendigo-to-player distance captured when the currently-active
	 * control.while began (NaN if there isn't one) - see PlanRunner, which is the only caller that
	 * ever passes a real value. */
	static boolean evaluate(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		String type = predicate.get("type").getAsString();
		return switch (type) {
			case "predicate.not" -> !evaluate(predicate.getAsJsonObject("operand"), self, whileBaselineDistance);
			case "predicate.and" -> allOperandsTrue(predicate, self, whileBaselineDistance);
			case "predicate.or" -> anyOperandTrue(predicate, self, whileBaselineDistance);
			default -> evaluateSimple(type, predicate, self, whileBaselineDistance);
		};
	}

	private static boolean allOperandsTrue(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (!evaluate(element.getAsJsonObject(), self, whileBaselineDistance)) {
				return false;
			}
		}
		return true;
	}

	private static boolean anyOperandTrue(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (evaluate(element.getAsJsonObject(), self, whileBaselineDistance)) {
				return true;
			}
		}
		return false;
	}

	private static boolean evaluateSimple(String type, JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		return switch (type) {
			case "predicate.player_looking_at_self" -> playerLookingAtSelf(predicate, self);
			case "predicate.player_approaching" -> playerApproaching(predicate, self, whileBaselineDistance);
			case "predicate.player_undetected" -> playerUndetected(predicate, self, whileBaselineDistance);
			case "predicate.self_in_darkness" -> isDark(self.level(), self.blockPosition());
			case "predicate.player_in_darkness" -> playerInDarkness(self);
			case "predicate.player_near_light_edge" -> playerNearLightEdge(self);
			case "predicate.dark_location_stored" -> self.getStoredDarkLocation() != null;
			case "predicate.player_distance" -> playerDistance(predicate, self);
			case "predicate.player_unreachable" -> self.isNavigationFailed();
			default -> throw new IllegalArgumentException("Unknown predicate type: " + type);
		};
	}

	/** Human-readable live snapshot (distance to nearest player + looking state at every band) - used
	 * purely for debugSay diagnostics, e.g. explaining why a control.while ended after 0 iterations.
	 * Looking state reflects any nearby player (see isLookedAtByAnyone), not just the nearest one. */
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
	 * side should "spot" it just as much as the one being stalked. */
	private static boolean playerLookingAtSelf(JsonObject predicate, WendigoEntity self) {
		return isLookedAtByAnyone(self, predicate.get("band").getAsString());
	}

	/** Used by PlanRunner's per-tick outcome polling (see EncounterHistory) - independent of whether
	 * the plan itself ever checks predicate.player_looking_at_self(dead_stare). Same "any player"
	 * multiplayer semantics as playerLookingAtSelf. */
	static boolean isDeadStare(WendigoEntity self) {
		return isLookedAtByAnyone(self, "dead_stare");
	}

	private static boolean isLookedAtByAnyone(WendigoEntity self, String band) {
		for (Player player : Targeting.nearbyPlayers(self)) {
			if (isLookingAtSelf(player, self, band)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isLookingAtSelf(Player player, WendigoEntity self, String band) {
		Vec3 toSelf = self.getEyePosition().subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toSelf);
		double cosThreshold = Math.cos(Math.toRadians(SemanticBands.lookAngleDegrees(band)));
		return alignment >= cosThreshold && player.hasLineOfSight(self);
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
	 * counts) - see isLookedAtByAnyone/hasApproachedByAnyone. The approach half's distance scaling
	 * still always comes from whileBaselineDistance, captured once from whoever was closest the
	 * moment the loop began - "any player" only changes who gets checked against that baseline, not
	 * how it's computed. */
	private static boolean playerUndetected(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		if (Targeting.nearestPlayer(self) == null) {
			return false; // nothing to hide from - not the same as "safely undetected", so don't loop forever on this
		}
		if (isLookedAtByAnyone(self, predicate.get("band").getAsString())) {
			return false;
		}
		return !hasApproachedByAnyone(self, predicate.get("approach_band").getAsString(), whileBaselineDistance);
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
}
