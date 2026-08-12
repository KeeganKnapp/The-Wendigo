package com.wendigo.spatial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import com.wendigo.block.SnuffedTorchBlock;
import com.wendigo.block.SnuffedWallTorchBlock;
import com.wendigo.block.WendigoBlocks;

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
		return findLightSources(level, origin, maxRadius, count, LightSourceScanner::isBreakableLightSource);
	}

	/** isSnuffableLightSource's own scan - a real light source the wendigo will actually SNUFF rather
	 * than fully destroy (see snuffByWendigo) - torch-family or candle-family only, a narrower subset
	 * of findLightSources' own broad "any non-full-block emitter" scope. Deliberately a SEPARATE scan,
	 * not a filter layered on top of findLightSources' own results - lanterns/everything else must stay
	 * fully untouched by anything this feeds (combat.break_torch's targeting, the passive chase-
	 * collateral destruction), while findLightSources itself keeps its existing broad behavior
	 * unchanged for its other callers (DarkSpotScanner's torch-count prompt context, spawn_on_torch
	 * candidate scanning). */
	public static List<BlockPos> findSnuffableLightSources(Level level, BlockPos origin, double maxRadius, int count) {
		return findLightSources(level, origin, maxRadius, count, LightSourceScanner::isSnuffableLightSource);
	}

	private static List<BlockPos> findLightSources(Level level, BlockPos origin, double maxRadius, int count,
			BiPredicate<Level, BlockPos> isTarget) {
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
				BlockPos source = climbLightSource(level, seed, isTarget);
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
	 * Destroys a light source the wendigo itself is responsible for (torches/lanterns/etc. outside the
	 * snuffable scope, or a torch-linked spawn spot) - dropBlock=true, so it just drops itself as an
	 * ordinary item the same way a player breaking it would. Still used as-is for anything
	 * findSnuffableLightSources doesn't cover.
	 */
	public static void destroyByWendigo(ServerLevel level, BlockPos pos, Entity source) {
		level.destroyBlock(pos, true, source, 512);
	}

	// The user's own explicit request, matching /playsound minecraft:block.redstone_torch.burnout block
	// @a ~ ~ ~ 0.5 2 exactly (volume 0.5, pitch 2) - played once at pos for every real snuff (not the
	// destroyByWendigo fallback below, which isn't a snuff at all).
	private static void playSnuffSound(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.BLOCKS, 0.5F, 2.0F);
	}

	/**
	 * Replaces a torch/candle at pos with its snuffed (unlit, light level 0, still physically present)
	 * form instead of destroying it - candles just get their own real LIT property flipped off in
	 * place (a real, already-existing vanilla state - see AbstractCandleBlock), torches get replaced
	 * with the matching one of WendigoBlocks' 8 SnuffedTorchBlock/SnuffedWallTorchBlock instances
	 * (preserving FACING for the wall case), remembering the exact real family so a later
	 * flint-and-steel relight or a plain break recovers the right item. Callers are expected to have
	 * already found pos via findSnuffableLightSources, so both branches below should always match one
	 * of the two families in practice - falls back to destroyByWendigo if somehow neither does, rather
	 * than silently doing nothing to a light source the caller clearly intended to act on.
	 */
	public static void snuffByWendigo(ServerLevel level, BlockPos pos, Entity source) {
		BlockState state = level.getBlockState(pos);
		if (state.is(BlockTags.CANDLES) || state.is(BlockTags.CANDLE_CAKES)) {
			level.setBlock(pos, state.setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL);
			playSnuffSound(level, pos);
			return;
		}
		// Soul campfire only - the user's own explicit request. A real, already-existing vanilla
		// state (the same LIT property water/a snowball already extinguishes a campfire with, and
		// isLitCampfire's own real getter reads), same in-place-flip shape as candles right above -
		// no custom block needed the way torches (which have no vanilla "unlit" variant at all) do.
		// Plain campfire is deliberately untouched - never asked for, and wasn't snuffable before.
		if (state.is(Blocks.SOUL_CAMPFIRE)) {
			level.setBlock(pos, state.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
			playSnuffSound(level, pos);
			return;
		}
		Block snuffed = WendigoBlocks.snuffedFor(state.getBlock());
		if (snuffed instanceof SnuffedWallTorchBlock wallTorch) {
			level.setBlock(pos, wallTorch.defaultBlockState()
				.setValue(SnuffedWallTorchBlock.FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING)),
				Block.UPDATE_ALL);
			playSnuffSound(level, pos);
			return;
		}
		if (snuffed != null) {
			level.setBlock(pos, snuffed.defaultBlockState(), Block.UPDATE_ALL);
			playSnuffSound(level, pos);
			return;
		}
		destroyByWendigo(level, pos, source);
	}


	/** True for any light-emitting block combat.break_torch/the passive chase-destruction/torch-
	 * linked-spawn machinery is allowed to target for full destruction (findLightSources' own scope) -
	 * light-emitting (getLightEmission() > 0), NOT a full block (isCollisionShapeFullBlock - excludes
	 * glowstone, sea lanterns, shroomlight, redstone lamps, jack o'lanterns and the like, matching the
	 * user's own explicit "isn't a full block" scope), and explicitly not an end rod or glow lichen even
	 * though both also have a non-full-block collision shape (the carve-outs the user asked for - glow
	 * lichen in particular isn't a placed light source the way a torch/lantern is, it's a naturally-
	 * spreading cave decoration, so "break the torch" reads wrong applied to it). Covers standing/wall/
	 * soul torches, copper torches at every oxidation stage, lanterns and copper lanterns at every
	 * oxidation stage, and redstone torches all for free - no per-block-type allowlist needed, just the
	 * shape-based rule plus these two carve-outs. Also still backs DarkSpotScanner's torch-count prompt
	 * context and spawn_on_torch candidate scanning (via findLightSources) - deliberately untouched by
	 * the narrower snuffable scope below, which only governs what the wendigo will actually SNUFF. */
	private static boolean isBreakableLightSource(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getLightEmission() > 0
			&& !state.isCollisionShapeFullBlock(level, pos)
			&& !state.is(Blocks.END_ROD)
			&& !state.is(Blocks.GLOW_LICHEN);
	}

	/** True only for the 8 real torch blocks WendigoBlocks knows how to snuff (torch/soul_torch/
	 * copper_torch/redstone_torch, standing + wall), a candle/candle-cake block (BlockTags.CANDLES/
	 * CANDLE_CAKES), or a lit soul campfire - see snuffByWendigo. Deliberately much narrower than
	 * isBreakableLightSource (see findSnuffableLightSources' own doc comment for why lanterns and
	 * everything else stay out of this one specifically) - soul LANTERNS in particular stay excluded
	 * here on purpose, the user's own explicit "in line with our rules about other [regular]
	 * lanterns" call: never snuffable/breakable, soul or not. Plain (non-soul) campfire is
	 * deliberately excluded too - only soul campfires were ever asked for. */
	private static boolean isSnuffableLightSource(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return WendigoBlocks.snuffedFor(state.getBlock()) != null
			|| state.is(BlockTags.CANDLES) || state.is(BlockTags.CANDLE_CAKES)
			|| state.is(Blocks.SOUL_CAMPFIRE);
	}

	/**
	 * Greedily steps to whichever of the 6 face-neighbors has strictly higher block light than the
	 * current position, repeating until no neighbor is brighter (a local maximum). Returns that
	 * position if it's an actual target per isTarget, or null if the climb dead-ended on a bright spot
	 * that isn't one (e.g. it wandered toward a lit but non-emissive area, or landed on a full-block
	 * source/end rod, neither of which is a valid target here). Block light is bounded 0-15 and each
	 * step strictly increases it, so this always terminates.
	 */
	private static BlockPos climbLightSource(Level level, BlockPos start, BiPredicate<Level, BlockPos> isTarget) {
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
				return isTarget.test(level, best) && hasPassableNeighbor(level, best)
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
