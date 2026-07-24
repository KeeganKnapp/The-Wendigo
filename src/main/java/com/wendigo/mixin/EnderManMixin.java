package com.wendigo.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;

import com.wendigo.entity.WendigoEntity;

/**
 * EnderMan.aiStep() spawns its ambient portal-swirl particles unconditionally on the client side,
 * inlined directly in the method body with no overridable hook (confirmed via bytecode disassembly
 * -- there's no separate "spawn ambient particle" method to override, and getDimensions-style
 * overrides don't apply here). This is the one and only Level.addParticle call in the class, so a
 * targeted redirect is safe: real vanilla Endermen still get their particles, only WendigoEntity
 * (which reuses EnderMan for AI/behavior but has entirely separate, Polymer-driven visuals) is
 * suppressed.
 */
@Mixin(EnderMan.class)
public class EnderManMixin {
    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
            )
    )
    private void wendigo$suppressPortalParticles(
            Level level, ParticleOptions type, double x, double y, double z, double dx, double dy, double dz
    ) {
        if ((Object) this instanceof WendigoEntity) {
            return;
        }
        level.addParticle(type, x, y, z, dx, dy, dz);
    }
}
