package com.wendigo.spatial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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

	// 8 points spaced 45 degrees apart at, say, radius 40 leaves wide gaps between sampled columns -
	// easy to miss a real passage entirely in an irregularly-shaped cave. Matches
	// LightSourceScanner's resolution for the same reason.
	private static final int RING_SAMPLE_POINTS = 12;
	// Distinct starting heights tried per sampled column, relative to the search origin - accounts
	// for hills/valleys in cave terrain instead of assuming the floor is flat at the origin's own Y.
	// Order matters: feet first, then progressively higher (a nearby rise/ridge), only falling back
	// to below (a dip/pit) last.
	private static final int[] LAYER_Y_OFFSETS = {0, 4, 8, -4};
	// How far straight down from each layer's starting height a column is allowed to relax onto the
	// actual floor - small and deliberately downward-only (gravity, not floating up to one), so
	// hill/valley coverage comes from having multiple layers rather than one big vertical window.
	// The old single ±10 window could "relax" all the way onto a totally disconnected, unreachable
	// cavern floor far below the real ground - this keeps each layer's own search shallow enough
	// that it can't wander that far from where it actually started.
	private static final int RELAX_DEPTH = 4;
	// Minimum separation between two accepted wave spots so they don't cluster on the same ledge.
	private static final double MIN_SPOT_SEPARATION_SQR = 16.0;
	// Hard cap on what counts as a "dark spot" at all - never return a candidate brighter than
	// this even if it's the darkest one sampled, so a bad neighborhood yields fewer/no spots
	// instead of a technically-darkest-but-still-lit one. Separate from the stricter
	// SemanticBands.DARKNESS_LIGHT_THRESHOLD (self_in_darkness) - this is "acceptable to spawn/
	// despawn/hide at", not "fully hidden". Public so PlanRunner can reuse it as the exact same bar
	// for "is it OK to actually finish despawning here" - see PlanRunner.readyToVanish.
	public static final int MAX_DARK_LIGHT = 5;

	/**
	 * Samples a ring of points around origin at the given radius and returns the darkest standable
	 * one found, or null if none of the sampled columns had a valid spot within MAX_DARK_LIGHT.
	 * Each column is resolved relative to origin's own Y via standableColumn's layered relax search
	 * (not a heightmap lookup), so this works underground, not just outdoors, and follows hills/
	 * valleys around origin rather than assuming a flat floor at its exact height.
	 */
	public static BlockPos findDarkest(Level level, BlockPos origin, double radius) {
		return findDarkestBiased(level, origin, radius, null);
	}

	/**
	 * Same as findDarkest, but skips any candidate whose bearing from origin points roughly
	 * toward/past avoid (within 90 degrees of it) when avoid is non-null - for "flee" style retreats,
	 * where the destination isn't just "somewhere dark" but "away from the threat". A plain
	 * findDarkest has no such bias, so a retreat could easily pick the nearest darkness even when
	 * that happens to sit in the same direction as (or past) the thing it's fleeing, which read as
	 * heading toward/through the player rather than away from them. Falls back to the unrestricted
	 * search if nothing qualifies in the away hemisphere - some darkness reachable beats none at all.
	 */
	public static BlockPos findDarkestAwayFrom(Level level, BlockPos origin, double radius, BlockPos avoid) {
		if (avoid == null) {
			return findDarkest(level, origin, radius);
		}
		double bearingToAvoidDegrees = Math.toDegrees(Math.atan2(avoid.getZ() - origin.getZ(), avoid.getX() - origin.getX()));
		BlockPos away = findDarkestBiased(level, origin, radius,
			angle -> Math.abs(normalizeDegrees(Math.toDegrees(angle) - bearingToAvoidDegrees)) >= 90.0);
		return away != null ? away : findDarkest(level, origin, radius);
	}

	private static BlockPos findDarkestBiased(Level level, BlockPos origin, double radius, java.util.function.DoublePredicate angleFilter) {
		BlockPos best = null;
		int bestLight = Integer.MAX_VALUE;

		// Ties (light == bestLight) never overwrite best, so a fixed i=0..11 traversal order always
		// let the very first index (due +X) win any tie - in a totally dark cave, most/all 12 points
		// commonly tie at light 0, so every band radius kept picking the same due-+X direction,
		// visibly lining every scanned spot up on one side of the player instead of spreading around
		// them. Shuffling the traversal order per call makes tie-breaks land on a different direction
		// each time instead.
		for (int i : shuffledRingIndices()) {
			double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
			if (angleFilter != null && !angleFilter.test(angle)) {
				continue;
			}
			int dx = (int) Math.round(Math.cos(angle) * radius);
			int dz = (int) Math.round(Math.sin(angle) * radius);
			BlockPos candidate = standableColumn(level, origin.offset(dx, 0, dz));
			if (candidate == null) {
				continue;
			}
			int light = level.getMaxLocalRawBrightness(candidate);
			if (light <= MAX_DARK_LIGHT && light < bestLight) {
				bestLight = light;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * True if origin itself, or any point within maxRadius blocks of it, is dark enough to count as
	 * a valid dark spot (see MAX_DARK_LIGHT) - a cheaper existence check than findDarkest for callers
	 * that only need to know whether somewhere safe exists nearby, not find the single darkest spot.
	 * Samples origin directly plus a few ring radii up to maxRadius (findDarkest only ring-samples at
	 * one exact radius, which could miss anything closer than that).
	 */
	public static boolean hasDarkSpotWithin(Level level, BlockPos origin, double maxRadius) {
		if (level.getMaxLocalRawBrightness(origin) <= MAX_DARK_LIGHT) {
			return true;
		}
		for (double radius = 2.0; radius <= maxRadius; radius += 2.0) {
			if (findDarkest(level, origin, radius) != null) {
				return true;
			}
		}
		return false;
	}

	// Offsets tried (in order) around each band's nominal radius before giving up on that band - a
	// single ring sample can easily land entirely on solid rock at larger radii, so this retries at
	// a spread of nearby radii rather than just one wider fallback.
	private static final double[] BAND_RADIUS_OFFSETS = {0, 4, -4, 8, -8};

	/**
	 * Scans up to {@code count} distinct dark standable spots around origin at increasing radius
	 * bands, ordered nearest to furthest - the wave system's spawn/despawn candidate list. Each
	 * band tries a spread of nearby radii (see BAND_RADIUS_OFFSETS) before giving up, so the caller
	 * reliably gets {@code count} spots when the terrain allows it.
	 */
	public static List<BlockPos> findWaveSpots(Level level, BlockPos origin, int count) {
		double[] bandRadii = {8, 16, 24, 32, 40, 48};
		List<BlockPos> found = new ArrayList<>();

		for (double bandRadius : bandRadii) {
			if (found.size() >= count) {
				break;
			}
			BlockPos candidate = null;
			for (double offset : BAND_RADIUS_OFFSETS) {
				double radius = bandRadius + offset;
				if (radius <= 0) {
					continue;
				}
				candidate = findDarkest(level, origin, radius);
				if (candidate != null) {
					break;
				}
			}
			BlockPos accepted = candidate;
			if (accepted != null && found.stream().noneMatch(p -> p.distSqr(accepted) < MIN_SPOT_SEPARATION_SQR)) {
				found.add(accepted);
			}
		}
		return found;
	}

	// A "dim spot" sits just above the darkness cutoff used elsewhere (predicate.self_in_darkness,
	// SemanticBands.DARKNESS_LIGHT_THRESHOLD = 7) but below fully lit - the edge of a light source,
	// not inside it. com.wendigo.spatial has no dependency on com.wendigo.plan (SemanticBands is
	// package-private there anyway), so these are kept in sync by hand rather than shared.
	private static final int DIM_LIGHT_MIN = 3;
	private static final int DIM_LIGHT_MAX = 5;
	// Ring-sampled (like findDarkest), not marched along a single straight line to "towards" - real
	// cave terrain almost never keeps a literal straight line open and standable the whole way, so a
	// line march mostly hit solid rock and came back empty. Rings still bias toward "towards" (see
	// DIM_ANGLE_TOLERANCE_DEGREES) without requiring exact alignment with it.
	private static final double[] DIM_SEARCH_RADII = {3.0, 6.0, 9.0, 12.0};
	private static final double DIM_ANGLE_TOLERANCE_DEGREES = 70.0;

	/**
	 * Ring-samples a few radii around origin, keeping standable columns whose light level falls in
	 * the dim band (edge-of-light, not fully dark or fully lit) and whose bearing from origin is
	 * roughly toward "towards" (within DIM_ANGLE_TOLERANCE_DEGREES) - a real edge-of-light waypoint
	 * near the player, not just any dim patch nearby regardless of direction. Returns up to count
	 * spots, closest-to-"towards" first. Used by movement.approach_dim_spot's live resolution (the
	 * wendigo's current position toward the nearest player) - the wave-context per-spot scan used to
	 * call this too, but that wanted the opposite of an angle bias (see findSpotDimSpots).
	 */
	public static List<BlockPos> findDimSpots(Level level, BlockPos origin, BlockPos towards, int count) {
		List<BlockPos> found = new ArrayList<>();
		double dx0 = towards.getX() - origin.getX();
		double dz0 = towards.getZ() - origin.getZ();
		double totalDistance = Math.sqrt(dx0 * dx0 + dz0 * dz0);
		if (totalDistance < 1.0) {
			return found;
		}
		double bearingToTargetDegrees = Math.toDegrees(Math.atan2(dz0, dx0));

		for (double radius : DIM_SEARCH_RADII) {
			if (radius >= totalDistance || found.size() >= count) {
				continue;
			}
			for (int i : shuffledRingIndices()) {
				double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
				double angleDelta = Math.abs(normalizeDegrees(Math.toDegrees(angle) - bearingToTargetDegrees));
				if (angleDelta > DIM_ANGLE_TOLERANCE_DEGREES) {
					continue;
				}
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos candidate = standableColumn(level, origin.offset(dx, 0, dz));
				if (candidate == null) {
					continue;
				}
				int light = level.getMaxLocalRawBrightness(candidate);
				if (light >= DIM_LIGHT_MIN && light <= DIM_LIGHT_MAX
					&& found.stream().noneMatch(p -> p.distSqr(candidate) < MIN_SPOT_SEPARATION_SQR)) {
					found.add(candidate);
					if (found.size() >= count) {
						break;
					}
				}
			}
		}
		found.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(towards)));
		return found;
	}

	// Per-dark-spot torch discovery - distinct radius tiers instead of one narrow band searched
	// repeatedly. findDimSpots' close-together DIM_SEARCH_RADII, biased toward a single "towards"
	// direction, meant nearly every dim spot around a given dark spot climbed to the same one nearest
	// light source - a far spawn point's own torch cluster in any direction other than toward the
	// player was routinely invisible to both the prompt context and the live combat.break_torch
	// resolution, so distant torches almost never got broken even when the wendigo was standing right
	// next to a cluster of them. This scans omnidirectionally (no angle bias at all - a spot's nearby
	// torches matter regardless of which side of it they're on) at five increasingly wide radii, one
	// attempt per tier, so a spot with several torches at different distances actually surfaces more
	// than just the closest one. Outer radius (35) matches LightSourceScanner's own hard cap, so
	// anything found here is still within reach of PlanRunner's live break_torch resolution from the
	// same spot.
	private static final double[] SPOT_TORCH_SEARCH_RADII = {4.0, 9.0, 16.0, 25.0, 35.0};

	/**
	 * One dim-spot-then-climb-to-light-source attempt per SPOT_TORCH_SEARCH_RADII tier, all
	 * omnidirectional around spot (see the field's own comment) - up to 5 dim spots/torches, fewer if
	 * a given tier's ring came up empty. dimSpots() entries whose source can't be climbed to (or is
	 * sealed off) still count as valid approach-and-stare targets, they just contribute nothing to
	 * lightSources(), which is deduplicated since multiple tiers commonly climb to the identical
	 * torch.
	 */
	public static RelevantSpots findSpotDimSpots(Level level, BlockPos spot) {
		List<BlockPos> dimSpots = new ArrayList<>();
		List<BlockPos> lightSources = new ArrayList<>();
		for (double radius : SPOT_TORCH_SEARCH_RADII) {
			BlockPos found = findDimSpotOmnidirectional(level, spot, radius, dimSpots);
			if (found == null) {
				continue;
			}
			dimSpots.add(found);
			BlockPos source = LightSourceScanner.climbLightSource(level, found);
			if (source != null && lightSources.stream().noneMatch(source::equals)) {
				lightSources.add(source);
			}
		}
		return new RelevantSpots(dimSpots, lightSources);
	}

	/** Ring-samples one radius around origin (shuffled order, see findDarkest) for a single standable
	 * column in the dim light band, skipping anything too close to an already-accepted dim spot -
	 * every direction is fair game, unlike findDimSpots' angle-toward-target bias. */
	private static BlockPos findDimSpotOmnidirectional(Level level, BlockPos origin, double radius, List<BlockPos> alreadyFound) {
		for (int i : shuffledRingIndices()) {
			double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
			int dx = (int) Math.round(Math.cos(angle) * radius);
			int dz = (int) Math.round(Math.sin(angle) * radius);
			BlockPos candidate = standableColumn(level, origin.offset(dx, 0, dz));
			if (candidate == null) {
				continue;
			}
			int light = level.getMaxLocalRawBrightness(candidate);
			if (light >= DIM_LIGHT_MIN && light <= DIM_LIGHT_MAX
				&& alreadyFound.stream().noneMatch(p -> p.distSqr(candidate) < MIN_SPOT_SEPARATION_SQR)) {
				return candidate;
			}
		}
		return null;
	}

	public record RelevantSpots(List<BlockPos> dimSpots, List<BlockPos> lightSources) {
	}

	/** Shuffled 0..RING_SAMPLE_POINTS-1 traversal order - see findDarkest's own note on why a fixed
	 * order systematically biased ring scans toward one direction. */
	private static List<Integer> shuffledRingIndices() {
		List<Integer> order = new ArrayList<>(RING_SAMPLE_POINTS);
		for (int i = 0; i < RING_SAMPLE_POINTS; i++) {
			order.add(i);
		}
		Collections.shuffle(order, ThreadLocalRandom.current());
		return order;
	}

	/** Wraps a degree delta to (-180, 180]. */
	private static double normalizeDegrees(double degrees) {
		double wrapped = degrees % 360.0;
		if (wrapped <= -180.0) {
			wrapped += 360.0;
		} else if (wrapped > 180.0) {
			wrapped -= 360.0;
		}
		return wrapped;
	}

	/**
	 * Tries each layer in LAYER_Y_OFFSETS in turn (feet, then higher, then below); within a layer,
	 * relaxes straight down from that layer's own starting height, up to RELAX_DEPTH blocks, to find
	 * the nearest actual floor - the first standable position found this way wins. Returns null if
	 * every layer's relax search comes up solid/empty-handed (e.g. the whole column is inside rock).
	 */
	private static BlockPos standableColumn(Level level, BlockPos column) {
		for (int layerOffset : LAYER_Y_OFFSETS) {
			BlockPos layerStart = column.offset(0, layerOffset, 0);
			for (int dy = 0; dy >= -RELAX_DEPTH; dy--) {
				BlockPos pos = layerStart.offset(0, dy, 0);
				if (isPassable(level, pos) && isPassable(level, pos.above()) && !isPassable(level, pos.below())) {
					return pos;
				}
			}
		}
		return null;
	}

	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
