package com.wendigo.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.wendigo.entity.WendigoEntity;
import com.wendigo.plan.PlanPredicates;

/**
 * Live, in-game sibling of the GameTest-only head-visibility diagnostic
 * (WendigoGameTests.stareHeadVisibilityPrintsWhileStanding/WhileCrawling) - the user's own explicit
 * "we need the test to also actually be a command that we can summon dummies with so I can look at
 * their head and see if our head obstruction + stare logic is correct" request. /wendigo headtest
 * stare (see WendigoCommands) spawns two stationary dummies (standing/crawling) and calls start()
 * here; while a session is active, this updates the caller's own action bar every
 * REPORT_INTERVAL_TICKS with whether PlanPredicates.isLookingAtSelf(player, dummy, "dead_stare") -
 * the exact same head-only check the GameTest exercises automatically - currently reads true for
 * each one, so the same logic can be eyeballed by walking around a real dummy in a real session
 * instead of only read back from a log.
 */
public final class StareHeadTest {
	// Same cadence the GameTest version prints at - the user's own explicit "every 10 ticks" number.
	private static final int REPORT_INTERVAL_TICKS = 10;

	private static final class Session {
		final WendigoEntity standing;
		final WendigoEntity crawling;

		Session(WendigoEntity standing, WendigoEntity crawling) {
			this.standing = standing;
			this.crawling = crawling;
		}
	}

	// Per-player, not global - each caller gets their own dummy pair and their own action bar, same
	// "who's watching" scoping WendigoDebug's own enabledPlayers set already uses.
	private static final Map<UUID, Session> activeSessions = new HashMap<>();
	private static int ticksSinceReport;

	private StareHeadTest() {
	}

	/** Called once from WendigoMod.onInitialize, same registration pattern
	 * WendigoProgressionTracker/DarknessOverstayTracker already use for their own periodic checks. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(StareHeadTest::onEndServerTick);
	}

	public static void start(ServerPlayer player, WendigoEntity standing, WendigoEntity crawling) {
		activeSessions.put(player.getUUID(), new Session(standing, crawling));
	}

	/** Returns true if a session was actually active and just got stopped - lets the command report
	 * back honestly instead of always claiming success. */
	public static boolean stop(ServerPlayer player) {
		return activeSessions.remove(player.getUUID()) != null;
	}

	private static void onEndServerTick(MinecraftServer server) {
		if (activeSessions.isEmpty()) {
			return;
		}
		ticksSinceReport++;
		if (ticksSinceReport < REPORT_INTERVAL_TICKS) {
			return;
		}
		ticksSinceReport = 0;
		// Sessions whose dummy died/despawned (killed for testing purposes, chunk unloaded, etc.) or
		// whose player logged off between reports just quietly stop reporting rather than throwing -
		// removeIf lets both cases clean themselves out of the map in the same pass.
		activeSessions.entrySet().removeIf(entry -> {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Session session = entry.getValue();
			if (player == null || !session.standing.isAlive() || !session.crawling.isAlive()) {
				return true;
			}
			boolean standingStared = PlanPredicates.isLookingAtSelf(player, session.standing, "dead_stare");
			boolean crawlingStared = PlanPredicates.isLookingAtSelf(player, session.crawling, "dead_stare");
			Component message = Component.literal("standing: ")
				.append(staredComponent(standingStared))
				.append(Component.literal("  |  crawling: ").withStyle(ChatFormatting.GRAY))
				.append(staredComponent(crawlingStared));
			player.sendSystemMessage(message, true); // true = action bar overlay, not chat - overwrites in place
			return false;
		});
	}

	private static Component staredComponent(boolean stared) {
		return Component.literal(stared ? "STARING" : "not staring")
			.withStyle(stared ? ChatFormatting.GREEN : ChatFormatting.RED);
	}
}
