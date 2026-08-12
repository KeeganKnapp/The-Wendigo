package com.wendigo.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a position is within a soul-fire-family light source's own "safe zone" radius - relocated
 * here (was private to com.wendigo.wave.WendigoManager) specifically so com.wendigo.plan.PlanRunner
 * can reuse the exact same check for its own per-task soul-light tally (see the soul-light
 * progression redesign this backs: soul light no longer blocks a plan from running at all, it only
 * affects whether a completed task counts for or against the run's own stage progression).
 * com.wendigo.plan cannot depend on com.wendigo.wave (the existing layering runs the other
 * direction - WendigoManager already imports from com.wendigo.plan), so this lives in
 * com.wendigo.spatial instead, the same shared-utility package DarkSpotScanner/LightSourceScanner
 * already live in, reachable from both.
 */
public final class SoulLightScanner {
	private SoulLightScanner() {
	}

	// The user's own explicit per-block-type radii - a campfire's own glow genuinely reaches much
	// farther than a single torch's, so the "safe zone" should scale with the source, not treat a
	// torch and a campfire as equally protective.
	private static final double SOUL_TORCH_RADIUS = 10.0;
	private static final double SOUL_LANTERN_RADIUS = 20.0;
	private static final double SOUL_CAMPFIRE_RADIUS = 30.0;
	// The largest of the three above - governs how far out isNearSoulLight's own scan has to search
	// at all (each candidate block is then checked against its OWN specific radius, not this one).
	private static final double SOUL_LIGHT_MAX_RADIUS = SOUL_CAMPFIRE_RADIUS;

	/** This block's own soul-light "safe zone" radius, or 0.0 if it isn't a soul-fire-family light
	 * source at all - soul wall torches and bare soul fire share the plain torch's own radius (neither
	 * is meaningfully brighter/bigger than a standing soul torch). */
	private static double soulLightRadius(BlockState state) {
		if (state.is(Blocks.SOUL_CAMPFIRE)) {
			return SOUL_CAMPFIRE_RADIUS;
		}
		if (state.is(Blocks.SOUL_LANTERN)) {
			return SOUL_LANTERN_RADIUS;
		}
		if (state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH) || state.is(Blocks.SOUL_FIRE)) {
			return SOUL_TORCH_RADIUS;
		}
		return 0.0;
	}

	/** Whether center is currently close enough to a real, lit soul-fire-family light source that it
	 * counts as "protected" - the user's own explicit "safe zone" concept. Each source's own radius
	 * comes from soulLightRadius above (campfire reaches farthest, torch/wall-torch/bare-fire reach
	 * least). Level, not ServerLevel - matches every other scanner in this package, and lets
	 * PlanRunner call this directly off WendigoEntity.level() without a cast. */
	public static boolean isNearSoulLight(Level level, BlockPos center) {
		int radius = (int) Math.ceil(SOUL_LIGHT_MAX_RADIUS);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			double sourceRadius = soulLightRadius(level.getBlockState(pos));
			if (sourceRadius > 0.0 && pos.distSqr(center) <= sourceRadius * sourceRadius) {
				return true;
			}
		}
		return false;
	}
}
