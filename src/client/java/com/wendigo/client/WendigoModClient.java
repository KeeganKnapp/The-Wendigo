package com.wendigo.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import com.wendigo.entity.ModEntities;

public class WendigoModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Every registered EntityType needs a renderer bound to it client-side or
		// EntityRenderDispatcher NPEs the moment one comes into view -- doesn't matter that
		// WendigoEntity is always invisible, the lookup itself must not return null.
		//
		// Reusing vanilla's EndermanRenderer here initially backfired: Enderman has a
		// glowing-eyes render layer that deliberately ignores invisibility, so the eyes stayed
		// visible. WendigoRenderer is a genuine no-op instead.
		//
		// This registration only matters when THIS mod is installed client-side (e.g. this
		// dev-test loop). A genuinely vanilla client has no idea what entity type
		// "wendigo:wendigo" even is -- for that case Polymer needs to be told to present it
		// as a real vanilla EntityType over the wire (PolymerEntity), which isn't wired up
		// yet. Worth doing as a follow-up before this is tested with an actual unmodified
		// client rather than this same-mod dev loop.
		EntityRendererRegistry.register(ModEntities.WENDIGO, WendigoRenderer::new);
	}
}
