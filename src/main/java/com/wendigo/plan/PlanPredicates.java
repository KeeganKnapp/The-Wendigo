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

	static boolean evaluate(JsonObject predicate, WendigoEntity self) {
		String type = predicate.get("type").getAsString();
		return switch (type) {
			case "predicate.not" -> !evaluate(predicate.getAsJsonObject("operand"), self);
			case "predicate.and" -> allOperandsTrue(predicate, self);
			case "predicate.or" -> anyOperandTrue(predicate, self);
			default -> evaluateSimple(type, predicate, self);
		};
	}

	private static boolean allOperandsTrue(JsonObject predicate, WendigoEntity self) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (!evaluate(element.getAsJsonObject(), self)) {
				return false;
			}
		}
		return true;
	}

	private static boolean anyOperandTrue(JsonObject predicate, WendigoEntity self) {
		for (var element : predicate.getAsJsonArray("operands")) {
			if (evaluate(element.getAsJsonObject(), self)) {
				return true;
			}
		}
		return false;
	}

	private static boolean evaluateSimple(String type, JsonObject predicate, WendigoEntity self) {
		return switch (type) {
			case "predicate.player_looking_at_self" -> playerLookingAtSelf(self);
			case "predicate.self_in_darkness" -> isDark(self.level(), self.blockPosition());
			case "predicate.player_in_darkness" -> playerInDarkness(self);
			case "predicate.player_near_light_edge" -> playerNearLightEdge(self);
			case "predicate.dark_location_stored" -> self.getStoredDarkLocation() != null;
			case "predicate.player_distance" -> playerDistance(predicate, self);
			case "predicate.player_unreachable" -> self.isNavigationFailed();
			default -> throw new IllegalArgumentException("Unknown predicate type: " + type);
		};
	}

	private static boolean isDark(Level level, BlockPos pos) {
		return level.getMaxLocalRawBrightness(pos) <= SemanticBands.DARKNESS_LIGHT_THRESHOLD;
	}

	private static boolean playerLookingAtSelf(WendigoEntity self) {
		Player player = Targeting.nearestPlayer(self);
		if (player == null) {
			return false;
		}
		Vec3 toSelf = self.getEyePosition().subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toSelf);
		double cosThreshold = Math.cos(Math.toRadians(SemanticBands.LOOK_AT_ANGLE_DEGREES));
		return alignment >= cosThreshold && player.hasLineOfSight(self);
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
		double threshold = SemanticBands.distanceBlocks(predicate.get("distance").getAsString());
		double actual = self.distanceTo(player);
		boolean closer = "closer_than".equals(predicate.get("comparator").getAsString());
		return closer ? actual < threshold : actual > threshold;
	}
}
