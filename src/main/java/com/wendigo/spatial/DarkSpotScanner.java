package com.wendigo.spatial;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Shared dark-spot scanning: ring-samples around an origin and checks for standable, passable
 * columns. Used both for single-spot lookups (retreat_to_dark, memory.store_dark_location) and
 * for building the wave system's multi-spot spawn/despawn candidate list.
 */
public final class DarkSpotScanner {
	private DarkSpotScanner() {
	}

	private static final int RING_SAMPLE_POINTS = 8;
	private static final int VERTICAL_SEARCH_RANGE = 3;
	// Minimum separation between two accepted wave spots so they don't cluster on the same ledge.
	private static final double MIN_SPOT_SEPARATION_SQR = 16.0;

	/**
	 * Samples a ring of points around origin at the given radius and returns the darkest standable
	 * one found, or null if none of the sampled columns had a valid spot. Uses origin's Y as the
	 * search plane (not a heightmap lookup) so it works underground, not just outdoors.
	 */
	public static BlockPos findDarkest(Level level, BlockPos origin, double radius) {
		BlockPos best = null;
		int bestLight = Integer.MAX_VALUE;

		for (int i = 0; i < RING_SAMPLE_POINTS; i++) {
			double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
			int dx = (int) Math.round(Math.cos(angle) * radius);
			int dz = (int) Math.round(Math.sin(angle) * radius);
			BlockPos candidate = standableColumn(level, origin.offset(dx, 0, dz));
			if (candidate == null) {
				continue;
			}
			int light = level.getMaxLocalRawBrightness(candidate);
			if (light < bestLight) {
				bestLight = light;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Scans up to {@code count} distinct dark standable spots around origin at increasing radius
	 * bands, ordered nearest to furthest - the wave system's spawn/despawn candidate list. Each
	 * band retries once at a wider radius if the first sample comes up empty, so the caller
	 * reliably gets {@code count} spots when the terrain allows it.
	 */
	public static List<BlockPos> findWaveSpots(Level level, BlockPos origin, int count) {
		double[] bandRadii = {8, 16, 24, 32, 40, 48};
		List<BlockPos> found = new ArrayList<>();

		for (double radius : bandRadii) {
			if (found.size() >= count) {
				break;
			}
			BlockPos spot = findDarkest(level, origin, radius);
			if (spot == null) {
				spot = findDarkest(level, origin, radius + 4);
			}
			BlockPos candidate = spot;
			if (candidate != null && found.stream().noneMatch(p -> p.distSqr(candidate) < MIN_SPOT_SEPARATION_SQR)) {
				found.add(candidate);
			}
		}
		return found;
	}

	private static BlockPos standableColumn(Level level, BlockPos column) {
		for (int dy = -VERTICAL_SEARCH_RANGE; dy <= VERTICAL_SEARCH_RANGE; dy++) {
			BlockPos pos = column.offset(0, dy, 0);
			if (isPassable(level, pos) && isPassable(level, pos.above()) && !isPassable(level, pos.below())) {
				return pos;
			}
		}
		return null;
	}

	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
