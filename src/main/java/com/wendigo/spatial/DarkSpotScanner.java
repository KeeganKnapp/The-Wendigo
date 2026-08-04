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

	// How far from the wendigo's own current position a live-band-position flood-fill is allowed to
	// range - the wendigo can legitimately already be well away from the player itself (mid-orbit, or
	// mid-plan after several steps), on top of however far the new position then needs to be from the
	// player too.
	private static final double MAX_LIVE_BAND_SEARCH_RADIUS = 64.0;
	private static final int MAX_LIVE_BAND_FLOOD_VISITED = 6000;

	/**
	 * Finds the nearest (by real path cost, not straight-line) standable/attachable column reachable
	 * from the WENDIGO's own current position (self) whose straight-line distance from player falls
	 * in [minDistanceFromPlayer, maxDistanceFromPlayer] - the one shared live-position resolver used
	 * everywhere a plan needs "get to roughly this distance from the player": orbit's own waypoint,
	 * movement.approach_band, spawn/engage positioning, and despawn/retreat's own farthest-band
	 * target. Deliberately resolved fresh every single call, against whatever position is passed in
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

	// Same order of magnitude as findNearestUnwatchedDarkSpot's own worst-case sample count (8 radii x
	// 12 ring points = 96) - cheap relative to findLiveBandPosition's own MAX_LIVE_BAND_FLOOD_VISITED
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

	// Retries findLiveBandPosition up to this many times looking for one the player isn't currently
	// looking toward - shuffledFloodDirections' own per-call randomization (see findLiveBandPosition's
	// own doc comment) means repeated calls with the same inputs aren't guaranteed to return the same
	// column, so retrying is a real search, not spinning on a deterministic result.
	private static final int UNWATCHED_POSITION_ATTEMPTS = 5;

	/**
	 * findLiveBandPosition, filtered for a position the player isn't currently facing toward - backs
	 * both spawn_at's own "no_players_looking" special value (WendigoManager's fresh-spawn path, self
	 * seeded from the player's own position since there's no existing entity position yet) and
	 * movement.approach_band's "no_players_looking" band (PlanRunner's mid-plan repositioning, self
	 * seeded from the wendigo's own current position like every other band). Falls back to whichever
	 * candidate was actually found (even if still watched) if none of the attempts come up unwatched,
	 * rather than returning null and leaving the caller with nothing at all - same "some darkness
	 * beats none" philosophy every other fallback in this class already follows.
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

	// Expanding-ring search bounds for findNearestUnwatchedDarkSpot - stage 5's teleport-behind-player
	// ambush is meant to read as "right behind you", not "somewhere vaguely nearby", so this starts
	// close and only widens as far as it has to. First pass, adjust by feel like every other radius
	// in this class.
	private static final double TELEPORT_BEHIND_MIN_RADIUS = 2.0;
	private static final double TELEPORT_BEHIND_MAX_RADIUS = 16.0;
	private static final double TELEPORT_BEHIND_RADIUS_STEP = 2.0;

	/**
	 * Nearest standable, dark (see MAX_DARK_LIGHT), currently-unwatched (see isPlayerLookingToward)
	 * column around player, searched via expanding rings (same RING_SAMPLE_POINTS/shuffled-order
	 * technique findDarkestBiased uses) from TELEPORT_BEHIND_MIN_RADIUS out to _MAX_RADIUS - backs
	 * combat.teleport_behind (stage 5 only). Deliberately NOT flood-verified reachable from the
	 * wendigo's own current position, same looseness findCeilingVantagePoint already accepts for
	 * orbit's own fallback: this is an instant teleport, not something that needs to be walked to, so
	 * reachability from self is irrelevant here - only "is this a real, dark, unwatched spot near the
	 * player" matters. Returns null if nothing in range qualifies at all.
	 */
	public static BlockPos findNearestUnwatchedDarkSpot(Level level, Player player) {
		BlockPos origin = player.blockPosition();
		for (double radius = TELEPORT_BEHIND_MIN_RADIUS; radius <= TELEPORT_BEHIND_MAX_RADIUS; radius += TELEPORT_BEHIND_RADIUS_STEP) {
			for (int i : shuffledRingIndices()) {
				double angle = (2 * Math.PI * i) / RING_SAMPLE_POINTS;
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos candidate = standableColumn(level, origin.offset(dx, 0, dz));
				if (candidate == null) {
					continue;
				}
				if (level.getMaxLocalRawBrightness(candidate) > MAX_DARK_LIGHT) {
					continue;
				}
				if (!isPlayerLookingToward(player, candidate)) {
					return candidate;
				}
			}
		}
		return null;
	}

	/** Angle-only approximation (no line-of-sight/occlusion check, unlike the live in-game stare
	 * predicate) of whether the player is currently facing toward a candidate position - good enough
	 * for "don't send it somewhere already in view", not meant to be as precise as
	 * predicate.player_looking_at_self. Same corner_of_eye threshold (~60 degrees) as that predicate. */
	private static boolean isPlayerLookingToward(Player player, BlockPos pos) {
		Vec3 toPos = Vec3.atCenterOf(pos).subtract(player.getEyePosition()).normalize();
		double alignment = player.getLookAngle().normalize().dot(toPos);
		return alignment >= Math.cos(Math.toRadians(60.0));
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
		int maxY = (int) Math.min(player.getY() + MAX_CEILING_VANTAGE_HEIGHT, level.getMaxY());
		for (int y = player.getY() + 1; y <= maxY; y++) {
			BlockPos candidate = new BlockPos(player.getX(), y, player.getZ());
			if (isAttachable(level, candidate, Direction.DOWN)) {
				return candidate;
			}
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
