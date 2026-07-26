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
	 * purely for debugSay diagnostics, e.g. explaining why a control.while ended after 0 iterations. */
	static String debugSnapshot(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return "no player in range";
		}
		return String.format("distance=%.1f looking(corner_of_eye)=%b looking(in_view)=%b looking(dead_stare)=%b",
			self.distanceTo(player),
			isLookingAtSelf(player, self, "corner_of_eye"),
			isLookingAtSelf(player, self, "in_view"),
			isLookingAtSelf(player, self, "dead_stare"));
	}

	private static boolean isDark(Level level, BlockPos pos) {
		return level.getMaxLocalRawBrightness(pos) <= SemanticBands.DARKNESS_LIGHT_THRESHOLD;
	}

	private static boolean playerLookingAtSelf(JsonObject predicate, WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		return player != null && isLookingAtSelf(player, self, predicate.get("band").getAsString());
	}

	/** Used by PlanRunner's per-tick outcome polling (see EncounterHistory) - independent of whether
	 * the plan itself ever checks predicate.player_looking_at_self(dead_stare). */
	static boolean isDeadStare(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		return player != null && isLookingAtSelf(player, self, "dead_stare");
	}

	private static boolean isLookingAtSelf(Player player, WendigoEntity self, String band) {
		Vec3 toSelf = self.getEyePosition().subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toSelf);
		double cosThreshold = Math.cos(Math.toRadians(SemanticBands.lookAngleDegrees(band)));
		return alignment >= cosThreshold && player.hasLineOfSight(self);
	}

	/** Backs predicate.player_approaching: true once the player has closed at least this band's
	 * share of the distance-at-loop-start (whileBaselineDistance, capped - see SemanticBands.
	 * APPROACH_BASELINE_CAP_BLOCKS) toward the wendigo. NaN baseline (no active control.while) or no
	 * player in range both read as "nothing covered yet". */
	private static boolean playerApproaching(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		Player player = Targeting.nearestPlayer(self);
		return player != null && hasApproached(player, self, predicate.get("band").getAsString(), whileBaselineDistance);
	}

	private static boolean hasApproached(Player player, WendigoEntity self, String band, double whileBaselineDistance) {
		if (Double.isNaN(whileBaselineDistance)) {
			return false;
		}
		double covered = Math.max(0.0, whileBaselineDistance - self.distanceTo(player));
		double threshold = whileBaselineDistance * SemanticBands.approachCoverageFraction(band);
		return covered >= threshold;
	}

	/** Backs predicate.player_undetected: !looking_at_self(band) && !hasApproached(approach_band), as
	 * one atomic check - see its schema description for why this exists instead of making the model
	 * hand-compose predicate.and(predicate.not(...), predicate.not(...)) for this idiom. */
	private static boolean playerUndetected(JsonObject predicate, WendigoEntity self, double whileBaselineDistance) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return false; // nothing to hide from - not the same as "safely undetected", so don't loop forever on this
		}
		if (isLookingAtSelf(player, self, predicate.get("band").getAsString())) {
			return false;
		}
		return !hasApproached(player, self, predicate.get("approach_band").getAsString(), whileBaselineDistance);
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
