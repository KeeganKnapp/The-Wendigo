package com.wendigo.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Rough classification of how big the open space around a position is - a mineshaft-like tunnel
 * reads very differently from a massive cavern even at the same light level, and this is meant to
 * be generally useful context beyond any one specific gate. Same ring-sampling-heuristic philosophy
 * as DarkSpotScanner/LightSourceScanner (not a true flood-fill/volume computation): casts short
 * horizontal rays out from a position in several directions to measure how far open space extends
 * before hitting a wall, checks ceiling height too (a mineshaft is tight in both dimensions, not
 * just narrow), and buckets the result.
 */
public final class CaveScaleScanner {
	private CaveScaleScanner() {
	}

	public enum CaveScale {
		TIGHT, // mineshaft / narrow corridor
		NORMAL,
		MASSIVE
	}

	private static final int RAY_COUNT = 8;
	private static final int MAX_RAY_DISTANCE = 20;
	private static final int MAX_CEILING_CHECK = 12;
	// Average open horizontal distance at/below this, with a low ceiling too, reads as TIGHT - a
	// mineshaft's 1-3 wide corridor tops out around here in both dimensions.
	private static final double TIGHT_MAX_AVG_DISTANCE = 4.0;
	private static final int TIGHT_MAX_CEILING = 3;
	// Average open distance at/above this, with a tall enough ceiling, reads as MASSIVE.
	private static final double MASSIVE_MIN_AVG_DISTANCE = 14.0;
	private static final int MASSIVE_MIN_CEILING = 6;

	public static CaveScale classify(Level level, BlockPos origin) {
		double totalDistance = 0;
		for (int i = 0; i < RAY_COUNT; i++) {
			double angle = (2 * Math.PI * i) / RAY_COUNT;
			totalDistance += castDistance(level, origin, Math.cos(angle), Math.sin(angle));
		}
		double avgDistance = totalDistance / RAY_COUNT;
		int ceiling = ceilingHeight(level, origin);

		if (avgDistance <= TIGHT_MAX_AVG_DISTANCE && ceiling <= TIGHT_MAX_CEILING) {
			return CaveScale.TIGHT;
		}
		if (avgDistance >= MASSIVE_MIN_AVG_DISTANCE && ceiling >= MASSIVE_MIN_CEILING) {
			return CaveScale.MASSIVE;
		}
		return CaveScale.NORMAL;
	}

	private static double castDistance(Level level, BlockPos origin, double dx, double dz) {
		for (int step = 1; step <= MAX_RAY_DISTANCE; step++) {
			BlockPos pos = origin.offset((int) Math.round(dx * step), 0, (int) Math.round(dz * step));
			if (!isPassable(level, pos)) {
				return step - 1;
			}
		}
		return MAX_RAY_DISTANCE;
	}

	private static int ceilingHeight(Level level, BlockPos origin) {
		for (int dy = 1; dy <= MAX_CEILING_CHECK; dy++) {
			if (!isPassable(level, origin.above(dy))) {
				return dy;
			}
		}
		return MAX_CEILING_CHECK;
	}

	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
