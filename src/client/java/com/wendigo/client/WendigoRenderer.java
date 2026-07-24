package com.wendigo.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import com.wendigo.entity.WendigoEntity;

/**
 * A genuine no-op renderer -- draws nothing at all. Needed because reusing vanilla's
 * {@code EndermanRenderer} (just to satisfy Fabric's "every EntityType needs a bound renderer or
 * the dispatcher NPEs" requirement) backfired: Enderman (and Spider) have a dedicated glowing-eyes
 * render layer that's deliberately drawn *through* invisibility, so the eyes stayed visible even
 * with WendigoEntity.isInvisible() always true. Extending the base {@link EntityRenderer} directly
 * (not MobRenderer/LivingEntityRenderer, which carry all the feature-layer machinery) and never
 * overriding {@code submit(...)} means there is nothing left to draw, eyes included.
 */
public class WendigoRenderer extends EntityRenderer<WendigoEntity, EntityRenderState> {
    public WendigoRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
