package com.wendigo.wave;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;

import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;

/**
 * Which of the scanned spots can actually be walked to from which other spots, using the same
 * light-averse pathfinding (DarknessAwareNavigation) the wendigo really moves with - not a spatial
 * heuristic like DarkSpotScanner/LightSourceScanner, since "is there a real route" isn't something
 * ring-sampling can answer. Runs once per wave start (small N, real cost only paid at spawn time),
 * feeding movement.approach_spot viability into the prompt so the model can chain "approach a
 * distant, otherwise dim-spot-less spot, then approach one of a *closer* spot's dim spots" instead
 * of being limited to whichever single spot it spawned at.
 */
final class SpotConnectivity {
	private SpotConnectivity() {
	}

	// How close counts as "reached" for this check - looser than a real movement action's arrival
	// tolerance since this only answers "is there a route at all", not "stop exactly here".
	private static final int PATH_ACCURACY = 2;

	/** For each spot, the indices (into the same list) of other spots reachable from it. Reachability is
	 * computed once per unordered pair and mirrored, since the terrain crossed is the same either way. */
	static List<List<Integer>> compute(ServerLevel level, List<BlockPos> spots) {
		int n = spots.size();
		boolean[][] reachable = new boolean[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				boolean canReach = canReach(level, spots.get(i), spots.get(j));
				reachable[i][j] = canReach;
				reachable[j][i] = canReach;
			}
		}
		List<List<Integer>> result = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			List<Integer> row = new ArrayList<>();
			for (int j = 0; j < n; j++) {
				if (i != j && reachable[i][j]) {
					row.add(j);
				}
			}
			result.add(row);
		}
		return result;
	}

	/** A throwaway, never-spawned WendigoEntity used purely for its navigation - createPath doesn't
	 * require the entity to actually be in the world, just positioned and onGround (see PlanRunner's
	 * spawn-tick race fix - the same canUpdatePath gate applies here, and a probe that's never ticked
	 * would otherwise never satisfy it). */
	private static boolean canReach(ServerLevel level, BlockPos from, BlockPos to) {
		WendigoEntity probe = new WendigoEntity(ModEntities.WENDIGO, level);
		probe.snapTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0f, 0f);
		probe.setOnGround(true);
		Path path = probe.getNavigation().createPath(to, PATH_ACCURACY);
		return path != null && path.canReach();
	}
}
