package com.wendigo.wave;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import com.wendigo.WendigoMod;
import com.wendigo.plan.SchemaBuilder;

/**
 * Standalone LLM-plan convergence research harness. Deliberately NOT listed in
 * src/gametest/resources/fabric.mod.json's "fabric-gametest" entrypoint array, so it never runs as
 * part of the normal ./gradlew build/runGameTest suite - it makes real, real-money calls to
 * WendigoMod.llmClient (the same OpenAI account every other real request uses), which is not
 * something every ordinary build should pay for. To actually run it: temporarily add
 * "com.wendigo.gametest.wave.WendigoConvergenceTests" back into that entrypoints array, run
 * ./gradlew runGameTest, then revert the entrypoints file back out again.
 *
 * The user's own explicit request: sample the model's real output across a scenario matrix (right
 * now just severity stage x whether any torches sit nearby) and log every returned plan, to check
 * whether it converges toward one common shape per stage instead of actually using the range of
 * tools the schema/prompt offer at that stage. Deliberately narrow in scope for a first pass - see
 * this class's own trailing comment for what a follow-up pass could add (cave scale, an already-
 * engaged entity's current-position/playerDirection context, encounter-history recap).
 *
 * Talks to WendigoManager.buildContext/buildSystemPrompt directly (both package-private, widened
 * from private specifically for this reuse - see their own doc comments) rather than driving a full
 * forceWave()-triggered spawn/plan/despawn lifecycle: this is testing what the MODEL returns given a
 * specific context, not the engine's own execution of it (PlanRunner/WendigoEntity), so there's no
 * need to actually spawn a WendigoEntity or wait out a full encounter per trial - just build the
 * exact same prompt+schema a real wave request would send, and ask.
 */
public final class WendigoConvergenceTests {
	private static final int TRIALS_PER_SCENARIO = 3;
	// completedRuns values that WendigoProgressionTracker.stageFor maps to stages 1-5, whose
	// representative percents are 10/30/50/70/90 (see WendigoProgressionTracker.STAGE_PERCENTS).
	private static final int[] STAGE_REPRESENTATIVE_RUNS = {0, 2, 3, 4, 5};

	private List<Trial> trials;
	private int trialIndex;
	private CompletableFuture<JsonObject> pending;
	private final List<String> resultLines = new ArrayList<>();

	private static final class Trial {
		final int stageIndex;
		final boolean torchesPresent;
		int percent;

		Trial(int stageIndex, boolean torchesPresent) {
			this.stageIndex = stageIndex;
			this.torchesPresent = torchesPresent;
		}
	}

