package com.wendigo.llm;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Tunables for the wendigo's LLM calls, stored as config/wendigo-llm.json so a server admin can
 * change provider/model/limits without recompiling. API keys themselves are intentionally NOT
 * fields here - read from the environment by LlmClient (ANTHROPIC_API_KEY for provider=anthropic,
 * or an `ant auth login` profile; OPENAI_API_KEY for provider=openai), so neither ends up
 * committed alongside this file.
 */
public class LlmConfig {
	// "anthropic" or "openai" - selects which of the two below (model/openaiModel) and which env
	// var (ANTHROPIC_API_KEY/OPENAI_API_KEY) LlmClient actually uses; the other provider's client
	// is never constructed, so its key doesn't need to be set at all.
	public String provider = "anthropic";
	public String model = "claude-haiku-4-5-20251001"; // used when provider = "anthropic"
	public String openaiModel = "gpt-4o"; // used when provider = "openai"
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
