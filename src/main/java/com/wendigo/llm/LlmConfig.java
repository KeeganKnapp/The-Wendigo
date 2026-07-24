package com.wendigo.llm;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Tunables for the wendigo's Claude API calls, stored as config/wendigo-llm.json so a server
 * admin can change model/limits without recompiling. The API key itself is intentionally NOT a
 * field here - it's read from the environment (ANTHROPIC_API_KEY, or an `ant auth login`
 * profile) by LlmClient, so it never ends up committed alongside this file.
 */
public class LlmConfig {
	public String model = "claude-haiku-4-5";
	public long maxTokens = 512;
	public int requestTimeoutSeconds = 30;

	public static LlmConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("wendigo-llm.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				LlmConfig loaded = gson.fromJson(reader, LlmConfig.class);
				return loaded != null ? loaded : new LlmConfig();
			} catch (IOException e) {
				throw new RuntimeException("Failed to read " + path, e);
			}
		}

		LlmConfig defaults = new LlmConfig();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, gson.toJson(defaults));
		} catch (IOException e) {
			throw new RuntimeException("Failed to write default config to " + path, e);
		}
		return defaults;
	}
}
