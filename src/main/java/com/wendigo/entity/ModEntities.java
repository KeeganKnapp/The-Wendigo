package com.wendigo.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;

import com.wendigo.WendigoMod;

public final class ModEntities {
    private static final ResourceKey<EntityType<?>> WENDIGO_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, WendigoMod.id("wendigo"));

    // Matches WendigoEntity's overridden STANDING dimensions (not vanilla Enderman's real 2.9
    // height -- see WendigoEntity's comment on that override for why: pathfinding needs the
    // entity to reliably fit through normal 2-3 tall spaces, not just while crawling). Fabric's
    // own FabricEntityTypeBuilder is deprecated in this API version in favor of building directly
    // off vanilla EntityType.Builder, which now has everything (sizing, tracking range, etc.)
    // natively.
    public static final EntityType<WendigoEntity> WENDIGO = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            WENDIGO_KEY,
            EntityType.Builder.of(WendigoEntity::new, MobCategory.MONSTER)
                    .sized(0.9F, 1.95F)
                    .build(WENDIGO_KEY)
    );

    private ModEntities() {}

    public static void init() {
        // A brand-new EntityType has no attribute supplier of its own -- without this,
        // LivingEntity's constructor NPEs on this.supplier the moment one is summoned
        // (getMaxHealth() etc. have nothing to read from). Reuse Enderman's attribute set
        // since WendigoEntity keeps its stats/combat behavior unchanged, just with its own max
        // health (50, up from Enderman's vanilla 40 - user-specified, tune by feel) so the new
        // spear-defense damage (see PlanRunner.repelWithSpear) has real room to matter across a
        // few hits instead of one or two draining the whole thing.
        FabricDefaultAttributeRegistry.register(WENDIGO,
            EnderMan.createAttributes().add(Attributes.MAX_HEALTH, 50.0));
    }
}
