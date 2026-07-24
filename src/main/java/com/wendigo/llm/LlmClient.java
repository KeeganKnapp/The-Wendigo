package com.wendigo.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * Async wrapper around the Claude API, used to request one wendigo decision plan per call.
 * The SDK's messages().create(...) is blocking, so every call runs on its own executor, off the
 * server tick thread - callers must hop back via MinecraftServer.execute(...) before touching
 * entity/world state with the result.
 */
public class LlmClient {
	private final LlmConfig config;
	private final AnthropicClient client;
	private final JsonOutputFormat.Schema actionSchema;
	private final Gson gson = new Gson();

	// Blocking SDK calls each need their own thread; there's only ever a handful of decision
	// requests in flight at once (one per active wendigo), so an unbounded cached pool is fine.
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "wendigo-llm-request");
		t.setDaemon(true);
		return t;
	});

	public LlmClient(LlmConfig config) {
		this.config = config;
		this.client = AnthropicOkHttpClient.builder()
			.fromEnv()
			.timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
			.build();
		this.actionSchema = loadSchema();
	}

	private static JsonOutputFormat.Schema loadSchema() {
		String schemaJson;
		try (InputStream in = LlmClient.class.getResourceAsStream("/llm/action_schema.json")) {
			if (in == null) {
				throw new IllegalStateException("Missing bundled resource /llm/action_schema.json");
			}
			schemaJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException("Failed to load action schema resource", e);
		}

		// JsonOutputFormat.Schema has no typed fields of its own - it's a freeform bag of
		// additionalProperties, so the whole schema is rebuilt one top-level key at a time.
		try {
			JsonNode root = new ObjectMapper().readTree(schemaJson);
			JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
			Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				builder.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue()));
			}
			return builder.build();
		} catch (IOException e) {
			throw new RuntimeException("Failed to parse action schema resource as JSON", e);
		}
	}

	/**
	 * Asks the model for one decision turn, constrained to the action_schema.json shape.
	 * Completes with the parsed action-plan JSON, or completes exceptionally on a refusal or
	 * malformed output.
	 */
	public CompletableFuture<JsonObject> requestPlan(String systemPrompt, String userPrompt) {
		JsonOutputFormat format = JsonOutputFormat.builder().schema(actionSchema).build();
		OutputConfig outputConfig = OutputConfig.builder().format(format).build();

		MessageCreateParams params = MessageCreateParams.builder()
			.model(config.model)
			.maxTokens(config.maxTokens)
			.system(systemPrompt)
			.addUserMessage(userPrompt)
			.outputConfig(outputConfig)
			.build();

		return CompletableFuture.supplyAsync(() -> parsePlan(client.messages().create(params)), executor);
	}

	private JsonObject parsePlan(Message response) {
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
}
