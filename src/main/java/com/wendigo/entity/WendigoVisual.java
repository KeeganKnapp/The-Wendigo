package com.wendigo.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;

import net.minecraft.core.component.DataComponents; // TODO verify against this MC version's generated mappings
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items; // TODO verify against this MC version's generated mappings
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import com.wendigo.WendigoMod;

/**
 * A Polymer virtual-entity rig: one packet-only {@code item_display} per bone, mirroring the
 * Animated-Java-exported wendigo rig (see WendigoAnimationData). Attached to a real, invisible
 * {@link WendigoEntity} via {@code EntityAttachment.ofTicking} -- see WendigoMod's
 * ServerEntityEvents hooks for the attach/detach wiring.
 *
 * Every bone is given zero offset from the holder's tracked position, same as the reference
 * datapack: there all 12 bones were literal vehicle Passengers of one root item_display (so their
 * world position always equals the root's), and all visual placement came entirely from each
 * bone's own "transformation" NBT. We reproduce that exactly: offset stays default (0,0,0), and
 * setTransformation() does all the work.
 */
public class WendigoVisual extends ElementHolder {
    // The real entity's own yaw is driven directly by pathfinding/move-control snapping toward the
    // next path node every tick (no LookAtGoal/RandomLookAroundGoal-style smoothing, since it has no
    // Goals beyond FloatGoal) -- reading it raw made the rig visibly jerky. Easing toward it instead
    // of copying it verbatim smooths that out; Mth.rotLerp already takes the shortest way around.
    private static final float YAW_LERP_FACTOR = 0.35f;

    private final WendigoEntity owner;
    private final Map<String, ItemDisplayElement> bones = new LinkedHashMap<>();
    // Not one of the animated bones in WendigoAnimationData (that file is generated from the
    // Animated-Java export and isn't meant to be hand-edited) -- instead this just mirrors "head"'s
    // transformation every tick, at the exact same size/rotation, but with the glowing-eyes texture
    // and its own independent brightness override driven by WendigoEntity#isStaring. Its model
    // geometry is a hair larger than the head's own cube (see eyes.json) so the two coincident
    // item-display layers don't z-fight where they overlap.
    private final ItemDisplayElement eyes = new ItemDisplayElement();
    private int animationTick = 0;
    private float smoothedYaw;
    private boolean yawInitialized;

    public WendigoVisual(WendigoEntity owner) {
        this.owner = owner;
        for (String bone : WendigoAnimationData.REST_POSE.keySet()) {
            ItemDisplayElement element = new ItemDisplayElement();

            ItemStack stack = new ItemStack(Items.WHITE_DYE);
            stack.set(DataComponents.ITEM_MODEL, WendigoMod.id("blueprint/wendigo/" + bone));
            element.setItem(stack);
            element.setItemDisplayContext(ItemDisplayContext.HEAD);

            element.setInterpolationDuration(1);
            element.setTransformation(RigMatrices.fromRowMajor(WendigoAnimationData.REST_POSE.get(bone)));

            this.bones.put(bone, element);
            this.addElement(element);
        }

        ItemStack eyeStack = new ItemStack(Items.WHITE_DYE);
        eyeStack.set(DataComponents.ITEM_MODEL, WendigoMod.id("blueprint/wendigo/eyes"));
        this.eyes.setItem(eyeStack);
        this.eyes.setItemDisplayContext(ItemDisplayContext.HEAD);
        this.eyes.setInterpolationDuration(1);
        this.eyes.setTransformation(RigMatrices.fromRowMajor(WendigoAnimationData.REST_POSE.get("head")));
        this.addElement(this.eyes);
    }

    @Override
    protected void onTick() {
        // Shared with WendigoEntity's hitbox-pose switch (same underlying movement check) so the
        // crawl animation and the crawling hitbox never drift out of sync with each other.
        boolean moving = this.owner.isCrawling();
        // The reference datapack kept every bone's facing in sync with the root each tick
        // (root/on_tick.mcfunction: "execute on passengers run rotate @s ~ ~") -- our bones are
        // independent virtual elements with their own yaw, so we have to do that sync ourselves
        // or the rig stays facing whatever direction it last had regardless of which way the
        // real (invisible) entity turns.
        float targetYaw = this.owner.getYRot();
        if (!this.yawInitialized) {
            this.smoothedYaw = targetYaw;
            this.yawInitialized = true;
        } else {
            this.smoothedYaw = Mth.rotLerp(YAW_LERP_FACTOR, this.smoothedYaw, targetYaw);
        }
        float yaw = this.smoothedYaw;

        if (moving) {
            int frame = this.animationTick % WendigoAnimationData.CRAWL_FRAME_COUNT;
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = WendigoAnimationData.CRAWL.get(entry.getKey())[frame];
                entry.getValue().setTransformation(RigMatrices.fromRowMajor(matrix));
                entry.getValue().setYaw(yaw);
                entry.getValue().startInterpolationIfDirty();
            }
            applyEyes(WendigoAnimationData.CRAWL.get("head")[frame], yaw);
            this.animationTick++;
        } else {
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = WendigoAnimationData.REST_POSE.get(entry.getKey());
                entry.getValue().setTransformation(RigMatrices.fromRowMajor(matrix));
                entry.getValue().setYaw(yaw);
                entry.getValue().startInterpolationIfDirty();
            }
            applyEyes(WendigoAnimationData.REST_POSE.get("head"), yaw);
            this.animationTick = 0;
        }
    }

    /** Keeps the eyes overlay locked to the head bone's own transform every frame, in either pose. */
    private void applyEyes(float[] headMatrix, float yaw) {
        this.eyes.setTransformation(RigMatrices.fromRowMajor(headMatrix));
        this.eyes.setYaw(yaw);
        // Only actually visible-in-the-dark while the entity is mid-stare (see WendigoEntity#isStaring)
        // -- null resets to natural lighting, same as everywhere else on the rig, the rest of the time.
        this.eyes.setBrightness(this.owner.isStaring() ? Brightness.FULL_BRIGHT : null);
        this.eyes.startInterpolationIfDirty();
    }
}
