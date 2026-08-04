package com.wendigo.entity;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Node;

/**
 * Light/cobweb cost-malus logic shared by every one of the wendigo's NodeEvaluators (ground and
 * climbing alike - see DarknessAwareClimberNodeEvaluator) - light level (makes the pathfinder
 * prefer darker routes without literally forbidding lit ones) and cobwebs (this Minecraft version
 * has no dedicated PathType for them, so vanilla path costing doesn't avoid them at all - confirmed
 * via PathType's enum constants, no WEB/COBWEB entry exists - and the wendigo was routinely getting
 * physically stuck in them). Both are large-but-finite penalties rather than PathType.BLOCKED, so
 * either can still be crossed as a last resort instead of failing to find a path at all. Light
 * thresholds mirror DarkSpotScanner's MAX_DARK_LIGHT (5, the spawn/despawn "acceptable" cap) and
 * its dim-spot band (3-5) - the two classes can't literally share a constant (different packages,
 * no dependency between them), kept in sync by hand.
 *
 * <p>Composition, not inheritance: the ground and climbing evaluators can't share a common
 * superclass (one extends vanilla WalkNodeEvaluator, the other AWCAPI's AdvancedWalkNodeProcessor),
 * so each holds one of these instead of hand-duplicating the same tuned constants.
 */
final class DarknessMalus {
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
	// The user's own explicit "40% penalty" request for ceiling pathfinding specifically (walls are
	// deliberately exempt - see apply's own check - matching WendigoEntity's own pitch-ramped speed
	// penalty, which likewise only applies past a dead-on wall toward a ceiling), so the wendigo
	// still climbs when it has to (unreachable from the floor, or the floor route is a much longer
	// detour) but slowly settles back onto the floor to gain speed whenever a floor route is
	// comparably direct. Sized against PathFinder's own real cost formula (confirmed via decompiling
	// vanilla's PathFinder.class: tentativeGScore = current.g + distance + neighbor.costMalus - a
	// FLAT addition on top of the per-step geometric distance, which is ~1.0 for a cardinal step and
	// ~1.414 for a diagonal one, the same scale DarkSpotScanner's own flood-cost model uses) - 0.5 is
	// roughly 40% of that per-step distance, not a percentage of the light/cobweb malus scale above
	// (those are large deliberately, to all but forbid crossing genuinely lit ground; this is meant
	// to be a soft, easily-outweighed tiebreaker instead, since a whole climbing ROUTE accumulates
	// this once per step, not once total).
	private static final float CLIMBING_MALUS = 0.5F;

	// Below this severity, a route through anything brighter than EDGE_LIGHT (5, the same "spawn
	// limit" DarkSpotScanner.MAX_DARK_LIGHT uses) doesn't exist as far as pathfinding is concerned at
	// all - not just expensive, genuinely excluded from the node graph (see apply) - so an
	// early-stage wendigo with no all-dark route available fails to path rather than walking through
	// open light, and falls back to whatever "couldn't get there" already does elsewhere (try the
	// next despawn candidate, eventually just vanish). At/above this, it's allowed through again,
	// still steeply malused (see lightMalus) but no longer a hard wall. Doesn't apply while
	// lightTolerant is set - those two primitives cross into light on purpose regardless of stage.
	private static final int HARD_BLOCK_MAX_PERCENT = 40;

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

	void setLightTolerant(boolean lightTolerant) {
		this.lightTolerant = lightTolerant;
	}

	void setSeverityPercent(int severityPercent) {
		this.severityPercent = severityPercent;
	}

	/** Call from the evaluator's own prepare(region, mob), after super.prepare(...). */
	void prepare(Mob mob) {
		this.nodeMalusApplied.clear();
		this.startLight = mob.level().getMaxLocalRawBrightness(mob.blockPosition());
	}

	/** Call from the evaluator's own done(), before super.done(). */
	void done() {
		this.nodeMalusApplied.clear();
	}