	@GameTest(padding = 20, maxTicks = 12000)
	public void sampleAcrossStagesAndTorchPresence(GameTestHelper helper) {
		buildRoom(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = makeSurvivalMockPlayer(helper);
		BlockPos playerAbsolute = helper.absolutePos(new BlockPos(3, 1, 3));
		player.teleportTo(playerAbsolute.getX() + 0.5, playerAbsolute.getY(), playerAbsolute.getZ() + 0.5);

		WendigoWaveConfig config = new WendigoWaveConfig();
		WendigoProgressionTracker tracker = new WendigoProgressionTracker();
		EncounterHistory history = new EncounterHistory();
		WendigoManager manager = new WendigoManager(config, tracker, history);

		this.trials = new ArrayList<>();
		for (int stageIndex = 0; stageIndex < STAGE_REPRESENTATIVE_RUNS.length; stageIndex++) {
			for (boolean torchesPresent : new boolean[] {false, true}) {
				for (int i = 0; i < TRIALS_PER_SCENARIO; i++) {
					this.trials.add(new Trial(stageIndex, torchesPresent));
				}
			}
		}
		this.trialIndex = 0;
		this.pending = null;
		this.resultLines.clear();
		WendigoMod.LOGGER.info("[convergence] starting {} trials", this.trials.size());

		helper.succeedWhen(() -> driveTrials(helper, level, player, manager, tracker));
	}

	private void driveTrials(GameTestHelper helper, ServerLevel level, ServerPlayer player,
			WendigoManager manager, WendigoProgressionTracker tracker) {
		if (this.trialIndex >= this.trials.size()) {
			WendigoMod.LOGGER.info("[convergence] === {} trials complete ===", this.trials.size());
			for (String line : this.resultLines) {
				WendigoMod.LOGGER.info(line);
			}
			return; // succeedWhen treats a clean return (no exception) as success
		}
		Trial trial = this.trials.get(this.trialIndex);
		if (this.pending == null) {
			tracker.setRunsForTesting(player, STAGE_REPRESENTATIVE_RUNS[trial.stageIndex]);
			trial.percent = tracker.percentOf(player);
			setTorches(helper, trial.torchesPresent);
			WaveContext context = manager.buildContext(level, player, trial.percent, null);
			String systemPrompt = WendigoManager.buildSystemPrompt(trial.percent);
			JsonObject schema = SchemaBuilder.forSeverity(trial.percent, context.caveScale());
			this.pending = WendigoMod.llmClient.requestPlan(systemPrompt, context.toPromptText(), schema);
			WendigoMod.LOGGER.info("[convergence] trial {}/{} launched: percent={} torches={}",
				this.trialIndex + 1, this.trials.size(), trial.percent, trial.torchesPresent);
		}
		helper.assertTrue(this.pending.isDone(), "still waiting on trial " + (this.trialIndex + 1)
			+ "/" + this.trials.size());
		try {
			JsonObject plan = this.pending.join();
			this.resultLines.add(formatResult(trial, plan));
		} catch (Exception e) {
			this.resultLines.add("[convergence] trial " + (this.trialIndex + 1) + " FAILED percent="
				+ trial.percent + " torches=" + trial.torchesPresent + " error=" + e);
		}
		this.pending = null;
		this.trialIndex++;
		helper.assertTrue(false, "advancing to trial " + (this.trialIndex + 1) + "/" + this.trials.size());
	}

	private static String formatResult(Trial trial, JsonObject plan) {
		JsonArray steps = plan.getAsJsonArray("plan");
		JsonArray globalRules = plan.has("global_rules") ? plan.getAsJsonArray("global_rules") : new JsonArray();
		List<String> globalRuleActions = new ArrayList<>();
		for (JsonElement rule : globalRules) {
			globalRuleActions.add(rule.getAsJsonObject().getAsJsonObject("action").get("type").getAsString());
		}
		return "[convergence] percent=" + trial.percent + " torches=" + trial.torchesPresent
			+ " global_rules=" + globalRuleActions
			+ " plan=" + String.join(" -> ", flattenStepTypes(steps));
	}

	/** Step types in execution order, with control.if/control.while's own nested branches/body
	 * inlined as bracketed sub-lists rather than just "control.if"/"control.while" on their own -
	 * the whole point of this harness is seeing what the model actually reaches for, and a bare
	 * "control.if" hides exactly the part that would answer that. */
	private static List<String> flattenStepTypes(JsonArray steps) {
		List<String> types = new ArrayList<>();
		for (JsonElement element : steps) {
			JsonObject step = element.getAsJsonObject();
			String type = step.get("type").getAsString();
			if (type.equals("control.if")) {
				String thenPart = String.join(",", flattenStepTypes(step.getAsJsonArray("then")));
				JsonElement elseElement = step.get("else");
				String elsePart = elseElement != null && !elseElement.isJsonNull()
					? String.join(",", flattenStepTypes(elseElement.getAsJsonArray())) : "";
				types.add("control.if(then=[" + thenPart + "], else=[" + elsePart + "])");
			} else if (type.equals("control.while")) {
				String bodyPart = String.join(",", flattenStepTypes(step.getAsJsonArray("body")));
				types.add("control.while(body=[" + bodyPart + "])");
			} else if (step.has("destination")) {
				// combat.teleport/movement.approach_spot - the whole point of this harness is seeing
				// which destination TYPE the model reaches for, not just that it used the action.
				types.add(type + "(" + step.get("destination").getAsString() + ")");
			} else {
				types.add(type);
			}
		}
		return types;
	}

	// Fixed spots well inside the room's own close_as_possible band (0-4 blocks from the player at
	// (3,1,3)) - always overwritten with either a real torch or air each trial, so torch presence is
	// the only thing that changes between the two variants, not room geometry.
	private static final BlockPos[] TORCH_SPOTS = {
		new BlockPos(1, 1, 1), new BlockPos(5, 1, 1), new BlockPos(1, 1, 5),
	};

	private static void setTorches(GameTestHelper helper, boolean present) {
		for (BlockPos spot : TORCH_SPOTS) {
			helper.setBlock(spot, present ? Blocks.TORCH : Blocks.AIR);
		}
	}

	// Same 7x7x9 sealed-room shape WendigoGameTests.java's own room-building tests already use.
	private static void buildRoom(GameTestHelper helper) {
		int roomHeight = 9;
		for (int x = 0; x <= 6; x++) {
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, roomHeight, z), Blocks.STONE);
			}
		}
		for (int y = 1; y < roomHeight; y++) {
			for (int x = 0; x <= 6; x++) {
				helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
				helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
			}
			for (int z = 0; z <= 6; z++) {
				helper.setBlock(new BlockPos(0, y, z), Blocks.STONE);
				helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
			}
		}
	}

	// Copied from WendigoGameTests.makeSurvivalMockPlayer (private there, not reachable across
	// classes even same-package-adjacent - see that method's own doc comment for why this exact
	// sequence, not helper.makeMockServerPlayerInLevel(), is needed: that one hardcodes
	// GameType.CREATIVE unconditionally).
	private static ServerPlayer makeSurvivalMockPlayer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		return player;
	}
}

// Follow-up scenario axes not covered by this first pass, left for a later pass if the first pass's
// own findings warrant it (see the user's own "you don't have to write the tests all at once"):
// cave scale (needs a much bigger structure than this one's 7x7 room to reach the far/farther/
// farthest torch bands or CaveScaleScanner's own MASSIVE threshold), an already-engaged entity's
// current-position context (isPlayerLookingAtSelf/onWallOrCeiling/playerDirection - only ever
// populated for engageExistingWendigo's own path, needs a real spawned+orbiting WendigoEntity
// first), and encounter-history recap variation (seed EncounterHistory.record with a synthetic
// PlanRunner.EncounterOutcome before each trial in a given scenario group).
