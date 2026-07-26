package com.wendigo.entity;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
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

	public void setLightTolerant(boolean lightTolerant) {
		this.lightTolerant = lightTolerant;
	}

	@Override
	public void prepare(PathNavigationRegion region, Mob mob) {
		super.prepare(region, mob);
		this.nodeMalusApplied.clear();
	}

	@Override
	public void done() {
		this.nodeMalusApplied.clear();
		super.done();
	}

	@Override
	public int getNeighbors(Node[] neighbors, Node node) {
		int count = super.getNeighbors(neighbors, node);
		for (int i = 0; i < count; i++) {
			Node neighbor = neighbors[i];
			if (neighbor == null) {
				continue;
			}
			long key = BlockPos.asLong(neighbor.x, neighbor.y, neighbor.z);
			if (this.nodeMalusApplied.containsKey(key)) {
				continue;
			}
			BlockPos pos = new BlockPos(neighbor.x, neighbor.y, neighbor.z);
			float malus = 0.0F;
			if (!this.lightTolerant) {
				malus += lightMalus(this.mob.level().getMaxLocalRawBrightness(pos));
			}
			if (this.mob.level().getBlockState(pos).is(Blocks.COBWEB)) {
				malus += COBWEB_MALUS;
			}
			neighbor.costMalus += malus;
			this.nodeMalusApplied.put(key, malus);
		}
		return count;
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
