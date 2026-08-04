package com.wendigo.wave;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Tunables for the wendigo's spawn/despawn wave lifecycle, stored as config/wendigo-wave.json. */
public class WendigoWaveConfig {
	// Cooldown between automatic severity-triggered waves scales with how severe things already are
	// for the target - a barely-there stage 1 relationship shouldn't be pestered as often as one
	// that's deep into escalation (see dynamicCooldownTicks). Min/max bound that interpolation.
	public int spawnCooldownMinTicks = 300; // 15s, at 100% severity
	public int spawnCooldownMaxTicks = 1200; // 60s, at 0% severity
	public int contextSpotCount = 6;
	// Rare last-resort backstop only - a patient stare-hold with the player still nearby should
	// never hit this. See WendigoManager.checkForcedWaveEnd for the real, more targeted triggers
	// (player genuinely disengaged, stuck at extreme close range) that end a wave well before this
	// would ever need to.
	public int waveTimeoutTicks = 4800; // 4min
	// Cooldown applied after a /wendigo wave or /wendigo wavetest debug wave instead of
	// spawnCooldownTicks - long enough that the automatic severity-triggered spawner won't sneak in
	// a real (LLM-costing) wave while someone's still mid-session testing under y=0.
	public int debugCooldownTicks = 6000; // 5min
	// How often (in ticks) /wendigo debug re-emits the active wave's spot/dim-spot particles - they
	// only persist ~1s on their own, so this needs to be well under 20.
	public int debugParticleIntervalTicks = 10;
	// How often (in ticks) /wendigo debug logs a mid-wave context snapshot (self position, distance
	// to player, light level, staring/crawling/nav-failed/nav-stuck flags) - a standing trend signal
	// alongside the per-action logging PlanRunner already does, not tied to any one action ending.
	public int debugContextIntervalTicks = 20; // 1s

	/** Linearly interpolates between spawnCooldownMaxTicks (0% severity) and spawnCooldownMinTicks
	 * (100%+) - the more established this player's relationship with the dark already is, the
	 * sooner the next real (LLM-costing) wave is allowed to fire. */
	public int dynamicCooldownTicks(int severityPercent) {
		int clamped = Math.clamp(severityPercent, 0, 100);
		return this.spawnCooldownMaxTicks - (this.spawnCooldownMaxTicks - this.spawnCooldownMinTicks) * clamped / 100;
	}

	public static WendigoWaveConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("wendigo-wave.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				WendigoWaveConfig loaded = gson.fromJson(reader, WendigoWaveConfig.class);
				return loaded != null ? loaded : new WendigoWaveConfig();
			} catch (IOException e) {
				throw new RuntimeException("Failed to read " + path, e);
			}
		}

		WendigoWaveConfig defaults = new WendigoWaveConfig();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, gson.toJson(defaults));
		} catch (IOException e) {
			throw new RuntimeException("Failed to write default config to " + path, e);
		}
		return defaults;
	}
}
