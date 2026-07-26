package com.wendigo.spatial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * Finds light-emitting blocks (torches, lanterns, etc.) near an origin - the counterpart to
 * DarkSpotScanner's search for the absence of light. Detects light sources generically via
 * BlockState.getLightEmission() > 0 rather than hardcoding specific block types, so any
 * light-emitting block works, not just vanilla torches.
 *
 * Ring-samples cheap seed columns (no per-column vertical scan) and hill-climbs each seed toward
 * the nearest source by following the block-light gradient - block light only, not
 * getMaxLocalRawBrightness, since that also carries sky light and would pull the climb toward
 * open sky/daylight instead of toward an actual emissive block.
 */
public final class LightSourceScanner {
	private LightSourceScanner() {
	}

	// More angular resolution than DarkSpotScanner's ring sampling (8) since this searches a much
	// larger radius (up to "far", ~35 blocks) for specific point-sources rather than any-standable-
	// column, so the wider net matters more here. Still a ring/radius heuristic, not exhaustive -
	// same first-pass tradeoff as the rest of this package.
	private static final int RING_SAMPLE_POINTS = 12;
	private static final double MIN_SEPARATION_SQR = 16.0;

	/**
	 * Ring-samples several radii around origin (up to maxRadius) and hill-climbs each sample point
	 * toward a light-emitting block via the block-light gradient, returning up to count distinct
	 * results ordered nearest-to-origin first. Block light only propagates ~15 blocks from its
	 * source, so a seed only finds anything if it lands within range of some source - the multiple
	 * radii/angles are what give this reasonable coverage.
	 */
	public static List<BlockPos> findLightSources(Level level, BlockPos origin, double maxRadius, int count) {
		List<BlockPos> found = new ArrayList<>();
		double[] radii = {5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0};

		for (double radius : radii) {
			if (radius > maxRadius || found.size() >= count) {
				continue;
			}
			for (int i = 0; i < RING_SAMPLE_POINTS; i++) {
				double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos seed = origin.offset(dx, 0, dz);
				BlockPos source = climbLightSource(level, seed);
				if (source != null && found.stream().noneMatch(p -> p.distSqr(source) < MIN_SEPARATION_SQR)) {
					found.add(source);
				}
				if (found.size() >= count) {
					break;
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		return found;
	}

	/**
	 * Greedily steps to whichever of the 6 face-neighbors has strictly higher block light than the
	 * current position, repeating until no neighbor is brighter (a local maximum). Returns that
	 * position if it's an actual light-emitting block, or null if the climb dead-ended on a bright
	 * spot that isn't a source (e.g. it wandered toward a lit but non-emissive area). Block light is
	 * bounded 0-15 and each step strictly increases it, so this always terminates. Package-visible
	 * so DarkSpotScanner can climb from an already-found dim spot instead of running an independent
	 * ring scan just to find the light source responsible for it - see DarkSpotScanner.findSpotDimSpots.
	 */
	static BlockPos climbLightSource(Level level, BlockPos start) {
		BlockPos current = start;

		while (true) {
			int currentLight = level.getBrightness(LightLayer.BLOCK, current);

			BlockPos best = current;
			int bestLight = currentLight;

			for (Direction dir : Direction.values()) {
				BlockPos next = current.relative(dir);
				int light = level.getBrightness(LightLayer.BLOCK, next);
				if (light > bestLight) {
					bestLight = light;
					best = next;
				}
			}

			if (best.equals(current)) {
				return level.getBlockState(best).getLightEmission() > 0 && hasPassableNeighbor(level, best)
					? current : null;
			}
			current = best;
		}
	}

	/**
	 * Whether at least one face-neighbor is passable - rejects light sources sealed on all sides
	 * (light can leak into a pocket through partially-opaque blocks the climb doesn't otherwise
	 * check for solidity) that the pathfinder could never actually get within break range of.
	 */
	private static boolean hasPassableNeighbor(Level level, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (level.getBlockState(pos.relative(dir)).getCollisionShape(level, pos.relative(dir)).isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
