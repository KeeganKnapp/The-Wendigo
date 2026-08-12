package com.wendigo.spatial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Shared dark-spot scanning: ring-samples/floods around an origin and checks for standable, passable
 * columns. Every position this class hands out is resolved live, on demand, against whatever
 * origin/player position is passed in right now - nothing here is pre-scanned or cached across ticks
 * (see findLiveBandPosition's own doc comment, the primitive that replaced the old "6 pre-scanned
 * labeled spots" system).
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
	// to below (a dip/pit) last. A single head-height-then-uncapped-relax scheme was tried instead of
	// this (see git history) but proved worse in noisy, multi-elevation caves - an uncapped downward
	// relax from one fixed starting point can walk straight past a real nearby floor and settle on a
	// disconnected pocket far below, whereas several shallow layers stay close to wherever they
	// actually started.
	private static final int[] LAYER_Y_OFFSETS = {0, 4, 8, -4};
	// How far straight down from each layer's starting height a column is allowed to relax onto the
	// actual floor - small and deliberately downward-only (gravity, not floating up to one), so
	// hill/valley coverage comes from having multiple layers rather than one big vertical window.
	private static final int RELAX_DEPTH = 4;
	// Hard cap on what counts as a "dark spot" at all - never return a candidate brighter than
	// this even if it's the darkest one sampled, so a bad neighborhood yields fewer/no spots
	// instead of a technically-darkest-but-still-lit one. Separate from the stricter
	// SemanticBands.DARKNESS_LIGHT_THRESHOLD (self_in_darkness) - this is "acceptable to spawn/
	// despawn/hide at", not "fully hidden". Public so PlanRunner can reuse it as the exact same bar
	// for "is it OK to actually finish despawning here" - see PlanRunner.readyToWithdraw.
	public static final int MAX_DARK_LIGHT = 4;

	/**
	 * Samples a ring of points around origin at the given radius and returns the darkest standable
	 * one found, or null if none of the sampled columns had a valid spot within MAX_DARK_LIGHT.
	 * Each column is resolved relative to origin's own Y via standableColumn's layered relax search
	 * (not a heightmap lookup), so this works underground, not just outdoors, and follows
	 * hills/valleys around origin rather than assuming a flat floor at its exact height.
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

	// The "ahead" destination type's own target band - the user's own explicit "1-2 lightlevel... more
	// likely to be close to a light, since the player is more likely to path through light" reasoning:
	// unlike every other search in this class (which wants the DARKEST candidate, capped at
	// MAX_DARK_LIGHT), this one deliberately wants a MILDLY lit spot instead, on the theory that a
	// player's own real route is more likely to run near their own placed torches than through
	// genuine total darkness - landing exactly there reads as lying in wait along their actual path,
	// not teleporting to a random dark pocket that happens to be nearby but off their route.
	private static final int PREDICTED_PATH_DIM_LIGHT_MIN = 1;
	private static final int PREDICTED_PATH_DIM_LIGHT_MAX = 2;
	// Same order of magnitude as LIVE_BAND_3D_SAMPLE_ATTEMPTS - see that field's own comment.
	private static final int DIM_SPOT_SAMPLE_ATTEMPTS = 80;

	/**
	 * The ahead destination type's own resolver: findLiveBandPosition3D's same spherical-shell
	 * sampling technique (genuine any-surface coverage - floor, wall, or ceiling near the predicted
	 * point, not just the floor; normal is caller-supplied per attempt, same convention as every other
	 * 3D sampler in this class) around origin (the player's own predicted position, not their current
	 * one - see PlanRunner's own extrapolation), out to maxRadius, requiring the DIM band
	 * [PREDICTED_PATH_DIM_LIGHT_MIN, PREDICTED_PATH_DIM_LIGHT_MAX] instead of the usual MAX_DARK_LIGHT
	 * ceiling every other search in this class wants - the user's own explicit "more likely to be
	 * close to a light, since the player is more likely to path through light" reasoning. First valid
	 * sample wins, same as findLiveBandPosition3D - not a best-of-many score search anymore, consistent
	 * with the rest of this class's own any-surface samplers. Returns null if nothing in the dim band
	 * turns up within DIM_SPOT_SAMPLE_ATTEMPTS tries for this one normal - callers retry with a
	 * different normal, same as every other any-surface sampler here, or fall back to an ordinary
	 * in_view search if the prediction doesn't pan out at all.
	 */
	public static BlockPos findDimSpotNear(Level level, BlockPos origin, double maxRadius, Direction normal) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int attempt = 0; attempt < DIM_SPOT_SAMPLE_ATTEMPTS; attempt++) {
			double z = random.nextDouble(-1.0, 1.0);
			double theta = random.nextDouble(0.0, 2.0 * Math.PI);
			double ringRadius = Math.sqrt(1.0 - z * z);
			double radius = random.nextDouble() * maxRadius;
			int dx = (int) Math.round(ringRadius * Math.cos(theta) * radius);
			int dy = (int) Math.round(z * radius);
			int dz = (int) Math.round(ringRadius * Math.sin(theta) * radius);
			BlockPos sample = origin.offset(dx, dy, dz);
			if (sample.getY() < level.getMinY() || sample.getY() > level.getMaxY()) {
				continue;
			}
			BlockPos candidate = attachableColumn(level, sample, normal);
			if (candidate == null) {
				continue;
			}
			if (candidate.distSqr(origin) > maxRadius * maxRadius) {
				continue;
			}
			int light = level.getMaxLocalRawBrightness(candidate);
			if (light >= PREDICTED_PATH_DIM_LIGHT_MIN && light <= PREDICTED_PATH_DIM_LIGHT_MAX) {
				return candidate;
			}
		}
		return null;
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

	// How far from the wendigo's own current position a live-band-position flood-fill is allowed to
	// range - the wendigo can legitimately already be well away from the player itself (mid-orbit, or
	// mid-plan after several steps), on top of however far the new position then needs to be from the
	// player too.
	private static final double MAX_LIVE_BAND_SEARCH_RADIUS = 32.0;
	private static final int MAX_LIVE_BAND_FLOOD_VISITED = 6000;

	/**
	 * Finds the nearest (by real path cost, not straight-line) standable/attachable column reachable
	 * from the WENDIGO's own current position (self) whose straight-line distance from player falls
	 * in [minDistanceFromPlayer, maxDistanceFromPlayer] - the one shared live-position resolver used
	 * everywhere a plan needs "get to roughly this distance from the player": orbit's own waypoint,
	 * fresh-spawn/engage positioning, and despawn/retreat. Deliberately resolved fresh every single call, against whatever position is passed in
	 * right now - never cached or reused from an earlier resolution, which is the whole point (a
	 * player who's moved since the last call gets a position relative to where they actually are now,
	 * not where they used to be).
	 * <p>
	 * Floods outward from self (not player, unlike findDarkest/findDarkestAwayFrom) and guarantees
	 * reachability from wherever the wendigo currently stands, filtered by a distance band measured
	 * from a DIFFERENT point (player) than the flood's own origin. normal selects floor (UP) or
	 * ceiling (DOWN) attachment, same convention as attachableColumn/isAttachable. Since only one
	 * position is needed (not a spread pool), this returns the very first in-band match the flood
	 * reaches, which - because the frontier is a real-distance-weighted priority queue - is guaranteed
	 * to be the cheapest-to-reach one, not just the first visited in some arbitrary order. Returns
	 * null if the flood exhausts its budget/radius without finding anything in-band.
	 */
	public static BlockPos findLiveBandPosition(Level level, BlockPos self, BlockPos player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer, Direction normal) {
		BlockPos start = attachableColumn(level, self, normal);
		if (start == null) {
			return null;
		}

		Queue<FloodNode> frontier = new PriorityQueue<>(Comparator.comparingDouble(FloodNode::cost));
		Set<Long> visited = new HashSet<>();
		frontier.add(new FloodNode(start, 0.0));
		visited.add(start.asLong());

		int visitedCount = 0;
		while (!frontier.isEmpty() && visitedCount < MAX_LIVE_BAND_FLOOD_VISITED) {
			FloodNode currentNode = frontier.poll();
			BlockPos current = currentNode.pos();
			visitedCount++;
			boolean isStart = current.equals(start);
			double distanceFromSelf = Math.sqrt(current.distSqr(self));

			if (!isStart) {
				double distanceFromPlayer = Math.sqrt(current.distSqr(player));
				if (distanceFromPlayer >= minDistanceFromPlayer && distanceFromPlayer <= maxDistanceFromPlayer) {
					int light = level.getMaxLocalRawBrightness(current);
					if (light <= MAX_DARK_LIGHT) {
						return current;
					}
				}
			}

			if (distanceFromSelf > MAX_LIVE_BAND_SEARCH_RADIUS) {
				continue; // don't expand past the search boundary
			}
			for (int i : shuffledFloodDirections()) {
				BlockPos neighbor = nearbyAttachable(level, current.getX() + FLOOD_STEP_DX[i], current.getZ() + FLOOD_STEP_DZ[i], current.getY(), normal);
				if (neighbor == null) {
					continue;
				}
				long key = neighbor.asLong();
				if (!visited.add(key)) {
					continue;
				}
				boolean diagonal = FLOOD_STEP_DX[i] != 0 && FLOOD_STEP_DZ[i] != 0;
				frontier.add(new FloodNode(neighbor, currentNode.cost() + (diagonal ? DIAGONAL_STEP_COST : 1.0)));
			}
		}
		return null;
	}

	// Same order of magnitude as findCeilingSpotAbovePlayer's own worst-case ring-sample count (5 radii
	// x 12 ring points = 60) - cheap relative to findLiveBandPosition's own MAX_LIVE_BAND_FLOOD_VISITED
	// budget. First pass, adjust by feel once live-tested, especially in a TIGHT-cave-scale band
	// (narrower shell, proportionally fewer samples land in it).
	private static final int LIVE_BAND_3D_SAMPLE_ATTEMPTS = 80;

	/**
	 * findLiveBandPosition's geometry-only sibling, for callers that need a genuinely fair shot at
	 * landing on ANY attachment surface (floor, ceiling, or a wall) rather than whichever one happens
	 * to be cheapest to flood-fill-reach from the entity's own current position - see PlanRunner.
	 * tickOrbit, the only caller. findLiveBandPosition's flood only ever searches for the SAME normal
	 * at every step (both its own start-column lookup and each expansion step), so a normal other than
	 * UP has to already be reachable via a same-normal-only path from wherever the entity currently
	 * is - in practice this means a ceiling/wall pick from a floor-standing entity routinely fails to
	 * even find a seed column, let alone flood out from one, systematically under-representing
	 * anything but the floor. This method sidesteps that entirely: no flood, no dependency on the
	 * entity's own current position at all - just genuine random points on a spherical shell between
	 * minDistanceFromPlayer and maxDistanceFromPlayer around player, each resolved into a real
	 * attachable position via attachableColumn (reused as-is; already normal-agnostic - only the UP
	 * case gets a different relax sign, every other normal, walls included, already shares one relax
	 * search). Deliberately NOT flood-verified reachable from the entity's own position, same
	 * looseness findCeilingVantagePoint already accepts - relies on the entity's own navigation plus
	 * stuck/trapped detection (PlanRunner.isRepeatedlyStuck, WendigoEntity.isOrbitTrapped) to recover
	 * from a genuinely-unreachable pick rather than guaranteeing reachability upfront. Returns the
	 * first sample that's both in-band and dark enough - not the "best" of many; sample order is
	 * already random, so first-valid is already an unbiased pick, which suits a wander/positioning
	 * caller (wants spread, not always the single darkest spot) better than accumulating a favorite
	 * would. Returns null if nothing qualifies within LIVE_BAND_3D_SAMPLE_ATTEMPTS tries.
	 */
	public static BlockPos findLiveBandPosition3D(Level level, BlockPos player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer, Direction normal) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		double radiusSpread = maxDistanceFromPlayer - minDistanceFromPlayer;
		for (int attempt = 0; attempt < LIVE_BAND_3D_SAMPLE_ATTEMPTS; attempt++) {
			// Archimedes/Marsaglia uniform-sphere-point sampling: z uniform in [-1,1] is the property
			// that has to land on Minecraft's actual vertical axis (Y) for this to fix ceiling/floor
			// under-representation - mapping it to a horizontal axis instead would spread samples
			// evenly around a horizontal ring while still clustering near straight-up/straight-down,
			// exactly the bias this method exists to remove.
			double z = random.nextDouble(-1.0, 1.0);
			double theta = random.nextDouble(0.0, 2.0 * Math.PI);
			double ringRadius = Math.sqrt(1.0 - z * z);
			double radius = minDistanceFromPlayer + random.nextDouble() * radiusSpread;
			int dx = (int) Math.round(ringRadius * Math.cos(theta) * radius);
			int dy = (int) Math.round(z * radius);
			int dz = (int) Math.round(ringRadius * Math.sin(theta) * radius);
			BlockPos sample = player.offset(dx, dy, dz);
			if (sample.getY() < level.getMinY() || sample.getY() > level.getMaxY()) {
				continue;
			}
			BlockPos candidate = attachableColumn(level, sample, normal);
			if (candidate == null) {
				continue;
			}
			// attachableColumn's own relax can walk the resolved candidate a few blocks off the raw
			// sampled point - re-check against the band here, unlike findLiveBandPosition's flood,
			// which already gets this check for free by filtering post-expansion.
			double distanceFromPlayer = Math.sqrt(candidate.distSqr(player));
			if (distanceFromPlayer < minDistanceFromPlayer || distanceFromPlayer > maxDistanceFromPlayer) {
				continue;
			}
			if (level.getMaxLocalRawBrightness(candidate) <= MAX_DARK_LIGHT) {
				return candidate;
			}
		}
		return null;
	}

	// Same order of magnitude as LIVE_BAND_3D_SAMPLE_ATTEMPTS - findLiveBandPositionInView applies an
	// extra isPlayerLookingToward filter on top of the same band+dark checks, so it needs the same
	// kind of sample budget, not a smaller one.
	private static final int IN_VIEW_POSITION_ATTEMPTS = 80;
	// Same corner_of_eye threshold (~60 degrees) isPlayerLookingToward's own 2-arg overload already
	// used before this class gained a second, tighter facing-check consumer - "broadly somewhere in
	// view" is good enough for the "in_view" destination type's own "let the player glimpse it" reveal.
	private static final double IN_VIEW_ALIGNMENT_DEGREES = 60.0;
	// The "eyeline" destination type's own, much tighter alignment - the user's own explicit "inline with
	// the target's eyeline" request: genuinely along their current gaze, not just broadly in their
	// peripheral field of view the way IN_VIEW_ALIGNMENT_DEGREES is. Matches
	// SemanticBands.lookAngleDegrees("dead_stare") (14 degrees) - same duplicated-constant tradeoff
	// this class's own MAX_DARK_LIGHT/DarknessMalus doc comment already accepts, since
	// com.wendigo.spatial can't depend on the package-private com.wendigo.plan.SemanticBands.
	private static final double EYELINE_ALIGNMENT_DEGREES = 14.0;

	/**
	 * findLiveBandPosition3D's sibling for the in_view destination type: same spherical-shell sampling
	 * (see that method's own doc comment for why - genuine 3D coverage of the band on any attachment
	 * surface, not a same-normal flood; normal is caller-supplied per attempt, same convention
	 * findLiveBandPosition3D itself uses - vary it across retries for real floor/wall/ceiling
	 * coverage), but keeps only a candidate the player IS currently facing toward (broadly - see
	 * IN_VIEW_ALIGNMENT_DEGREES) instead of filtering it out - the exact opposite of
	 * findUnwatchedPosition3D's own filter, since this backs a deliberate "let the player glimpse it"
	 * reveal rather than a blind-spot ambush. Returns null if nothing in-band, dark, AND in-view turns
	 * up within IN_VIEW_POSITION_ATTEMPTS tries for this one normal - callers retry with a different
	 * normal (and, for a tight cave, real-pathfind verification) rather than this method searching
	 * every surface itself.
	 */
	public static BlockPos findLiveBandPositionInView(Level level, Player player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer, Direction normal) {
		return findLiveBandPositionFacing(level, player, minDistanceFromPlayer, maxDistanceFromPlayer,
			IN_VIEW_ALIGNMENT_DEGREES, normal);
	}

	/** The eyeline destination type's own resolver - identical shape to findLiveBandPositionInView
	 * right above (same sampling, same dark requirement, same caller-supplied normal), just gated on
	 * the much tighter EYELINE_ALIGNMENT_DEGREES instead - a dark spot genuinely along the player's
	 * current line of sight, not merely somewhere broadly in view. */
	public static BlockPos findLiveBandPositionEyeline(Level level, Player player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer, Direction normal) {
		return findLiveBandPositionFacing(level, player, minDistanceFromPlayer, maxDistanceFromPlayer,
			EYELINE_ALIGNMENT_DEGREES, normal);
	}

	private static BlockPos findLiveBandPositionFacing(Level level, Player player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer, double alignmentDegrees, Direction normal) {
		BlockPos playerPos = player.blockPosition();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		double radiusSpread = maxDistanceFromPlayer - minDistanceFromPlayer;
		for (int attempt = 0; attempt < IN_VIEW_POSITION_ATTEMPTS; attempt++) {
			double z = random.nextDouble(-1.0, 1.0);
			double theta = random.nextDouble(0.0, 2.0 * Math.PI);
			double ringRadius = Math.sqrt(1.0 - z * z);
			double radius = minDistanceFromPlayer + random.nextDouble() * radiusSpread;
			int dx = (int) Math.round(ringRadius * Math.cos(theta) * radius);
			int dy = (int) Math.round(z * radius);
			int dz = (int) Math.round(ringRadius * Math.sin(theta) * radius);
			BlockPos sample = playerPos.offset(dx, dy, dz);
			if (sample.getY() < level.getMinY() || sample.getY() > level.getMaxY()) {
				continue;
			}
			BlockPos candidate = attachableColumn(level, sample, normal);
			if (candidate == null) {
				continue;
			}
			double distanceFromPlayer = Math.sqrt(candidate.distSqr(playerPos));
			if (distanceFromPlayer < minDistanceFromPlayer || distanceFromPlayer > maxDistanceFromPlayer) {
				continue;
			}
			// Exactly 0, not just <= MAX_DARK_LIGHT like every other dark-spot search in this class -
			// the user's own explicit "any teleport action should only ever land on a 0-light block"
			// request. Safe to tighten right here rather than only post-filtering the result in
			// PlanRunner - this method has exactly two callers, findLiveBandPositionInView/
			// findLiveBandPositionEyeline, both exclusively backing the teleport/approach in_view/
			// eyeline destination types and nothing else, so nothing else shares this search.
			if (level.getMaxLocalRawBrightness(candidate) > 0) {
				continue;
			}
			if (isPlayerLookingToward(player, candidate, alignmentDegrees)) {
				return candidate;
			}
		}
		return null;
	}

	// The "above" destination type's own vertical window: how far straight up from the player
	// the ceiling perch must be - "no closer than 10 blocks up... within 30 blocks", the user's own
	// explicit words, read as a vertical constraint (a horizontal search only ever widens the search
	// for a valid ceiling point, it doesn't relax how far above the player that point sits).
	private static final double CEILING_ABOVE_MIN_HEIGHT = 10.0;
	private static final double CEILING_ABOVE_MAX_HEIGHT = 30.0;
	// How far horizontally off dead-center-above-the-player this is willing to search once the exact
	// directly-above column turns out lit (or has no ceiling at all within the height window) - the
	// user's own "~20 block search range" figure, distinct from the 30-block vertical cap above.
	private static final double CEILING_ABOVE_SEARCH_RADIUS = 20.0;
	private static final double CEILING_ABOVE_RADIUS_STEP = 4.0;

	/**
	 * The above destination type's own resolver (combat.teleport and movement.approach_spot alike):
	 * prefers landing directly above the player (dead-center X/Z), the lowest valid ceiling point
	 * that's still at least CEILING_ABOVE_MIN_HEIGHT up - "as close as possible" while honoring the
	 * no-closer-than-10 floor. If that exact column is lit, or has no ceiling within [MIN,MAX] at all,
	 * widens into an expanding-ring horizontal search (same shuffled-ring technique findDarkestBiased
	 * uses) out to CEILING_ABOVE_SEARCH_RADIUS, each ring point re-checked against the same vertical
	 * window. Returns null if nothing qualifies anywhere in that search - this destination type is
	 * always face-specific (ceiling only), no any-surface fallback the way behind/in_view/eyeline/
	 * ahead have.
	 */
	public static BlockPos findCeilingSpotAbovePlayer(Level level, BlockPos player) {
		BlockPos direct = findVerticalAttachableInRange(level, player, Direction.DOWN, CEILING_ABOVE_MIN_HEIGHT, CEILING_ABOVE_MAX_HEIGHT);
		if (direct != null && level.getMaxLocalRawBrightness(direct) <= MAX_DARK_LIGHT) {
			return direct;
		}
		for (double radius = CEILING_ABOVE_RADIUS_STEP; radius <= CEILING_ABOVE_SEARCH_RADIUS; radius += CEILING_ABOVE_RADIUS_STEP) {
			for (int i : shuffledRingIndices()) {
				double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos candidate = findVerticalAttachableInRange(level, player.offset(dx, 0, dz), Direction.DOWN,
					CEILING_ABOVE_MIN_HEIGHT, CEILING_ABOVE_MAX_HEIGHT);
				if (candidate == null) {
					continue;
				}
				if (level.getMaxLocalRawBrightness(candidate) <= MAX_DARK_LIGHT) {
					return candidate;
				}
			}
		}
		return null;
	}

	/** findVerticalAttachablePoint's bounded sibling: same straight-line probe, but only within
	 * [minDistance, maxDistance] from origin rather than from 1 all the way out to maxDistance -
	 * findCeilingSpotAbovePlayer's own "no closer than 10 blocks up" requirement needs the lower
	 * bound findVerticalAttachablePoint doesn't have. Only meaningful for normal=DOWN (the only
	 * direction findCeilingSpotAbovePlayer ever calls this with) - unlike findVerticalAttachablePoint,
	 * doesn't bother supporting UP, since nothing in this codebase needs a floor-with-a-minimum-depth
	 * search yet. */
	private static BlockPos findVerticalAttachableInRange(Level level, BlockPos origin, Direction normal,
			double minDistance, double maxDistance) {
		int minY = (int) Math.min(origin.getY() + minDistance, level.getMaxY());
		int maxY = (int) Math.min(origin.getY() + maxDistance, level.getMaxY());
		for (int y = minY; y <= maxY; y++) {
			BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
			if (isAttachable(level, candidate, normal)) {
				return candidate;
			}
		}
		return null;
	}

	// Retries findLiveBandPosition up to this many times looking for one the player isn't currently
	// looking toward - shuffledFloodDirections' own per-call randomization (see findLiveBandPosition's
	// own doc comment) means repeated calls with the same inputs aren't guaranteed to return the same
	// column, so retrying is a real search, not spinning on a deterministic result.
	private static final int UNWATCHED_POSITION_ATTEMPTS = 5;

	/**
	 * findLiveBandPosition, filtered for a position the player isn't currently facing toward - backs
	 * WendigoManager.resolveUnwatchedSpot, the fresh-spawn positioning path (self seeded from the
	 * player's own position since there's no existing entity position yet) - floor-only,
	 * flood-verified-reachable-from-self, unlike the any-surface findUnwatchedPosition3D
	 * movement.approach_spot/combat.teleport's own "unwatched"/"behind" destination types use for
	 * mid-plan repositioning now. Falls back to whichever candidate was actually found (even if
	 * still watched) if none of the attempts come up unwatched, rather than returning null and
	 * leaving the caller with nothing at all - same "some darkness beats none" philosophy every
	 * other fallback in this class already follows.
	 */
	public static BlockPos findUnwatchedPosition(Level level, BlockPos self, Player player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer, Direction normal) {
		BlockPos best = null;
		for (int attempt = 0; attempt < UNWATCHED_POSITION_ATTEMPTS; attempt++) {
			BlockPos candidate = findLiveBandPosition(level, self, player.blockPosition(), minDistanceFromPlayer, maxDistanceFromPlayer, normal);
			if (candidate == null) {
				continue;
			}
			best = candidate;
			if (!isPlayerLookingToward(player, candidate)) {
				return candidate;
			}
		}
		return best;
	}

	// Any-surface normal set for the behind/unwatched destination types - same 6 directions
	// PlanRunner.randomOrbitSurfaceNormal draws from, duplicated here rather than widened-for-reuse
	// since com.wendigo.spatial can't depend on com.wendigo.plan (see this class's own MAX_DARK_LIGHT/
	// DarknessMalus-vs-SemanticBands comment for the same tradeoff already accepted elsewhere).
	private static final Direction[] ANY_SURFACE_NORMALS =
		{Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	// Same order of magnitude as UNWATCHED_POSITION_ATTEMPTS - this is a quality retry (looking for an
	// unwatched result specifically), not a from-scratch search budget, so it doesn't need anywhere
	// near LIVE_BAND_3D_SAMPLE_ATTEMPTS's own per-normal sample count.
	private static final int UNWATCHED_3D_ATTEMPTS = 8;

	/**
	 * The behind/unwatched destination types' own resolver: findLiveBandPosition3D's same
	 * spherical-shell sampling (genuine any-surface coverage - "behind the player" could just as
	 * easily be on the ceiling behind them as on the floor), retried up to UNWATCHED_3D_ATTEMPTS times
	 * with a freshly-randomized surface normal each attempt (same technique
	 * PlanRunner.randomOrbitSurfaceNormal already establishes for orbit positioning) looking
	 * specifically for a candidate the player ISN'T currently facing toward - falls back to whichever
	 * candidate was actually found (even if still watched) if none of the attempts come up unwatched,
	 * same "some darkness beats none" philosophy findUnwatchedPosition's own flood-based sibling
	 * already follows. Deliberately NOT flood-verified reachable from the wendigo's own current
	 * position, same looseness findCeilingVantagePoint already accepts: this backs both an instant
	 * teleport (reachability from self is irrelevant) and a walked approach (verified separately, by
	 * the caller, via a real pathfind - see PlanRunner's own movement.approach_spot dispatch). Returns
	 * null only if every attempt's own sample budget comes up completely empty (nothing dark/attachable
	 * anywhere sampled, not even a watched one).
	 */
	public static BlockPos findUnwatchedPosition3D(Level level, Player player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer) {
		BlockPos playerPos = player.blockPosition();
		BlockPos best = null;
		for (int attempt = 0; attempt < UNWATCHED_3D_ATTEMPTS; attempt++) {
			Direction normal = ANY_SURFACE_NORMALS[ThreadLocalRandom.current().nextInt(ANY_SURFACE_NORMALS.length)];
			BlockPos candidate = findLiveBandPosition3D(level, playerPos, minDistanceFromPlayer, maxDistanceFromPlayer, normal);
			if (candidate == null) {
				continue;
			}
			best = candidate;
			if (!isPlayerLookingToward(player, candidate)) {
				return candidate;
			}
		}
		return best;
	}

	/** Angle-only approximation (no line-of-sight/occlusion check, unlike the live in-game stare
	 * predicate) of whether the player is currently facing toward a candidate position - good enough
	 * for "don't send it somewhere already in view", not meant to be as precise as
	 * predicate.player_looking_at_self. Same corner_of_eye threshold (~60 degrees) as that predicate. */
	private static boolean isPlayerLookingToward(Player player, BlockPos pos) {
		return isPlayerLookingToward(player, pos, IN_VIEW_ALIGNMENT_DEGREES);
	}

	private static boolean isPlayerLookingToward(Player player, BlockPos pos, double alignmentDegrees) {
		Vec3 toPos = Vec3.atCenterOf(pos).subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toPos);
		return alignment >= Math.cos(Math.toRadians(alignmentDegrees));
	}

	// Cap on how far above the player orbit's own ceiling-vantage preference (see
	// PlanRunner.tickOrbit) will look for a ceiling to perch on - a straight-line height limit, not
	// tied to MAX_LIVE_BAND_SEARCH_RADIUS (that governs the flood-fill's real path-cost budget for
	// the ordinary band-position search this is an alternative to).
	private static final double MAX_CEILING_VANTAGE_HEIGHT = 30.0;

	/** Straight vertical probe directly above player, looking for the first ceiling-attachable
	 * position within MAX_CEILING_VANTAGE_HEIGHT blocks - unlike attachableColumn's own layered relax
	 * search (built for "the ceiling is roughly near this same Y already", a handful of fixed
	 * offsets), this checks every block in between, since the whole point here is finding out how far
	 * up the actual ceiling is, not just confirming one exists somewhere nearby. Not flood-verified
	 * reachable from the wendigo's own position (same looseness findDarkestAwayFrom already accepts
	 * for orbit's own fallback) - orbit's stuck/trapped detection already handles a genuinely
	 * unreachable pick. Returns null if the column is open (no solid ceiling) within range. */
	public static BlockPos findCeilingVantagePoint(Level level, BlockPos player) {
		return findVerticalAttachablePoint(level, player, Direction.DOWN, MAX_CEILING_VANTAGE_HEIGHT);
	}

	/** Generalizes findCeilingVantagePoint to the floor case too (normal=UP: straight probe downward
	 * for real ground; normal=DOWN: the original ceiling case, unchanged). Public specifically for
	 * PlanRunner.resolveChaseDestination: attachableColumn's own layered-relax search (a handful of
	 * fixed offset windows, gaps between them) can miss a real surface that's simply further from
	 * origin than any single window reaches - live-confirmed as still letting a chase destination
	 * resolve to open air when the target player was flying far enough below a real ceiling that none
	 * of attachableColumn's own {@code LAYER_Y_OFFSETS} windows happened to reach it. This checks
	 * every block along the way instead, same as findCeilingVantagePoint already did for its one
	 * fixed direction. Only meaningful for UP/DOWN - returns null for a horizontal normal (walls
	 * don't have a single vertical column to walk along the same way). */
	public static BlockPos findVerticalAttachablePoint(Level level, BlockPos origin, Direction normal, double maxDistance) {
		if (normal == Direction.DOWN) {
			int maxY = (int) Math.min(origin.getY() + maxDistance, level.getMaxY());
			for (int y = origin.getY() + 1; y <= maxY; y++) {
				BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
				if (isAttachable(level, candidate, normal)) {
					return candidate;
				}
			}
			return null;
		}
		if (normal == Direction.UP) {
			int minY = (int) Math.max(origin.getY() - maxDistance, level.getMinY());
			for (int y = origin.getY() - 1; y >= minY; y--) {
				BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
				if (isAttachable(level, candidate, normal)) {
					return candidate;
				}
			}
			return null;
		}
		return null;
	}

	// Over-fetches from LightSourceScanner.findLightSources (nearest-first) since torches closer than
	// a band's own minDistance would otherwise consume its count cap before anything genuinely in-band
	// is ever reached - generous enough for realistic cave torch density without being unbounded.
	private static final int BAND_TORCH_FETCH_COUNT = 40;

	/** Torches whose live distance from player falls in [minDistance, maxDistance], nearest-in-band
	 * first - backs both the prompt's per-band torch counts (WaveContext) and combat.break_torch's
	 * optional band-constrained target lookup (PlanRunner), both reading this fresh at the moment
	 * they need it rather than a value cached from earlier. */
	public static List<BlockPos> findTorchesInBand(Level level, BlockPos player, double minDistance, double maxDistance) {
		List<BlockPos> nearby = LightSourceScanner.findLightSources(level, player, maxDistance, BAND_TORCH_FETCH_COUNT);
		List<BlockPos> inBand = new ArrayList<>();
		for (BlockPos candidate : nearby) {
			double distance = Math.sqrt(candidate.distSqr(player));
			if (distance >= minDistance && distance <= maxDistance) {
				inBand.add(candidate);
			}
		}
		return inBand;
	}

	/** A flood-fill frontier entry - cost is the accumulated real-distance-weighted path length from
	 * the flood's own start (see DIAGONAL_STEP_COST), not a hop count, so the frontier's priority-queue
	 * ordering expands the genuinely closest unvisited column next regardless of how many hops it took
	 * to reach it. */
	private record FloodNode(BlockPos pos, double cost) {
	}

	// 8-directional horizontal step between flood nodes - deliberately not the exact same move set
	// vanilla pathfinding models (diagonal-cutting rules, jump height, cobweb/fence malus etc.); this
	// only needs to be AT LEAST as permissive as real movement, never stricter, since a spot slightly
	// harder to actually reach already has a runtime fallback (stuck/timeout detection, a fresh
	// re-resolution next attempt) - the failure mode this search exists to kill is
	// "engine-verified-reachable" and "the wendigo can actually get there" silently drifting apart,
	// which a real flood-fill can't have (every column visited was reached by walking there, one
	// connected step at a time - reachability is true by construction, not verified after the fact).
	private static final int[] FLOOD_STEP_DX = {1, -1, 0, 0, 1, 1, -1, -1};
	private static final int[] FLOOD_STEP_DZ = {0, 0, 1, -1, 1, -1, 1, -1};
	// A diagonal step covers sqrt(2) as much real ground as a cardinal one - weighting each step by
	// its real distance and expanding cheapest-first (see the frontier above) makes "distance
	// travelled" in the search match real distance, so cardinal and diagonal directions compete fairly
	// instead of diagonal always winning ties under a naive uniform-cost scheme.
	private static final double DIAGONAL_STEP_COST = Math.sqrt(2.0);
	// How far above/below a column's own floor a horizontally-adjacent column's floor may sit and
	// still count as one flood step - generous downward (a mob can always fall/drop through open
	// space), modest upward (roughly a single block's step-up, matching ordinary ground-mob capability).
	private static final int FLOOD_MAX_STEP_UP = 1;
	private static final int FLOOD_MAX_STEP_DOWN = 4;

	/** Finds the floor (normal UP) or ceiling (normal DOWN) attachment point at (x,z) nearest to
	 * referenceY - up to FLOOD_MAX_STEP_UP above it, down to FLOOD_MAX_STEP_DOWN below - for
	 * flood-fill neighbor expansion (a small window relative to the CURRENT node, unlike
	 * attachableColumn's several-fixed-layers scheme meant for locating an unknown column from far
	 * away). The window itself isn't mirrored for ceiling search (same absolute up/down reach either
	 * way, not "generous toward the ceiling instead of the floor") - unlike attachableColumn's own
	 * relax direction (which has to invert to find a ceiling at all), this window is just a
	 * reachability-heuristic tuning knob with no strong physical reasoning either way; first pass,
	 * adjust by feel once ceiling spots are actually visible in a live client. */
	private static BlockPos nearbyAttachable(Level level, int x, int z, int referenceY, Direction normal) {
		for (int dy = FLOOD_MAX_STEP_UP; dy >= -FLOOD_MAX_STEP_DOWN; dy--) {
			BlockPos pos = new BlockPos(x, referenceY + dy, z);
			if (isAttachable(level, pos, normal)) {
				return pos;
			}
		}
		return null;
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

	/** Shuffled 0..FLOOD_STEP_DX.length-1 traversal order - same fix as shuffledRingIndices, applied
	 * to the flood-fill's own neighbor order. Visiting FLOOD_STEP_DX/DZ in the same fixed sequence at
	 * every cell made an open, uniformly dark room flood out as a dead-straight diagonal line (always
	 * the same direction) instead of spreading across equally-valid directions - a symmetric search
	 * space with a fixed tie-break order produces a fully deterministic, asymmetric result. Called
	 * once per dequeued cell (not once per whole search) so the bias doesn't just shift from "always
	 * this direction" to "always this direction, chosen once at the start". */
	private static List<Integer> shuffledFloodDirections() {
		List<Integer> order = new ArrayList<>(FLOOD_STEP_DX.length);
		for (int i = 0; i < FLOOD_STEP_DX.length; i++) {
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
	 * the nearest actual floor - the first standable position found this way wins. Only requires the
	 * one block itself to be passable (plus solid ground below) - doesn't also require the block
	 * above to be passable, so a single-block-tall crevice qualifies too, not just a full 2-block
	 * standing space. WendigoEntity's own dynamic pose switch shrinks it into its crawl hitbox
	 * wherever standing clearance isn't actually there (including right on spawn), and
	 * DarknessAwareClimberNodeEvaluator lets pathfinding route through a gap this tight in the first
	 * place - this scan just needs to agree that the spot exists at all. Returns null if every
	 * layer's relax search comes up solid/empty-handed (e.g. the whole column is inside rock).
	 */
	private static BlockPos standableColumn(Level level, BlockPos column) {
		return attachableColumn(level, column, Direction.UP);
	}

	/**
	 * Generalizes standableColumn to an arbitrary attachment surface - normal UP is exactly
	 * standableColumn's own floor search (relaxes downward from each layer, looking for solid
	 * ground below an open block); normal DOWN mirrors it for a ceiling (relaxes upward instead,
	 * looking for solid rock above an open block). The relax direction has to invert for correctness
	 * (searching downward can never find a ceiling), unlike nearbyAttachable's own window, which
	 * stays unmirrored on purpose (see that method's comment).
	 *
	 * <p>Public (not just this file's own findLiveBandPosition3D/tryEnterOrbit-style callers) so
	 * PlanRunner's own combat.chase/internal.chase_until_light can validate a chase destination
	 * before ever pathing there - see PlanRunner.resolveChaseDestination's own doc comment for why:
	 * a player floating in open air near (but not touching) a real surface - flying in creative right
	 * below a ceiling, live-confirmed as the actual trigger for the recurring ceiling-flip bug - isn't
	 * itself attachable, but is usually only a few blocks away from a real position that is.
	 */
	public static BlockPos attachableColumn(Level level, BlockPos column, Direction normal) {
		int relaxSign = normal == Direction.UP ? -1 : 1;
		for (int layerOffset : LAYER_Y_OFFSETS) {
			BlockPos layerStart = column.offset(0, layerOffset, 0);
			for (int step = 0; step <= RELAX_DEPTH; step++) {
				BlockPos pos = layerStart.offset(0, step * relaxSign, 0);
				if (isAttachable(level, pos, normal)) {
					return pos;
				}
			}
		}
		return null;
	}

	/** True if a full standing-height (3-block, matching WendigoEntity's STANDING_DIMENSIONS) column
	 * is clear at pos - the block itself passable and the two blocks above it too, regardless of what's
	 * below. Public so WendigoEntity can check whether its current position actually has room to stand,
	 * and force its crawl pose/hitbox when it doesn't - see the entity's own tick(). */
	public static boolean hasStandingClearance(Level level, BlockPos pos) {
		return isPassable(level, pos) && isPassable(level, pos.above()) && isPassable(level, pos.above(2));
	}

	/** True if pos itself is open and the block behind it (relative to normal - the direction the
	 * attached mob faces AWAY from the surface) is solid: normal UP is an ordinary floor check
	 * (solid ground below, matching the old isPassable(pos) && !isPassable(pos.below())), normal
	 * DOWN is its ceiling mirror (solid rock above). */
	private static boolean isAttachable(Level level, BlockPos pos, Direction normal) {
		return isPassable(level, pos) && !isPassable(level, pos.relative(normal.getOpposite()));
	}

	/** Public so PlanRunner's own combat.teleport handler can re-verify a resolved destination's exact
	 * final coordinates are genuinely open right before an instant snapTo - see that call site's own
	 * doc comment for the real bug this guards against: a radial destination's own candidate BlockPos
	 * (already isAttachable/isPassable-verified here) gets pathfind-wrapped (createPath(candidate, 1))
	 * before combat.teleport ever sees it, and Path.getTarget()/canReach() only guarantee the path's
	 * own last node ended up WITHIN that 1-block tolerance of the original candidate, not exactly at
	 * it - unlike movement.approach_spot (a real walked arrival, safely collision-mediated regardless
	 * of any such drift), an instant snap has no physics step to catch a final position that drifted
	 * onto solid ground. Checking the real collision shape (not just isAir) matches what the entity's
	 * own physics would actually collide with. */
	public static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