	/**
	 * Filters/costs neighbors[0..count) in place (see DarknessNodeEvaluator's original doc for why
	 * excluded entries have to be compacted out rather than left as null holes), returning the new,
	 * possibly-smaller count. Call from the evaluator's own getNeighbors, after super.getNeighbors.
	 */
	int apply(Mob mob, Node[] neighbors, int count) {
		boolean hardBlockLight = !this.lightTolerant && this.severityPercent < HARD_BLOCK_MAX_PERCENT
			&& this.startLight <= EDGE_LIGHT;
		int kept = 0;
		for (int i = 0; i < count; i++) {
			Node neighbor = neighbors[i];
			if (neighbor == null) {
				continue;
			}
			BlockPos pos = new BlockPos(neighbor.x, neighbor.y, neighbor.z);
			int light = mob.level().getMaxLocalRawBrightness(pos);
			if (hardBlockLight && light > EDGE_LIGHT) {
				continue; // excluded entirely below HARD_BLOCK_MAX_PERCENT - this route doesn't exist
			}
			// The user's own explicit rule: fast, free movement through flowing water (a stream, a
			// shin-deep puddle - PathType.WATER's own malus is already 0.0F for these, see
			// WendigoEntity's constructor) but never PATH OVER a genuine source block (a still cave
			// lake/pond) - unconditional exclusion, not just a heavy malus like cobwebs get, since
			// this is also the proactive half of the drowning-bailout fix (WendigoManager's own
			// air-supply check): staying out of deep still water in the first place beats discarding
			// and re-searching after the fact once it's already mid-lake taking damage. Not gated on
			// severityPercent/lightTolerant the way the light hard-block above is - there's no "last
			// resort, swim the lake anyway" tier this should ever fall back to. getFluidState().is
			// (not getBlockState().is(Blocks.WATER)) also correctly excludes waterlogged blocks whose
			// fluid layer is a genuine source, not just plain water blocks.
			// Fluids.WATER is specifically the source-water singleton (Fluids.FLOWING_WATER is the
			// separate flowing one) - a direct identity check against it, rather than going through
			// FluidTags.WATER, since both Fluid.is(TagKey) and Fluid.builtInRegistryHolder() are
			// deprecated in this MC version (confirmed via javac -Xlint:deprecation).
			if (mob.level().getFluidState(pos).getType() == Fluids.WATER) {
				continue;
			}
			long key = BlockPos.asLong(neighbor.x, neighbor.y, neighbor.z);
			if (!this.nodeMalusApplied.containsKey(key)) {
				float malus = 0.0F;
				if (!this.lightTolerant) {
					malus += lightMalus(light);
				}
				if (mob.level().getBlockState(pos).is(Blocks.COBWEB)) {
					malus += COBWEB_MALUS;
				}
				// Solid ground directly beneath is a genuine floor node (or a legitimate multi-block
				// drop's own landing spot, which always resolves to one of these too - see
				// AdvancedWalkNodeProcessor.getSafePoints) - no penalty. Of what's left (already passed
				// AWCAPI's own attachability check to exist as a neighbor at all, so no floor below
				// reliably means wall or ceiling), only a ceiling attachment (solid directly ABOVE) gets
				// the malus now - the user's own explicit follow-up to WendigoEntity's own pitch-ramped
				// speed penalty, which also only ever applies past a dead-on wall (pitch 90) toward a
				// ceiling (pitch 180): walls should path at full, unpenalized cost too, not just move at
				// full speed once already chosen.
				if (isPassable(mob.level(), pos.below()) && !isPassable(mob.level(), pos.above())) {
					malus += CLIMBING_MALUS;
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

	/** Same collision-shape-empty check DarkSpotScanner's own isPassable/isAttachable use for exactly
	 * this purpose (different package, private there - not worth widening its visibility just for
	 * this one reuse, same tradeoff already accepted elsewhere in this codebase). */
	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
