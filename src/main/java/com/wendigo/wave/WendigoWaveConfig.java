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
	public int spawnCooldownTicks = 600; // 30s between waves
	public int severityCap = 10000;
	public int contextSpotCount = 4;
	public int waveTimeoutTicks = 1200; // 60s hard cap on an entire wave, independent of per-action timeouts

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
