package com.wendigo.debug;

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
 * went wrong") and a live-path particle trail. Lives in its own package rather than com.wendigo.plan
 * or com.wendigo.wave specifically because both of those need to call into it (PlanRunner for
 * commentary/path, WendigoManager for commentary), and neither currently depends on the other - a
 * shared, globally-reachable utility (same role WendigoMod's own static LOGGER/llmClient fields
 * already play) avoids picking one of those two packages and creating a one-way dependency between
 * them.
 */
public final class WendigoDebug {
	private static final int WHITE = 0xFFFFFF;
	private static final float PATH_PARTICLE_SCALE = 0.6f;

	private static final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

	// Live-tunable replacement for WendigoVisual's standing-rest-pose head-yaw correction, set via
	// /wendigo headoffset <degrees> - lets a player dial in the right value by eye in real time
	// instead of a blind edit/compile/restart guess each attempt. NaN means "no override, use the
	// same HEAD_LOOK_YAW_CORRECTION_DEGREES every other pose already uses."
	private static volatile float standingHeadYawOverrideDegrees = Float.NaN;

	private WendigoDebug() {
	}

	public static void setStandingHeadYawOverride(float degrees) {
		standingHeadYawOverrideDegrees = degrees;
	}

	public static void clearStandingHeadYawOverride() {
		standingHeadYawOverrideDegrees = Float.NaN;
	}

	public static float getStandingHeadYawOverride() {
		return standingHeadYawOverrideDegrees;
	}

	// Live-toggle for WendigoVisual's flat-floor crawl-tracking pitch negation, set via
	// /wendigo crawlpitch - true (the current code default) matches the negation already in place;
	// flipping it to false lets a player instantly A/B the un-negated behavior without a rebuild, in
	// case the negation turns out not to be the actual fix after all.
	private static volatile boolean floorCrawlPitchInverted = true;

	public static void setFloorCrawlPitchInverted(boolean inverted) {
		floorCrawlPitchInverted = inverted;
	}

	public static boolean isFloorCrawlPitchInverted() {
		return floorCrawlPitchInverted;
	}

	// Same live-toggle idea as floorCrawlPitchInverted, for the climbing case instead - set via
	// /wendigo climbpitch. Defaults to true (negated): climbing's headLookExtra composes an
	// additional whole-rig attachment tilt (rigOrientation) AFTER the head's own yaw/pitch rotation,
	// unlike the floor case where headLookExtra is the only rotation in play - so changing the
	// climbing yaw formula (removing its HEAD_LOOK_YAW_CORRECTION_DEGREES term this session) can
	// disturb how the pitch visually composes even though the pitch formula itself didn't change,
	// which is the best working theory for why climbing pitch reads backwards now. Best-guess
	// default, not yet live-confirmed - toggle to compare against the un-negated value.
	private static volatile boolean climbPitchInverted = true;

	public static void setClimbPitchInverted(boolean inverted) {
		climbPitchInverted = inverted;
	}

	public static boolean isClimbPitchInverted() {
		return climbPitchInverted;
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

	/** Whether this specific player has debug enabled - WendigoProgressionTracker uses this to let a
	 * debugging player skip the mob-clearing warmup buffer entirely rather than waiting it out every
	 * test run. */
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
}
