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
	// Minimum separation between two accepted wave spots so they don't cluster on the same ledge -
	// 6 blocks (stored squared since every comparison site is against distSqr, not distance).
	private static final double MIN_SPOT_SEPARATION_SQR = 36.0;
	// Hard cap on what counts as a "dark spot" at all - never return a candidate brighter than
	// this even if it's the darkest one sampled, so a bad neighborhood yields fewer/no spots
	// instead of a technically-darkest-but-still-lit one. Separate from the stricter
	// SemanticBands.DARKNESS_LIGHT_THRESHOLD (self_in_darkness) - this is "acceptable to spawn/
	// despawn/hide at", not "fully hidden". Public so PlanRunner can reuse it as the exact same bar
	// for "is it OK to actually finish despawning here" - see PlanRunner.readyToVanish.
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

	// How close a too-lit candidate needs to be to a torch to count as "linkable" - not a simulated
	// lighting removal (too expensive for a heuristic like this whole class), just "close enough that
	// breaking it plausibly resolves this spot", same tradeoff as everywhere else here.
	private static final double TORCH_LINK_RADIUS = 6.0;

	/** A scanned wave spot - either a genuinely dark standable position (linkedTorch null), or one
	 * that's too lit to qualify on its own but sits near a breakable torch - spawning there and
	 * immediately destroying that one torch is assumed to resolve the lighting. The AI never sees
	 * this distinction for spawn_at's regular spot_a..spot_f labels (spawn_at just offers the label
	 * either way) - only the engine (WendigoManager) reads linkedTorch, to know whether to break a
	 * torch on arrival. Also reused for spawn_on_torch candidates, where linkedTorch is always
	 * non-null by definition - distanceFromOrigin there is what SchemaBuilder.minTorchSpawnDistance
	 * filters against, mirroring spot_b..spot_f's own progressive distance unlock but for this
	 * separate candidate pool instead of a labeled spot.
	 *
	 * <p>surfaceNormal is UP for every ordinary floor spot, DOWN for a ceiling spot (see
	 * findCeilingSpots), and EAST/WEST/NORTH/SOUTH for a wall spot (see findWallSpots) - the
	 * direction away from whatever solid surface the position is attached to. */
	public record WaveSpot(BlockPos position, BlockPos linkedTorch, double distanceFromOrigin, Direction surfaceNormal) {
		public boolean isTorchLinked() {
			return linkedTorch != null;
		}
	}

	/** findWaveSpots' result: the labeled spot_a..spot_f candidates, and separately, torch-adjacent
	 * positions found along the way that are worth offering as spawn_on_torch fallback candidates
	 * (see WendigoManager) - kept apart from spots() since they're a different kind of choice (a
	 * deliberately-exposed emergency option, not a normal hidden spawn point), even though they came
	 * from the exact same flood pass. */
	public record WaveSpotScan(List<WaveSpot> spots, List<WaveSpot> torchSpawnCandidates) {
	}

	// Wave-spot search never widens past this radius from the player - a flood-fill through a truly
	// massive cavern could otherwise wander arbitrarily far chasing the next distance threshold.
	private static final double MAX_SEARCH_RADIUS = 48.0;
	// spot_a itself - the very first, closest spot - is never allowed closer than this to the player,
	// even if genuinely dark ground exists nearer than that. Spawning right on top of the player
	// defeats the point of the whole scan regardless of how dark it is there.
	private static final double MIN_FIRST_SPOT_DISTANCE = 6.0;
	// Each accepted wave spot must be at least this much farther from the player (straight-line, not
	// path-length) than the previous one, cumulative from MIN_FIRST_SPOT_DISTANCE (8, 16, 24, 32, 40,
	// 48) - same "proper distance spread" as before, just now satisfied by a real connected search
	// instead of independently re-sampling rings at each radius.
	private static final double SPOT_DISTANCE_STEP = MAX_SEARCH_RADIUS / 6.0;

	/** The cumulative distance threshold a labeled spot slot unlocks at - spot_a=0 through spot_f=5,
	 * matching SPOT_LABELS' own ordering (see WaveContext). Public so severity gating that isn't about
	 * a labeled spot at all (SchemaBuilder.minTorchSpawnDistance, for the separate spawn_on_torch
	 * candidate pool) can reuse the exact same distance tiers spot_b..spot_f already unlock at, instead
	 * of a second hardcoded copy of 16/24/32/40 that could silently drift from these. */
	public static double spotDistanceThreshold(int spotIndex) {
		return MIN_FIRST_SPOT_DISTANCE + spotIndex * SPOT_DISTANCE_STEP;
	}
	// Hard ceiling on how many standable columns one flood-fill will ever visit - bounds worst-case
	// cost in a huge, fully open cavern regardless of MAX_SEARCH_RADIUS (a big radius still contains
	// vastly more open volume in a massive cave than a tight one). Generous for a search that only
	// runs once per wave start.
	private static final int MAX_FLOOD_VISITED = 4000;
	// How many of the (possibly many) flood-visited nodes get the real, comparatively expensive
	// LightSourceScanner.findLightSources treatment - torch-linked-spot fallback and
	// spawn_on_torch-candidate collection both draw from this same bounded set of checks rather than
	// probing every single visited node.
	private static final int MAX_TORCH_CHECKS = 60;
	private static final int MAX_TORCH_SPAWN_CANDIDATES = 10;
	// 8-directional horizontal step between flood nodes - deliberately not the exact same move set
	// vanilla pathfinding models (diagonal-cutting rules, jump height, cobweb/fence malus etc.); this
	// only needs to be AT LEAST as permissive as real movement, never stricter, since the failure mode
	// of "found a spot slightly harder to actually reach" already has a runtime fallback chain
	// (PlanRunner's despawn/retreat retry logic, stuck/timeout detection), while the failure mode this
	// search exists to kill - "engine-verified-reachable" and "the wendigo can actually get there"
	// silently drifting apart, one via ring-sampling+probing, the other via real navigation - has no
	// such safety net; the two now use the exact same graph by construction, not two independent
	// approximations of it.
	private static final int[] FLOOD_STEP_DX = {1, -1, 0, 0, 1, 1, -1, -1};
	private static final int[] FLOOD_STEP_DZ = {0, 0, 1, -1, 1, -1, 1, -1};
	// A diagonal step covers sqrt(2) as much real ground as a cardinal one, but both were being
	// treated as "one hop" of equal weight - in an open room that made the search reach any given
	// Euclidean distance threshold fastest (fewest hops) by going perfectly diagonally, every time,
	// regardless of tie-breaking order (shuffling which cell wins a tie doesn't help when diagonal
	// travel isn't actually tied with cardinal travel to begin with - it's structurally cheaper).
	// Weighting each step by its real distance and expanding cheapest-first (see the frontier below)
	// makes "distance travelled" in the search match real distance instead, so cardinal and diagonal
	// directions compete fairly.
	private static final double DIAGONAL_STEP_COST = Math.sqrt(2.0);
	// Whole-number thresholds (8, 16, 24...) are only reachable exactly by cardinal steps (cost 1.0
	// each) - the nearest diagonal step can only overshoot one (6 steps = 8.49 cost to clear a
	// threshold of 8), and any other angle overshoots by even more (octile distance only equals real
	// distance exactly at 0/45/90-degree multiples). Cost-ordering alone would therefore always accept
	// the cardinal candidate first, every time, for the same reason plain BFS always accepted the
	// diagonal one - a different "always exactly one direction wins" bias, not a random tie. Instead
	// of accepting the very first cell whose distance clears a threshold, candidates that clear it
	// within this much extra cost of the first one are pooled and one is picked at random from the
	// pool. Checked against a standalone simulation (open room, thousands of trials, angle of the
	// chosen spot) rather than just guessed at a value: 1.5 still let the exact diagonal angles (45/
	// 135/225/315 - reachable by many different step sequences that all land on precisely that angle,
	// unlike most other angles) come up noticeably more than a uniform spread would predict; 3.0 came
	// out close to flat across all directions without pooling candidates so late that "roughly the
	// same time" stops being true.
	private static final double ACCEPT_WINDOW_COST = 3.0;
	// How far above/below a column's own floor a horizontally-adjacent column's floor may sit and
	// still count as one flood step - generous downward (a mob can always fall/drop through open
	// space), modest upward (roughly a single block's step-up, matching ordinary ground-mob capability).
	private static final int FLOOD_MAX_STEP_UP = 1;
	private static final int FLOOD_MAX_STEP_DOWN = 4;

	/**
	 * Flood-fills outward from origin (the player) across actually-connected standable columns -
	 * replaces an earlier design that independently ring-sampled at each radius band and only
	 * verified reachability afterward (via a throwaway pathfinding probe), which repeatedly proved
	 * unreliable in practice (crevices, rails, fence-post gaps, search-budget limits - each its own
	 * source of "engine says unreachable, live wendigo proves otherwise" drift). A flood-fill can't
	 * have that problem: every column it ever visits was reached by walking there, one connected step
	 * at a time, from the player's own position - reachability isn't verified after the fact, it's
	 * true by construction.
	 * <p>
	 * spot_a is (randomly, among whichever cleared it at roughly the same accumulated cost - see
	 * ACCEPT_WINDOW_COST) one of the standable columns visited whose straight-line distance from origin
	 * is at least MIN_FIRST_SPOT_DISTANCE and whose light is dark enough (or torch-linked); each spot
	 * after that needs SPOT_DISTANCE_STEP more distance than the last accepted one (cumulative: 8, 16,
	 * 24, 32, 40, 48). A cave with several branches naturally spreads spots across whichever branches
	 * reach each threshold first (a fork in the search just means the frontier has entries from
	 * multiple branches active at once - no special-casing needed). The frontier is cost-ordered (see
	 * FloodNode/DIAGONAL_STEP_COST), not plain FIFO - a uniform-cost breadth-first order made "first
	 * past the threshold" always mean "reached diagonally", since a diagonal hop covers more real
	 * distance than a cardinal one for the same "one step" cost. That alone still wasn't enough: whole-
	 * number thresholds are only reachable exactly by cardinal steps, so pure cost-ordering then always
	 * favored cardinal directions instead (see ACCEPT_WINDOW_COST) - two different "always exactly one
	 * direction wins" biases from two different causes, not one bug with one fix. Separately
	 * collects up to MAX_TORCH_SPAWN_CANDIDATES torch-adjacent positions encountered along the way,
	 * for spawn_on_torch (see WendigoManager) - reachable by the same construction, no separate check
	 * needed there either. Stops once {@code count} spots are found, the flood exhausts its budget
	 * (MAX_FLOOD_VISITED) or its radius (MAX_SEARCH_RADIUS), or there's simply nowhere left to expand.
	 */
	public static WaveSpotScan findWaveSpots(Level level, BlockPos origin, int count) {
		return findAttachableSpots(level, origin, count, Direction.UP);
	}

	/**
	 * Same flood-fill as findWaveSpots, searching for ceiling attachment points (normal DOWN -
	 * solid rock above, open space below) instead of floor ones - lets the wendigo spawn/hide on a
	 * dark patch of cave ceiling via AWCAPI's climbing (see WendigoEntity), staring down at the
	 * player from above instead of always being at floor level.
	 * <p>
	 * Not extended to wall spots (normal EAST/WEST/NORTH/SOUTH) - a ceiling spot, like a floor spot,
	 * is still naturally "one XZ column has one ceiling/floor", so it reuses this flood-fill's
	 * existing column-based data model unchanged (just walking attachableColumn/nearbyAttachable's
	 * search upward instead of downward - see their own doc comments). A wall spot isn't a function
	 * of an XZ column at all, it's a function of a vertical face at a given Y band - see
	 * findWallSpots' own, deliberately lighter-weight approach for that case instead.
	 * <p>
	 * findAttachableSpots' own flood START is found via a single vertical probe directly at origin's
	 * own (x,z) (attachableColumn's own layered relax search, straight up/down only, no lateral
	 * movement) - fine for a floor spot, since the player is BY DEFINITION standing on a floor right
	 * there, but for a ceiling that same probe requires solid rock directly overhead within a narrow
	 * vertical window, which most cave ceilings simply aren't at any one exact point (live testing
	 * found ceiling spots showing up far less often than floor ones, even after widening how many
	 * slots were reserved for them - this, not flood path-cost, turned out to be why: if that one
	 * probe fails, the WHOLE ceiling flood returns empty immediately, regardless of how good the
	 * ceiling is 10 blocks to either side). findCeilingSeed below widens that single point into a
	 * real search before giving up.
	 */
	public static WaveSpotScan findCeilingSpots(Level level, BlockPos origin, int count) {
		BlockPos start = attachableColumn(level, origin, Direction.DOWN);
		if (start == null) {
			start = findCeilingSeed(level, origin);
		}
		if (start == null) {
			return new WaveSpotScan(List.of(), List.of());
		}
		return findAttachableSpots(level, origin, count, Direction.DOWN, start);
	}

	// How far (ring-sampled, same technique as findDarkest) to search for ANY valid ceiling
	// attachment point near origin, once the direct straight-up probe above origin itself has
	// failed - see findCeilingSpots' own comment for why this matters. 30 blocks, not
	// MAX_SEARCH_RADIUS's own 48 - a ceiling seed this far out already uses up a meaningful chunk of
	// the flood's own distance-from-origin budget before it's even started exploring.
	private static final double[] CEILING_SEED_SEARCH_RADII = {6.0, 12.0, 18.0, 24.0, 30.0};

	/** Ring-samples progressively wider radii around origin (shuffled order, see findDarkest) for
	 * the first XZ column where attachableColumn(DOWN) succeeds - unlike the primary flood-fill,
	 * this isn't itself reachability-verified (same looseness already accepted for
	 * findWallSpots/findDimSpots elsewhere in this class) - it only needs to find A valid ceiling
	 * point to hand off to findAttachableSpots as its flood start, which then explores outward from
	 * there with the real connected-search guarantee. Returns null if nothing qualifies within 30
	 * blocks at all. */
	private static BlockPos findCeilingSeed(Level level, BlockPos origin) {
		for (double radius : CEILING_SEED_SEARCH_RADII) {
			for (int i : shuffledRingIndices()) {
				double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos candidate = attachableColumn(level, origin.offset(dx, 0, dz), Direction.DOWN);
				if (candidate != null) {
					return candidate;
				}
			}
		}
		return null;
	}

	// How far up a wall (from a ring-sampled floor anchor - see findWallSpots) to probe for a dark
	// attachment point - first pass, tune by feel.
	private static final int MAX_WALL_CLIMB_HEIGHT = 32;
	// Same ring-sampling technique as findDimSpots/findDarkest - NOT the flood-fill's real-
	// connected-search guarantee findWaveSpots/findCeilingSpots rely on (see findWallSpots' own doc
	// comment for why that tradeoff is acceptable here) - progressively farther, mirroring the
	// flood-fill's own distance spread (spotDistanceThreshold's 8/16/24/32/40/48).
	private static final double[] WALL_SEARCH_RADII = {8.0, 16.0, 24.0, 32.0, 40.0, 48.0};
	private static final Direction[] HORIZONTAL_DIRECTIONS =
		{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	/**
	 * Wall spots (normal EAST/WEST/NORTH/SOUTH) aren't naturally "one XZ column has one wall" the
	 * way floor/ceiling spots are - a wall spans a whole range of Y at a fixed XZ edge, not a single
	 * point per column - so rather than forcing them into findWaveSpots/findCeilingSpots' flood-fill
	 * data model, this uses the same ring-sampling technique findDimSpots/findDarkest already use
	 * for a lighter-weight secondary spot type: ring-sample floor anchors around origin (standable,
	 * but NOT flood-verified reachable the way the primary spot pool is), then from each anchor,
	 * probe straight up whichever of its 4 horizontal neighbors is solid, requiring every block of
	 * the climb to be genuinely open-and-wall-backed (isAttachable, no gaps) before accepting a
	 * position. Reachability here is "walk to this known-standable anchor, then climb this one
	 * contiguous wall face straight up" - a real path AWCAPI's climbing navigation can always take
	 * once at the anchor, just not flood-verified the same rigorous way the primary pool is (the
	 * same looseness already accepted for findDimSpots/torch candidates elsewhere in this class).
	 */
	public static WaveSpotScan findWallSpots(Level level, BlockPos origin, int count) {
		List<WaveSpot> found = new ArrayList<>();
		for (double radius : WALL_SEARCH_RADII) {
			if (found.size() >= count) {
				break;
			}
			for (int i : shuffledRingIndices()) {
				if (found.size() >= count) {
					break;
				}
				double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos anchor = standableColumn(level, origin.offset(dx, 0, dz));
				if (anchor == null) {
					continue;
				}
				WaveSpot wallSpot = findWallSpotAbove(level, anchor, Math.sqrt(anchor.distSqr(origin)), found);
				if (wallSpot != null) {
					found.add(wallSpot);
				}
			}
		}
		return new WaveSpotScan(found, List.of());
	}

	/** Checks each of the 4 horizontal directions (shuffled, to avoid the same directional bias
	 * findDarkest's own ring-sampling once had) for a wall right at anchor's own height, then climbs
	 * straight up that one face (if found) up to MAX_WALL_CLIMB_HEIGHT blocks looking for a dark
	 * attachment point - stops climbing the instant a gap breaks the wall's continuity. Never
	 * offers anchor itself (dy=0) as a wall spot - that's just standing on the floor next to a wall,
	 * not a meaningfully different, elevated attachment point. */
	private static WaveSpot findWallSpotAbove(Level level, BlockPos anchor, double distance, List<WaveSpot> alreadyFound) {
		List<Direction> directions = new ArrayList<>(HORIZONTAL_DIRECTIONS.length);
		Collections.addAll(directions, HORIZONTAL_DIRECTIONS);
		Collections.shuffle(directions, ThreadLocalRandom.current());
		for (Direction normal : directions) {
			if (!isAttachable(level, anchor, normal)) {
				continue; // no wall in this direction at anchor's own height
			}
			for (int dy = 1; dy <= MAX_WALL_CLIMB_HEIGHT; dy++) {
				BlockPos candidate = anchor.above(dy);
				if (!isAttachable(level, candidate, normal)) {
					break; // wall face ended (opening/gap) - stop climbing this one
				}
				int light = level.getMaxLocalRawBrightness(candidate);
				if (light <= MAX_DARK_LIGHT
						&& alreadyFound.stream().noneMatch(w -> w.position().distSqr(candidate) < MIN_SPOT_SEPARATION_SQR)) {
					return new WaveSpot(candidate, null, distance, normal);
				}
			}
		}
		return null;
	}

	// How far from the wendigo's own current position an orbit-waypoint flood-fill is allowed to
	// range - deliberately larger than MAX_SEARCH_RADIUS/MAX_FLOOD_VISITED, since (unlike every
	// other scan in this class, always seeded at the player) the wendigo orbiting at the edge of
	// its own band can legitimately already be MAX_SEARCH_RADIUS+ blocks from the player itself, on
	// top of however far the new waypoint then needs to be from there too.
	private static final double MAX_ORBIT_SEARCH_RADIUS = 64.0;
	private static final int MAX_ORBIT_FLOOD_VISITED = 6000;

	/**
	 * Finds the nearest (by real path cost, not straight-line) standable, dark column reachable from
	 * the WENDIGO's own current position (self) whose straight-line distance from player falls in
	 * [minDistanceFromPlayer, maxDistanceFromPlayer] - the orbit behavior's own waypoint picker (see
	 * PlanRunner.tickOrbit). Every other scan in this class floods outward from the PLAYER and
	 * guarantees reachability from there; orbit needs the opposite guarantee (reachable from
	 * wherever the wendigo currently stands, which could itself already be far from the player),
	 * filtered by a distance band measured from a DIFFERENT point than the flood's own origin - a
	 * genuinely different shape from findAttachableSpots' cumulative-distance-from-origin threshold
	 * model below, not reusable from it. Since only one waypoint is needed (not a spread pool), this
	 * returns the very first in-band match the flood reaches, which - because the frontier is the
	 * same real-distance-weighted priority queue findAttachableSpots uses - is guaranteed to be the
	 * cheapest-to-reach one, not just the first visited in some arbitrary order. Returns null if the
	 * flood exhausts its budget/radius without finding anything in-band.
	 */
	public static BlockPos findOrbitWaypoint(Level level, BlockPos self, BlockPos player,
			double minDistanceFromPlayer, double maxDistanceFromPlayer) {
		BlockPos start = attachableColumn(level, self, Direction.UP);
		if (start == null) {
			return null;
		}

		Queue<FloodNode> frontier = new PriorityQueue<>(Comparator.comparingDouble(FloodNode::cost));
		Set<Long> visited = new HashSet<>();
		frontier.add(new FloodNode(start, 0.0));
		visited.add(start.asLong());

		int visitedCount = 0;
		while (!frontier.isEmpty() && visitedCount < MAX_ORBIT_FLOOD_VISITED) {
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

			if (distanceFromSelf > MAX_ORBIT_SEARCH_RADIUS) {
				continue; // don't expand past the search boundary
			}
			for (int i : shuffledFloodDirections()) {
				BlockPos neighbor = nearbyAttachable(level, current.getX() + FLOOD_STEP_DX[i], current.getZ() + FLOOD_STEP_DZ[i], current.getY(), Direction.UP);
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

	// Cap on how far above the player orbit's own ceiling-vantage preference (see
	// PlanRunner.tickOrbit) will look for a ceiling to perch on - a straight-line height limit, not
	// tied to MAX_ORBIT_SEARCH_RADIUS (that governs the flood-fill's real path-cost budget for the
	// ordinary floor waypoint search this is an alternative to).
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
		int maxY = (int) Math.min(player.getY() + MAX_CEILING_VANTAGE_HEIGHT, level.getMaxY());
		for (int y = player.getY() + 1; y <= maxY; y++) {
			BlockPos candidate = new BlockPos(player.getX(), y, player.getZ());
			if (isAttachable(level, candidate, Direction.DOWN)) {
				return candidate;
			}
		}
		return null;
	}

	private static WaveSpotScan findAttachableSpots(Level level, BlockPos origin, int count, Direction normal) {
		return findAttachableSpots(level, origin, count, normal, null);
	}

	/** explicitStart, when given, is used as the flood's own starting point INSTEAD of
	 * attachableColumn(origin, normal) - see findCeilingSpots' own comment for why a ceiling flood
	 * needs this (a valid start point found somewhere other than directly at origin's own (x,z)).
	 * Distance thresholds (spot_a..spot_f's own progressive unlock distances) are still measured
	 * from origin regardless - only WHERE the flood begins exploring from changes, not what "how far
	 * from the player" means for an accepted spot. */
	private static WaveSpotScan findAttachableSpots(Level level, BlockPos origin, int count, Direction normal, BlockPos explicitStart) {
		List<WaveSpot> spots = new ArrayList<>();
		List<WaveSpot> torchSpawnCandidates = new ArrayList<>();
		BlockPos start = explicitStart != null ? explicitStart : attachableColumn(level, origin, normal);
		if (start == null) {
			return new WaveSpotScan(spots, torchSpawnCandidates);
		}

		Queue<FloodNode> frontier = new PriorityQueue<>(Comparator.comparingDouble(FloodNode::cost));
		Set<Long> visited = new HashSet<>();
		frontier.add(new FloodNode(start, 0.0));
		visited.add(start.asLong());

		double nextThreshold = MIN_FIRST_SPOT_DISTANCE;
		int visitedCount = 0;
		int torchChecksUsed = 0;
		// Candidates that have already cleared nextThreshold, waiting to see if anything else clears
		// it within ACCEPT_WINDOW_COST before one gets picked - see that constant's own comment.
		List<WaveSpot> thresholdCandidates = new ArrayList<>();
		double windowStartCost = -1.0;

		while (!frontier.isEmpty() && spots.size() < count && visitedCount < MAX_FLOOD_VISITED) {
			FloodNode currentNode = frontier.poll();
			BlockPos current = currentNode.pos();
			visitedCount++;
			boolean isStart = current.equals(start);
			double distance = Math.sqrt(current.distSqr(origin));

			if (windowStartCost >= 0 && currentNode.cost() > windowStartCost + ACCEPT_WINDOW_COST) {
				spots.add(thresholdCandidates.get(ThreadLocalRandom.current().nextInt(thresholdCandidates.size())));
				nextThreshold += SPOT_DISTANCE_STEP;
				thresholdCandidates = new ArrayList<>();
				windowStartCost = -1.0;
			}

			if (!isStart && spots.size() < count) {
				boolean wantsTorchCheck = distance >= nextThreshold || torchSpawnCandidates.size() < MAX_TORCH_SPAWN_CANDIDATES;
				List<BlockPos> nearbyTorch = null;
				if (wantsTorchCheck && torchChecksUsed < MAX_TORCH_CHECKS) {
					torchChecksUsed++;
					nearbyTorch = LightSourceScanner.findLightSources(level, current, TORCH_LINK_RADIUS, 1);
				}
				if (distance >= nextThreshold) {
					int light = level.getMaxLocalRawBrightness(current);
					WaveSpot accepted = null;
					if (light <= MAX_DARK_LIGHT) {
						accepted = new WaveSpot(current, null, distance, normal);
					} else if (nearbyTorch != null && !nearbyTorch.isEmpty()) {
						accepted = new WaveSpot(current, nearbyTorch.get(0), distance, normal);
					}
					boolean tooCloseToAccepted = accepted != null
						&& spots.stream().anyMatch(w -> w.position().distSqr(current) < MIN_SPOT_SEPARATION_SQR);
					if (accepted != null && !tooCloseToAccepted) {
						thresholdCandidates.add(accepted);
						if (windowStartCost < 0) {
							windowStartCost = currentNode.cost();
						}
					}
				}
				if (nearbyTorch != null && !nearbyTorch.isEmpty() && torchSpawnCandidates.size() < MAX_TORCH_SPAWN_CANDIDATES
						&& torchSpawnCandidates.stream().noneMatch(w -> w.position().distSqr(current) < MIN_SPOT_SEPARATION_SQR)) {
					torchSpawnCandidates.add(new WaveSpot(current, nearbyTorch.get(0), distance, normal));
				}
			}

			if (distance > MAX_SEARCH_RADIUS) {
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
		// A window can still be open here - the flood ran out of frontier/budget before any later cell
		// arrived to close it out (see the window-close check above) - don't drop a genuinely found
		// last-threshold candidate just because nothing else happened to race it.
		if (windowStartCost >= 0 && spots.size() < count) {
			spots.add(thresholdCandidates.get(ThreadLocalRandom.current().nextInt(thresholdCandidates.size())));
		}
		return new WaveSpotScan(spots, torchSpawnCandidates);
	}

	/** A flood-fill frontier entry - cost is the accumulated real-distance-weighted path length from
	 * the player (see DIAGONAL_STEP_COST), not a hop count, so the frontier's priority-queue ordering
	 * expands the genuinely closest unvisited column next regardless of how many hops it took to
	 * reach it. */
	private record FloodNode(BlockPos pos, double cost) {
	}

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

	// A "dim spot" sits just above the darkness cutoff used elsewhere (predicate.self_in_darkness,
	// SemanticBands.DARKNESS_LIGHT_THRESHOLD = 2) but below fully lit - the edge of a light source,
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
	 * looking for solid rock above an open block) - see findCeilingSpots. The relax direction has to
	 * invert for correctness (searching downward can never find a ceiling), unlike
	 * nearbyAttachable's own window, which stays unmirrored on purpose (see that method's comment).
	 */
	private static BlockPos attachableColumn(Level level, BlockPos column, Direction normal) {
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

	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
