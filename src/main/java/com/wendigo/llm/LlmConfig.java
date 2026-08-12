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
 * fields here - resolved by LlmClient.resolveApiKey instead (ANTHROPIC_API_KEY for
 * provider=anthropic, OPENAI_API_KEY for provider=openai), which tries, in order: a real OS
 * environment variable, a same-named Java system property (a "-DOPENAI_API_KEY=..." JVM
 * argument - works on locked-down hosting panels that expose custom startup flags but not real
 * env vars), then a plain config/wendigo-<provider>-api-key.txt file (works regardless of what a
 * given panel supports at all, since file-manager/SFTP access is already required just to install
 * the mod jar in the first place) - the Anthropic path also still falls back further to the SDK's
 * own `ant auth login` CLI profile lookup if none of those three have anything. Kept out of this
 * class/file specifically so a key never ends up committed alongside the rest of this config.
 */
public class LlmConfig {
	// "anthropic" or "openai" - selects which of the two below (model/openaiModel) and which env
	// var (ANTHROPIC_API_KEY/OPENAI_API_KEY) LlmClient actually uses; the other provider's client
	// is never constructed, so its key doesn't need to be set at all.
	// Defaults match the project's own actual current choice (see this class's own history/memory:
	// tried switching to Anthropic, hit a schema-complexity "compiled grammar too large" limit that
	// persisted even after trimming the schema, reverted back to OpenAI) - these two fields only
	// matter the first time a given run directory boots (see load() below, which writes them out to
	// a real JSON file at that point and reads the file thereafter), but a fresh run directory
	// (a new dev clone, CI, ./gradlew runGameTest's own separate build/run/gameTest working
	// directory) should bootstrap into the SAME real choice everyone else is already running with,
	// not silently fall back to the abandoned Anthropic path or the pricier/more rate-limited full
	// gpt-4o. Found live: a convergence-testing GameTest run against a freshly-generated
	// build/run/gameTest config hit OpenAI's gpt-4o rate limit within its first few requests, while
	// the hand-edited run/config/wendigo-llm.json (gpt-4o-mini) had no such trouble.
	public String provider = "openai";
	public String model = "claude-haiku-4-5-20251001"; // used when provider = "anthropic"
	public String openaiModel = "gpt-4o-mini"; // used when provider = "openai"
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
