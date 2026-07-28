package com.wendigo.entity;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Standard ground pathfinding, plus additional cost layered on top of each candidate node: light
 * level (makes the pathfinder prefer darker routes without literally forbidding lit ones) and
 * cobwebs (this Minecraft version has no dedicated PathType for them, so vanilla path costing
 * doesn't avoid them at all - confirmed via PathType's enum constants, no WEB/COBWEB entry exists -
 * and the wendigo was routinely getting physically stuck in them). Both are large-but-finite
 * penalties rather than PathType.BLOCKED, so either can still be crossed as a last resort instead
 * of failing to find a path at all. Light thresholds mirror DarkSpotScanner's MAX_DARK_LIGHT (5,
 * the spawn/despawn "acceptable" cap) and its dim-spot band (3-5) - the two classes can't literally
 * share a constant (different packages, no dependency between them), kept in sync by hand.
 */
public class DarknessNodeEvaluator extends WalkNodeEvaluator {
	// Below this, no penalty at all - fully dark, ideal.
	private static final int IDEAL_LIGHT_MAX = 1;
	// 2-4: "can manage" - a mild, linearly growing penalty.
	private static final int MANAGEABLE_LIGHT_MAX = 4;
	private static final float MANAGEABLE_MALUS_PER_LEVEL = 3.0F;
	// Exactly 5: still not "brighter than 5", but the edge of what's tolerable - a bigger step.
	private static final int EDGE_LIGHT = 5;
	private static final float EDGE_MALUS = 20.0F;
	// 6+: "does not want to go through this at all" - a heavy, growing penalty, but finite so a
	// route through it is still possible if it's truly the only option.
	private static final float AVOID_BASE_MALUS = 120.0F;
	private static final float AVOID_MALUS_PER_LEVEL = 25.0F;
	// Cobwebs get an even heavier flat penalty than the worst light case - getting physically stuck
	// is a worse outcome than being briefly lit, so this should almost always lose to any detour.
	private static final float COBWEB_MALUS = 150.0F;

	// NodeEvaluator caches and reuses Node objects by coordinate for the lifetime of one search, so
	// the same neighbor gets handed to getNeighbors repeatedly whenever flood-fill expansion
	// approaches it from a different direction - normal, and harmless for vanilla's own costMalus
	// writes since those are idempotent (Math.max against the same PathType-derived value each
	// time). Naively adding our own malus on every such revisit is NOT idempotent: in open, flat
	// terrain a single tile can be presented as a neighbor up to 8 times before the search closes
	// it, compounding its cost far past the intended "large but finite" penalty and effectively
	// blocking every route near a light source (or cobweb). Track which nodes already got their
	// combined malus applied this search so it happens exactly once per node, cleared alongside the
	// rest of the evaluator's per-search state.
	private final Long2FloatOpenHashMap nodeMalusApplied = new Long2FloatOpenHashMap();

	// combat.lunge_attack and combat.break_torch are documented as the two primitives allowed to
	// cross into light on purpose - but every action shares this same evaluator/navigation, so
	// without an explicit opt-out they were just as light-averse as everything else, and a route
	// that necessarily ends *inside* a lit area (next to a torch) racks up enough malus on its
	// final stretch that the best-effort search kept stopping at the edge of the light instead of
	// paying the cost to finish. PlanRunner toggles this immediately before/after those two moveTo
	// calls; every other action leaves it false. Cobweb avoidance is NOT covered by this toggle -
	// getting stuck is a mobility problem, not a light-exposure tradeoff, so it applies regardless.
	private boolean lightTolerant;

	// Below this severity, a route through anything brighter than EDGE_LIGHT (5, the same "spawn
	// limit" DarkSpotScanner.MAX_DARK_LIGHT uses) doesn't exist as far as pathfinding is concerned at
	// all - not just expensive, genuinely excluded from the node graph (see getNeighbors) - so an
	// early-stage wendigo with no all-dark route available fails to path rather than walking through
	// open light, and falls back to whatever "couldn't get there" already does elsewhere (try the
	// next despawn candidate, eventually just vanish). At/above this, it's allowed through again,
	// still steeply malused (see lightMalus) but no longer a hard wall. Doesn't apply while
	// lightTolerant is set - those two primitives cross into light on purpose regardless of stage.
	private static final int HARD_BLOCK_MAX_PERCENT = 40;
	// Set by WendigoEntity right after severityPercent is known for the current wave (see
	// PlanRunner.start) - defaults to 100 (no hard block) so pathfinding never misbehaves before a
	// wave has actually told it what stage this is, e.g. any incidental navigation between
	// construction and the first startWave call.
	private int severityPercent = 100;
	// Light level at the mob's own position when this search began (see prepare) - real bug found via
	// logs/reports: the hard block above was excluding EVERY neighbor whenever the mob's actual
	// current spot was itself already lit (e.g. standing right next to a torch it just broke), since
	// nothing directly reachable from a lit position necessarily reads as "dark enough" either -
	// leaving getNeighbors returning zero candidates, moveTo silently failing to find any path at
	// all, and the action never resolving on its own ("stands there doing nothing"). The hard block
	// is about refusing to leave safety, not about refusing to ever move again once already exposed -
	// skip it entirely for a search that starts already brighter than EDGE_LIGHT, falling back to the
	// normal finite malus so it can still find its way back to real darkness.
	private int startLight;

