package com.wendigo.debug;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Per-player debug session registry for the wendigo: chat commentary ("what is it doing / what
 * went wrong") and particle visualization (scanned spots, dim spots, live path). Lives in its own
 * package rather than com.wendigo.plan or com.wendigo.wave specifically because both of those
 * need to call into it (PlanRunner for commentary/path, WendigoManager for spot particles), and
 * neither currently depends on the other - a shared, globally-reachable utility (same role
 * WendigoMod's own static LOGGER/llmClient fields already play) avoids picking one of those two
 * packages and creating a one-way dependency between them.
 */
public final class WendigoDebug {
	// spot_a, spot_b, spot_c, spot_d - red, green, blue, yellow.
	private static final int[] SPOT_COLORS = {0xFF3B30, 0x34C759, 0x3B82F6, 0xFFD60A, 0x9c00eb, 0xff6600 };
	private static final float DIM_SPOT_DARKEN_FACTOR = 0.5f;
	private static final int WHITE = 0xFFFFFF;
	private static final float SPOT_PARTICLE_SCALE = 1.2f;
	private static final float DIM_SPOT_PARTICLE_SCALE = 0.9f;
	private static final float PATH_PARTICLE_SCALE = 0.6f;

	private static final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

	private WendigoDebug() {
	}

	/** Flips this player's debug session, returns the new state (true = now enabled). */
	public static boolean toggle(ServerPlayer player) {
		if (!enabledPlayers.remove(player.getUUID())) {
			enabledPlayers.add(player.getUUID());
			return true;
		}
		return false;
	}

	/** Cheap early-exit check so callers on hot paths (PlanRunner.tick()) don't do real work when nobody's watching. */
	public static boolean anyEnabled() {
		return !enabledPlayers.isEmpty();
	}

	/** Whether this specific player has debug enabled - PlayerSeverityTracker uses this to let a
	 * debugging player skip the warmup buffer entirely rather than waiting it out every test run. */
	public static boolean isEnabled(ServerPlayer player) {
		return enabledPlayers.contains(player.getUUID());
	}

	/** Sends a timestamped debug line to every enabled player currently in the given level. Centralized
	 * here (rather than each call site formatting its own) so every debug line - from PlanRunner,
	 * WendigoManager, wherever - carries the same clock, letting trends across a whole test session be
	 * read back from chat/log history in order. */
	public static void say(ServerLevel level, String message) {
		if (enabledPlayers.isEmpty()) {
			return;
		}
		Component component = Component.literal("[wendigo " + timestamp(level) + "] " + message).withStyle(ChatFormatting.GRAY);
		for (ServerPlayer player : level.players()) {
			if (enabledPlayers.contains(player.getUUID())) {
				player.sendSystemMessage(component);
			}
		}
	}

	/** Server tick count rendered as seconds to a hundredth (finer than the requested quarter-second,
	 * never coarser) - tick-based rather than wall-clock so it stays meaningful across a /tick freeze
	 * or /tick step session instead of racing ahead of what actually happened in-world. */
	private static String timestamp(ServerLevel level) {
		return String.format("t=%.2fs", level.getServer().getTickCount() / 20.0);
	}

	/**
	 * Draws the wave's scanned dark spots (colored by index - red/green/blue/yellow for
	 * spot_a..spot_d) and each one's dim spots (same color, darkened) to every enabled player.
	 * Meant to be called periodically (not every tick - particles persist ~1s on their own) for as
	 * long as the wave that scanned them is still active.
	 */
	public static void showSpots(ServerLevel level, List<BlockPos> spots, List<List<BlockPos>> dimSpotsPerSpot) {
		if (enabledPlayers.isEmpty()) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (!enabledPlayers.contains(player.getUUID())) {
				continue;
			}
			for (int i = 0; i < spots.size() && i < SPOT_COLORS.length; i++) {
				sendDust(level, player, spots.get(i), SPOT_COLORS[i], SPOT_PARTICLE_SCALE);
				if (i >= dimSpotsPerSpot.size()) {
					continue;
				}
				int darker = darken(SPOT_COLORS[i], DIM_SPOT_DARKEN_FACTOR);
				for (BlockPos dimSpot : dimSpotsPerSpot.get(i)) {
					sendDust(level, player, dimSpot, darker, DIM_SPOT_PARTICLE_SCALE);
				}
			}
		}
	}

	/** Draws the remaining (not-yet-walked) portion of the navigator's current path in white. */
	public static void showPath(ServerLevel level, PathNavigation navigation) {
		if (enabledPlayers.isEmpty()) {
			return;
		}
		Path path = navigation.getPath();
		if (path == null) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (!enabledPlayers.contains(player.getUUID())) {
				continue;
			}
			for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
				BlockPos pos = path.getNodePos(i);
				sendDust(level, player, pos, WHITE, PATH_PARTICLE_SCALE);
			}
		}
	}

	private static void sendDust(ServerLevel level, ServerPlayer player, BlockPos pos, int color, float scale) {
		DustParticleOptions options = new DustParticleOptions(color, scale);
		level.sendParticles(player, options, true, false,
			pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.15, 0.15, 0.15, 0.0);
	}

	private static int darken(int color, float factor) {
		int r = (int) (((color >> 16) & 0xFF) * factor);
		int g = (int) (((color >> 8) & 0xFF) * factor);
		int b = (int) ((color & 0xFF) * factor);
		return (r << 16) | (g << 8) | b;
	}
}
