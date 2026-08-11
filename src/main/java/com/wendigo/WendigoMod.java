package com.wendigo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wendigo.block.WendigoBlocks;
import com.wendigo.command.WendigoCommands;
import com.wendigo.debug.SpiderDiagnostics;
import com.wendigo.debug.StareHeadTest;
import com.wendigo.debug.WendigoDebugItems;
import com.wendigo.entity.ModEntities;
import com.wendigo.entity.WendigoEntity;
import com.wendigo.entity.WendigoVisual;
import com.wendigo.llm.LlmClient;
import com.wendigo.llm.LlmConfig;
import com.wendigo.sound.WendigoSounds;
import com.wendigo.wave.DarknessOverstayTracker;
import com.wendigo.wave.EncounterHistory;
import com.wendigo.wave.WendigoManager;
import com.wendigo.wave.WendigoProgressionTracker;
import com.wendigo.wave.WendigoWaveConfig;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;

public class WendigoMod implements ModInitializer {
	public static final String MOD_ID = "wendigo";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Exposed statically so future primitive/trigger code can call requestPlan(...) without
	// threading a reference through every entity - same pattern as the visual rig getters.
	public static LlmClient llmClient;
	// Exposed for WendigoCommands' /wendigo wave debug command, which needs to bypass the
	// manager's normal cooldown/eligibility gating.
	public static WendigoManager wendigoManager;
	// Exposed for WendigoCommands' /wendigo runs get/set debug commands.
	public static WendigoProgressionTracker progressionTracker;
	// See WendigoTuningConfig's own class doc comment - read directly from here (a shared static
	// instance, same "loaded once at mod init" shape as the fields above) rather than threaded through
	// PlanRunner's own constructor, which has no existing config wiring at all.
	public static WendigoTuningConfig tuningConfig;

	@Override
	public void onInitialize() {
		LOGGER.info("Wendigo mod initializing");
		ModEntities.init();
		WendigoBlocks.init();
		WendigoSounds.init();
		WendigoDebugItems.init();
		SpiderDiagnostics.init();
		StareHeadTest.register();

		// Marks this mod's own src/main/resources/assets/wendigo (the unpacked AJ resource
		// pack export) as resource-pack asset source, so Polymer's AutoHost merges and pushes
		// it to joining vanilla clients -- no client mod or manual resource pack install needed.
		PolymerResourcePackUtils.addModAssets(MOD_ID);
		// Without the rig's item models, players see nothing where the wendigo should be --
		// require the pack rather than let it be silently declined.
		PolymerResourcePackUtils.markAsRequired();

		// Spawn/tear down the virtual item-display rig alongside the real (invisible) entity.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof WendigoEntity wendigo) {
				WendigoVisual visual = new WendigoVisual(wendigo);
				wendigo.setVisual(visual);
				EntityAttachment.ofTicking(visual, wendigo);
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof WendigoEntity wendigo && wendigo.getVisual() != null) {
				wendigo.getVisual().destroy();
			}
		});

		llmClient = new LlmClient(LlmConfig.load());
		tuningConfig = WendigoTuningConfig.load();

		WendigoWaveConfig waveConfig = WendigoWaveConfig.load();
		progressionTracker = new WendigoProgressionTracker();
		progressionTracker.register();
		wendigoManager = new WendigoManager(waveConfig, progressionTracker, new EncounterHistory());
		wendigoManager.register();
		new DarknessOverstayTracker(progressionTracker, wendigoManager).register();

		WendigoCommands.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
