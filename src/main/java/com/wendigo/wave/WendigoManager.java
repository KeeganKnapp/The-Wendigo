package com.wendigo.wave;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.wendigo.WendigoMod;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.spatial.DarkSpotScanner;

/**
 * Owns the wendigo's spawn/despawn lifecycle: at most one per level, spawned near whichever
 * player currently has the highest dweller severity, running a single LLM-authored plan from
 * spawn to despawn with no mid-wave re-planning. See PlanRunner for how the plan body itself
 * executes once the entity exists.
 */
public final class WendigoManager {
	private static final String SYSTEM_PROMPT =
		"You control a wendigo, a shadow-dwelling stalker creature in Minecraft. You are given a "
		+ "target player, their dweller severity, and a handful of scanned dark spots near them "
		+ "labeled spot_a through spot_d (nearest to furthest, or fewer if that's all that was "
		+ "found). Choose which spot to spawn at, a short plan (1-6 steps) of actions/predicates "
		+ "to run once spawned, and which spot to despawn at once the plan finishes - a believable "
		+ "wave: appear close, do something unsettling, then withdraw into the dark.";

	private final WendigoWaveConfig config;
	private final PlayerSeverityTracker severityTracker;
	private final Map<ServerLevel, WaveState> waves = new HashMap<>();

	public WendigoManager(WendigoWaveConfig config, PlayerSeverityTracker severityTracker) {
		this.config = config;
		this.severityTracker = severityTracker;
	}

	public void register() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
	}

	private void onEndServerTick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			tickLevel(level, this.waves.computeIfAbsent(level, l -> new WaveState()));
		}
	}

	private void tickLevel(ServerLevel level, WaveState state) {
		int now = level.getServer().getTickCount();

		if (state.entity != null) {
			boolean timedOut = now - state.waveStartTick > this.config.waveTimeoutTicks;
			if (!state.entity.isAlive() || state.entity.isWaveComplete() || timedOut) {
				if (state.entity.isAlive()) {
					state.entity.discard();
				}
				state.entity = null;
				state.cooldownUntilTick = now + this.config.spawnCooldownTicks;
			}
			return;
		}

		if (state.requestPending || now < state.cooldownUntilTick) {
			return;
		}

		ServerPlayer target = this.severityTracker.mostSevereEligiblePlayer(level);
		if (target != null) {
			beginWave(level, state, target);
		}
	}

	/** Bypasses cooldown/eligibility and calls the real LLM - used by the /wendigo wave debug command. */
	public void forceWave(ServerLevel level, ServerPlayer target) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.entity != null || state.requestPending) {
			return;
		}
		beginWave(level, state, target);
	}

	/**
	 * Bypasses cooldown/eligibility AND the LLM call - runs a hand-authored plan (spawn_at/plan/
	 * despawn_at, same shape the model would return) through the real spawn/despawn lifecycle.
	 * Used by the /wendigo wavetest debug command to iterate on plans for free.
	 */
	public void forceWaveWithPlan(ServerLevel level, ServerPlayer target, JsonObject plan) {
		WaveState state = this.waves.computeIfAbsent(level, l -> new WaveState());
		if (state.entity != null || state.requestPending) {
			return;
		}
		WaveContext context = buildContext(level, target);
		if (context != null) {
			spawnWave(level, state, context, plan);
		}
	}

	private void beginWave(ServerLevel level, WaveState state, ServerPlayer target) {
		WaveContext context = buildContext(level, target);
		if (context == null) {
			return; // nowhere sensible to put it right now - try again next tick
		}

		state.requestPending = true;
		MinecraftServer server = level.getServer();
		WendigoMod.llmClient.requestPlan(SYSTEM_PROMPT, context.toPromptText())
			.whenComplete((plan, error) -> server.execute(() -> {
				state.requestPending = false;
				if (error != null) {
					WendigoMod.LOGGER.error("Wendigo wave plan request failed", error);
					state.cooldownUntilTick = level.getServer().getTickCount() + this.config.spawnCooldownTicks;
					return;
				}
				spawnWave(level, state, context, plan);
			}));
	}

	private WaveContext buildContext(ServerLevel level, ServerPlayer target) {
		List<BlockPos> spots = DarkSpotScanner.findWaveSpots(level, target.blockPosition(), this.config.contextSpotCount);
		if (spots.isEmpty()) {
			return null;
		}
		return new WaveContext(target, this.severityTracker.severityOf(target), this.config.severityCap, spots);
	}

	private void spawnWave(ServerLevel level, WaveState state, WaveContext context, JsonObject plan) {
		BlockPos spawnPos = resolveSpot(context, plan, "spawn_at");
		BlockPos despawnPos = resolveSpot(context, plan, "despawn_at");
		if (spawnPos == null || despawnPos == null) {
			WendigoMod.LOGGER.warn("Wendigo wave plan missing a resolvable spawn/despawn spot, skipping: {}", plan);
			state.cooldownUntilTick = level.getServer().getTickCount() + this.config.spawnCooldownTicks;
			return;
		}

		WendigoEntity wendigo = new WendigoEntity(ModEntities.WENDIGO, level);
		wendigo.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
		level.addFreshEntity(wendigo);
		wendigo.startWave(plan.getAsJsonArray("plan"), despawnPos);

		state.entity = wendigo;
		state.waveStartTick = level.getServer().getTickCount();
	}

	/** Resolves a schema spot label to a position, falling back to a fresh scan rather than ever
	 * rejecting the whole wave over one bad field - same philosophy the schema itself documents. */
	private BlockPos resolveSpot(WaveContext context, JsonObject plan, String field) {
		String label = plan.has(field) ? plan.get(field).getAsString() : null;
		BlockPos resolved = label != null ? context.resolve(label) : null;
		if (resolved == null) {
			resolved = DarkSpotScanner.findDarkest(context.player().level(), context.player().blockPosition(), 16);
		}
		return resolved;
	}

	private static final class WaveState {
		WendigoEntity entity;
		boolean requestPending;
		int waveStartTick;
		int cooldownUntilTick;
	}
}
