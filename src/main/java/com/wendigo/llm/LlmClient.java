package com.wendigo.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

/**
 * Async wrapper around one wendigo decision request, backed by either the Claude API (official
 * SDK) or GPT-4o (raw REST call over java.net.http - no SDK dependency added) depending on
 * LlmConfig.provider. Only the selected provider's client is ever constructed, so the other
 * provider's API key doesn't need to be set. Both paths are blocking under the hood, so every call
 * runs off the server tick thread - callers must hop back via MinecraftServer.execute(...) before
 * touching entity/world state with the result.
 *
 * The schema is supplied per-request (see requestPlan), not loaded/fixed once at construction -
 * see com.wendigo.plan.SchemaBuilder, which filters the base action_schema.json down to whatever's
 * actually unlocked at the caller's current severity, rather than this class always sending the
 * full static schema regardless of tier.
 */
public class LlmClient {
	private static final String OPENAI_CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";

	private final LlmConfig config;
	private final Gson gson = new Gson();

	// Anthropic path - null when provider = "openai".
	private final AnthropicClient anthropicClient;
	// Blocking SDK calls each need their own thread; there's only ever a handful of decision
	// requests in flight at once (one per active wendigo), so an unbounded cached pool is fine.
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "wendigo-llm-request");
		t.setDaemon(true);
		return t;
	});

	// OpenAI path - null when provider = "anthropic". HttpClient.sendAsync is natively async, so
	// this path doesn't need the executor above at all.
	private final HttpClient openAiHttpClient;

	public LlmClient(LlmConfig config) {
		this.config = config;

		if (isOpenAi()) {
			this.anthropicClient = null;
			this.openAiHttpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(config.requestTimeoutSeconds))
				.build();
		} else {
			AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
				.timeout(Duration.ofSeconds(config.requestTimeoutSeconds));
			String resolvedKey = resolveApiKey("ANTHROPIC_API_KEY", "anthropic");
			if (resolvedKey != null) {
				builder.apiKey(resolvedKey);
			} else {
				// Nothing found via resolveApiKey's own chain - fall back to the SDK's own real
				// .fromEnv(), which (per LlmConfig's own doc comment) also checks an `ant auth login`
				// CLI profile file, a lookup path resolveApiKey deliberately doesn't try to replicate.
				builder.fromEnv();
			}
			this.anthropicClient = builder.build();
			this.openAiHttpClient = null;
		}
	}

	private boolean isOpenAi() {
		return "openai".equalsIgnoreCase(this.config.provider);
	}

	// The user's own explicit ask: a locked-down game-hosting panel (no root/shell access) may not
	// expose a way to set a real OS environment variable at all - tried in order: (1) the OS env var
	// itself, the normal case for a dev machine or any host that DOES let you configure the process
	// environment; (2) a Java system property of the SAME name, settable via a "-DOPENAI_API_KEY=..."
	// JVM argument - many panels expose a "JVM Arguments"/"Additional Flags" startup field even when
	// they don't expose real env vars; (3) a local plaintext file in this mod's own Fabric config
	// directory (config/wendigo-<name>-api-key.txt, just the raw key, no other structure) - works
	// regardless of what a given panel supports, since file-manager/SFTP access is already required
	// just to install the mod jar in the first place. Returns null (not throwing) if none of the
	// three have anything - callers decide what "no key configured" means for their own request
	// path. Never logged, never written back anywhere except the placeholder file itself (see
	// below) - otherwise purely read-only resolution.
	private static String resolveApiKey(String envVarName, String fileBaseName) {
		String fromEnv = System.getenv(envVarName);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.trim();
		}
		String fromProperty = System.getProperty(envVarName);
		if (fromProperty != null && !fromProperty.isBlank()) {
			return fromProperty.trim();
		}
		Path path = FabricLoader.getInstance().getConfigDir().resolve("wendigo-" + fileBaseName + "-api-key.txt");
		// A real, live-reported gap: this used to only ever READ the file, never create it - so on
		// a fresh install there was nothing for an admin to find or edit at all, no matter how they
		// looked for it. Same "auto-write a discoverable default on first load" shape
		// LlmConfig.load() already uses for the JSON config, just plain text instead of JSON -
		// write a commented placeholder (never a real key) the first time this file doesn't exist,
		// so a server admin has something concrete to open and edit via SFTP/file manager.
		if (!Files.exists(path)) {
			try {
				Files.createDirectories(path.getParent());
				Files.writeString(path, "# Paste your " + envVarName + " value on its own line below (no "
					+ "quotes), then restart the server for it to take effect.\n"
					+ "# Lines starting with # are ignored.\n");
			} catch (IOException e) {
				throw new RuntimeException("Failed to write placeholder " + path, e);
			}
			return null;
		}
		try {
			for (String line : Files.readAllLines(path)) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					return trimmed;
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to read " + path, e);
		}
		return null;
	}

	// JsonOutputFormat.Schema has no typed fields of its own - it's a freeform bag of
	// additionalProperties, so the whole schema is rebuilt one top-level key at a time. Bridges
	// Gson's JsonObject to Jackson's JsonNode via its serialized text - the two libraries have no
	// direct interop, and this only runs once per request, not per tick.
	private static JsonOutputFormat.Schema buildAnthropicSchema(JsonObject schema) {
		try {
			JsonNode root = new ObjectMapper().readTree(schema.toString());
			JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
			Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				builder.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue()));
			}
			return builder.build();
		} catch (IOException e) {
			throw new RuntimeException("Failed to convert filtered schema for the Anthropic client", e);
		}
	}

	// OpenAI's strict structured-output mode wants the schema object itself, not a JSON Schema
	// dialect declaration - $schema is metadata about the schema, not a constraint, so it's
	// stripped rather than passed through untouched.
	private static JsonObject buildOpenAiSchema(JsonObject schema) {
		JsonObject copy = schema.deepCopy();
		copy.remove("$schema");
		return copy;
	}

	/**
	 * Asks the model for one decision turn, constrained to the given schema shape (see
	 * com.wendigo.plan.SchemaBuilder). Completes with the parsed action-plan JSON, or completes
	 * exceptionally on a refusal or malformed output.
	 */
	public CompletableFuture<JsonObject> requestPlan(String systemPrompt, String userPrompt, JsonObject schema) {
		return isOpenAi() ? requestPlanOpenAi(systemPrompt, userPrompt, schema) : requestPlanAnthropic(systemPrompt, userPrompt, schema);
	}

	private CompletableFuture<JsonObject> requestPlanAnthropic(String systemPrompt, String userPrompt, JsonObject schema) {
		JsonOutputFormat format = JsonOutputFormat.builder().schema(buildAnthropicSchema(schema)).build();
		OutputConfig outputConfig = OutputConfig.builder().format(format).build();

		MessageCreateParams params = MessageCreateParams.builder()
			.model(this.config.model)
			.maxTokens(this.config.maxTokens)
			.system(systemPrompt)
			.addUserMessage(userPrompt)
			.outputConfig(outputConfig)
			.build();

		return CompletableFuture.supplyAsync(() -> parseAnthropicPlan(this.anthropicClient.messages().create(params)), this.executor);
	}

	private JsonObject parseAnthropicPlan(Message response) {
		if (response.stopReason().isPresent() && response.stopReason().get().equals(StopReason.REFUSAL)) {
			throw new RuntimeException("Claude declined the request (refusal)");
		}

		for (ContentBlock block : response.content()) {
			if (block.isText()) {
				return JsonParser.parseString(block.asText().text()).getAsJsonObject();
			}
		}
		throw new RuntimeException("No text content block in Claude's response");
	}

	private CompletableFuture<JsonObject> requestPlanOpenAi(String systemPrompt, String userPrompt, JsonObject schema) {
		String apiKey = resolveApiKey("OPENAI_API_KEY", "openai");
		if (apiKey == null || apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"OPENAI_API_KEY is not set - checked the OPENAI_API_KEY environment variable, the "
					+ "OPENAI_API_KEY system property (-DOPENAI_API_KEY=... JVM argument), and "
					+ "config/wendigo-openai-api-key.txt"));
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", this.config.openaiModel);
		body.addProperty("max_completion_tokens", this.config.maxTokens);

		JsonArray messages = new JsonArray();
		messages.add(chatMessage("system", systemPrompt));
		messages.add(chatMessage("user", userPrompt));
		body.add("messages", messages);

		JsonObject jsonSchema = new JsonObject();
		jsonSchema.addProperty("name", "wendigo_action_plan");
		jsonSchema.addProperty("strict", true);
		jsonSchema.add("schema", buildOpenAiSchema(schema));
		JsonObject responseFormat = new JsonObject();
		responseFormat.addProperty("type", "json_schema");
		responseFormat.add("json_schema", jsonSchema);
		body.add("response_format", responseFormat);

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(OPENAI_CHAT_COMPLETIONS_URL))
			.timeout(Duration.ofSeconds(this.config.requestTimeoutSeconds))
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body)))
			.build();

		return this.openAiHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.thenApply(this::parseOpenAiPlan);
	}

	private static JsonObject chatMessage(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private JsonObject parseOpenAiPlan(HttpResponse<String> response) {
		JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
		if (response.statusCode() != 200) {
			String message = body.has("error") && body.getAsJsonObject("error").has("message")
				? body.getAsJsonObject("error").get("message").getAsString()
				: response.body();
			throw new RuntimeException("OpenAI request failed (" + response.statusCode() + "): " + message);
		}

		JsonObject message = body.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
		if (message.has("refusal") && !message.get("refusal").isJsonNull()) {
			throw new RuntimeException("GPT-4o declined the request (refusal): " + message.get("refusal").getAsString());
		}
		if (!message.has("content") || message.get("content").isJsonNull()) {
			throw new RuntimeException("No content in OpenAI's response");
		}
		return JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
	}
}