	public void setLightTolerant(boolean lightTolerant) {
		this.lightTolerant = lightTolerant;
	}

	public void setSeverityPercent(int severityPercent) {
		this.severityPercent = severityPercent;
	}

	@Override
	public void prepare(PathNavigationRegion region, Mob mob) {
		super.prepare(region, mob);
		this.nodeMalusApplied.clear();
		// super.prepare() derives entityHeight from the mob's CURRENT bounding box - full standing
		// height (STANDING_DIMENSIONS) while standing, 1 while already crawling (WendigoEntity's own
		// pose, driven by whether its current position actually has standing room - see updatePose).
		// A search only treats 1-block gaps as valid while the mob is ALREADY in crawl pose when it
		// begins - true right after spawning inside a crevice (see WendigoEntity.syncPoseToSpawnPosition,
		// which sets pose before this is ever called), so "spawn in a crevice, path back out of it"
		// works; a search starting from a normal standing position requires full standing-height
		// clearance throughout.
		this.startLight = mob.level().getMaxLocalRawBrightness(mob.blockPosition());
	}

	@Override
	public void done() {
		this.nodeMalusApplied.clear();
		super.done();
	}

	/**
	 * Vanilla's own WalkNodeEvaluator.getPathTypeOfMob downgrades a rail tile from PathType.RAIL
	 * (malus 0.0F, perfectly normal - verified via bytecode) to PathType.UNPASSABLE_RAIL (malus
	 * -1.0F, effectively BLOCKED) unless the searching mob's OWN current position is also a rail
	 * tile - a heuristic that happens to work out for a live entity already walking along a track
	 * (its own position often satisfies that check once it's actually engaged with the rails), but
	 * produces a false "blocked" verdict for anything searching from a position that simply isn't
	 * itself sitting on a rail - including the live entity itself, any time it starts a fresh search
	 * from off the tracks. Real bug found via user testing: surrounding a player with rails in an
	 * otherwise perfectly ordinary 2-tall room
	 * made every candidate spot read as unreachable; removing a single rail immediately fixed it,
	 * and the live (already-moving) wendigo never had any trouble crossing the same rails. Rails
	 * have no special meaning to this mob at all - undo the downgrade unconditionally.
	 */
	@Override
	public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
		PathType type = super.getPathTypeOfMob(context, x, y, z, mob);
		return type == PathType.UNPASSABLE_RAIL ? PathType.RAIL : type;
	}

	@Override
	public int getNeighbors(Node[] neighbors, Node node) {
		int count = super.getNeighbors(neighbors, node);
		boolean hardBlockLight = !this.lightTolerant && this.severityPercent < HARD_BLOCK_MAX_PERCENT
			&& this.startLight <= EDGE_LIGHT;
		// Compacted in place rather than nulling entries within the returned count - PathFinder
		// dereferences every neighbor up to whatever count getNeighbors returns with no null check of
		// its own (confirmed via bytecode), so a genuinely excluded node has to actually be removed
		// from the array/count, not just left as a null hole in it.
		int kept = 0;
		for (int i = 0; i < count; i++) {
			Node neighbor = neighbors[i];
			if (neighbor == null) {
				continue;
			}
			BlockPos pos = new BlockPos(neighbor.x, neighbor.y, neighbor.z);
			int light = this.mob.level().getMaxLocalRawBrightness(pos);
			if (hardBlockLight && light > EDGE_LIGHT) {
				continue; // excluded entirely below HARD_BLOCK_MAX_PERCENT - this route doesn't exist
			}
			long key = BlockPos.asLong(neighbor.x, neighbor.y, neighbor.z);
			if (!this.nodeMalusApplied.containsKey(key)) {
				float malus = 0.0F;
				if (!this.lightTolerant) {
					malus += lightMalus(light);
				}
				if (this.mob.level().getBlockState(pos).is(Blocks.COBWEB)) {
					malus += COBWEB_MALUS;
				}
				neighbor.costMalus += malus;
				this.nodeMalusApplied.put(key, malus);
			}
			neighbors[kept] = neighbor;
			kept++;
		}
		return kept;
	}

	private static float lightMalus(int light) {
		if (light <= IDEAL_LIGHT_MAX) {
			return 0.0F;
		}
		if (light <= MANAGEABLE_LIGHT_MAX) {
			return (light - IDEAL_LIGHT_MAX) * MANAGEABLE_MALUS_PER_LEVEL;
		}
		if (light == EDGE_LIGHT) {
			return EDGE_MALUS;
		}
		return AVOID_BASE_MALUS + (light - EDGE_LIGHT) * AVOID_MALUS_PER_LEVEL;
	}
}
