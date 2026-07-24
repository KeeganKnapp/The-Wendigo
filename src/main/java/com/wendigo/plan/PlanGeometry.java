package com.wendigo.plan;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import com.wendigo.entity.WendigoEntity;
import com.wendigo.spatial.DarkSpotScanner;

/** Spatial helpers for movement primitives: dark-spot scanning and relative-position targeting. */
final class PlanGeometry {
	private PlanGeometry() {
	}

	/** Darkest standable spot within radius of self - delegates to the shared DarkSpotScanner. */
	static BlockPos findDarkSpot(WendigoEntity self, double radius) {
		return DarkSpotScanner.findDarkest(self.level(), self.blockPosition(), radius);
	}

	/** Target position for movement.reposition, relative to the requested reference point. */
	static BlockPos repositionTarget(JsonObject step, WendigoEntity self) {
		Vec3 referencePos = referencePosition(step.get("reference").getAsString(), self);
		if (referencePos == null) {
			return null;
		}

		Vec3 awayFromRef = self.position().subtract(referencePos);
		if (awayFromRef.horizontalDistance() < 1.0e-4) {
			awayFromRef = new Vec3(1, 0, 0); // degenerate case: standing on top of the reference
		}
		awayFromRef = awayFromRef.normalize();

		double bandDistance = SemanticBands.distanceBlocks(step.get("distance").getAsString());
		String direction = step.get("direction").getAsString();
		// Rotation sign for orbit_cw/orbit_ccw is an assumption, not verified against a fixed
		// screen-space convention - swap the two if testing shows it orbiting the wrong way.
		Vec3 offset = switch (direction) {
			case "toward" -> awayFromRef.scale(-bandDistance);
			case "orbit_cw" -> rotateHorizontal(awayFromRef, -90).scale(bandDistance);
			case "orbit_ccw" -> rotateHorizontal(awayFromRef, 90).scale(bandDistance);
			default -> awayFromRef.scale(bandDistance); // "away"
		};
		return BlockPos.containing(referencePos.add(offset));
	}

	private static Vec3 referencePosition(String reference, WendigoEntity self) {
		if ("stored_dark_spot".equals(reference)) {
			BlockPos stored = self.getStoredDarkLocation();
			return stored != null ? Vec3.atBottomCenterOf(stored) : null;
		}
		Player player = Targeting.nearestPlayer(self);
		return player != null ? player.position() : null;
	}

	private static Vec3 rotateHorizontal(Vec3 v, double angleDegrees) {
		double rad = Math.toRadians(angleDegrees);
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		return new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos);
	}
}
