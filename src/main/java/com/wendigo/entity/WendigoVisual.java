package com.wendigo.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;

import net.minecraft.core.component.DataComponents; // TODO verify against this MC version's generated mappings
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items; // TODO verify against this MC version's generated mappings
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import com.wendigo.WendigoMod;
import com.wendigo.debug.WendigoDebug;

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
    private static final double LOOK_AT_PLAYER_RADIUS = 64.0;
    // Bright, unmistakably-artificial cyan - distinct from anything else the rig or the cave itself
    // could plausibly tint, so it reads as "debug indicator" at a glance.
    private static final int DEBUG_GLOW_COLOR = 0x00FFFF;

    // Mirrors SemanticBands.speedMultiplier's slow/normal/fast values (com.wendigo.plan, package-
    // private - a plan-schema concern, not an animation one) - duplicated here rather than widening
    // that class's visibility for this, same tradeoff already accepted for
    // SemanticBands.DARKNESS_LIGHT_THRESHOLD vs DarkSpotScanner's own darkness cutoff. Used only to
    // classify the entity's actual live speed into a band below, not to drive movement itself.
    private static final double SLOW_MOVE_MULTIPLIER = 1.25;
    private static final double NORMAL_MOVE_MULTIPLIER = 1.5;
    private static final double FAST_MOVE_MULTIPLIER = 2.0;

    // How much faster than real-time the crawl loop plays back at each band - even the slowest
    // moving speed still doubles it, so the limb cycle never reads as visibly skating/sliding across
    // the ground the way 1x playback did against real movement speed. First-pass, adjust by feel.
    private static final double SLOW_ANIMATION_SPEED = 2.0;
    private static final double NORMAL_ANIMATION_SPEED = 2.5;
    private static final double FAST_ANIMATION_SPEED = 3.0;

    private final WendigoEntity owner;
    private final Map<String, ItemDisplayElement> bones = new LinkedHashMap<>();
    // Not one of the animated bones in WendigoAnimationData (that file is generated from the
    // Animated-Java export and isn't meant to be hand-edited) -- instead this just mirrors "head"'s
    // transformation every tick, at the exact same size/rotation, but with the glowing-eyes texture
    // and its own independent brightness override driven by WendigoEntity#isStaring. Its model
    // geometry is a hair larger than the head's own cube (see eyes.json) so the two coincident
    // item-display layers don't z-fight where they overlap.
    private final ItemDisplayElement eyes = new ItemDisplayElement();
    // A fractional frame accumulator rather than an int frame-index -- advancing by a non-integer
    // multiplier (2.5x, etc) every tick needs the fractional remainder to carry over, or the loop's
    // real-time speed would round back down toward 1x.
    private double animationPhase = 0.0;
    private float smoothedYaw;
    private boolean yawInitialized;
    private boolean debugGlowing;

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
        applyDebugGlow();
        // Three visual states, matched to the entity's real movement/hitbox state rather than just
        // hitbox alone: actually moving (regardless of pose) plays the looping crawl animation;
        // stopped but still in the crawl/swim hitbox (too cramped to stand, or between crawl moves)
        // holds the single crawl-idle pose instead of snapping back to a standing rest pose that
        // wouldn't fit the space; stopped and in the standing hitbox uses the plain rest pose.
        boolean moving = this.owner.isMoving();
        boolean crawlHitbox = this.owner.isCrawling();
        // The reference datapack kept every bone's facing in sync with the root each tick
        // (root/on_tick.mcfunction: "execute on passengers run rotate @s ~ ~") -- our bones are
        // independent virtual elements with their own yaw, so we have to do that sync ourselves
        // or the rig stays facing whatever direction it last had regardless of which way the
        // real (invisible) entity turns.
        // While staring, the whole rig turns to face the nearest player instead of following
        // movement - not just the head. Each bone's transformation matrix bakes in its own
        // translation offset from the rig's shared anchor point (see the class doc), and that
        // translation gets rotated along with whatever yaw is applied to that element; giving only
        // the head bone its own yaw rotated its *position* toward the player as well as its facing,
        // which reads as the head detaching and poking forward off the neck the further it turned.
        // Turning every bone together keeps the whole rig internally consistent - still doesn't
        // touch the real entity's yaw/LookControl, that stays driven purely by movement/pathing.
        float targetYaw = this.owner.isStaring() ? lookAtPlayerYaw() : this.owner.getYRot();
        if (!this.yawInitialized) {
            this.smoothedYaw = targetYaw;
            this.yawInitialized = true;
        } else {
            this.smoothedYaw = Mth.rotLerp(YAW_LERP_FACTOR, this.smoothedYaw, targetYaw);
        }
        float yaw = this.smoothedYaw;

        if (moving) {
            int frame = ((int) this.animationPhase) % WendigoAnimationData.CRAWL_FRAME_COUNT;
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = WendigoAnimationData.CRAWL.get(entry.getKey())[frame];
                entry.getValue().setTransformation(RigMatrices.fromRowMajor(matrix));
                entry.getValue().setYaw(yaw);
                entry.getValue().startInterpolationIfDirty();
            }
            applyEyes(WendigoAnimationData.CRAWL.get("head")[frame], yaw);
            this.animationPhase += animationSpeedMultiplier();
        } else {
            Map<String, float[]> pose = crawlHitbox ? WendigoAnimationData.CRAWL_IDLE : WendigoAnimationData.REST_POSE;
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = pose.get(entry.getKey());
                entry.getValue().setTransformation(RigMatrices.fromRowMajor(matrix));
                entry.getValue().setYaw(yaw);
                entry.getValue().startInterpolationIfDirty();
            }
            applyEyes(pose.get("head"), yaw);
            this.animationPhase = 0.0;
        }
    }

    /** Buckets the entity's actual current speed against its own base MOVEMENT_SPEED attribute,
     * scaled by the same slow/normal/fast multipliers SemanticBands.speedMultiplier hands to
     * navigation.moveTo - so the crawl loop's real-time playback rate tracks how fast the wendigo is
     * genuinely moving right now, regardless of which plan primitive (or hardcoded chase multiplier)
     * is currently driving it. Boundaries are the midpoints between adjacent bands; below the
     * slow/normal midpoint still gets the slow-band (floor) speed, not something slower. */
    private double animationSpeedMultiplier() {
        double baseSpeed = this.owner.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (baseSpeed <= 0.0) {
            return NORMAL_ANIMATION_SPEED;
        }
        double speed = this.owner.horizontalSpeed();
        double slowNormalBoundary = baseSpeed * (SLOW_MOVE_MULTIPLIER + NORMAL_MOVE_MULTIPLIER) / 2.0;
        double normalFastBoundary = baseSpeed * (NORMAL_MOVE_MULTIPLIER + FAST_MOVE_MULTIPLIER) / 2.0;
        if (speed >= normalFastBoundary) {
            return FAST_ANIMATION_SPEED;
        }
        if (speed >= slowNormalBoundary) {
            return NORMAL_ANIMATION_SPEED;
        }
        return SLOW_ANIMATION_SPEED;
    }

    /**
     * The wendigo is otherwise entirely invisible-by-design (see WendigoEntity#isInvisible) - normally
     * the whole point, but a nuisance while debugging (can't see it coming, can't see where it actually
     * ended up relative to a scanned spot). Polymer's item_display elements support the same
     * client-rendered "glowing" outline any vanilla entity does (GenericEntityElement#setGlowing,
     * DisplayElement#setGlowColorOverride - both verified present via javap against the mapped Polymer
     * jar) - through walls, no different from vanilla's Glowing status effect/spectral arrow. Not
     * per-viewer (Polymer's tracked entity data is shared across every watcher of this element, there's
     * no per-player override hook for it): the whole rig glows for everyone in the level whenever ANY
     * player currently has /wendigo debug enabled, same "shared once someone's debugging" scope as the
     * existing scanned-spot particles/chat commentary rather than a true per-viewer effect. Only touches
     * the elements when the debug state actually flips, not every tick.
     */
    private void applyDebugGlow() {
        boolean glowing = WendigoDebug.anyEnabled();
        if (glowing == this.debugGlowing) {
            return;
        }
        this.debugGlowing = glowing;
        for (ItemDisplayElement bone : this.bones.values()) {
            bone.setGlowing(glowing);
            if (glowing) {
                bone.setGlowColorOverride(DEBUG_GLOW_COLOR);
            }
        }
        this.eyes.setGlowing(glowing);
        if (glowing) {
            this.eyes.setGlowColorOverride(DEBUG_GLOW_COLOR);
        }
    }

    /** Yaw facing the nearest player, or the body's own yaw if none is found nearby. */
    private float lookAtPlayerYaw() {
        Player player = this.owner.level().getNearestPlayer(this.owner, LOOK_AT_PLAYER_RADIUS);
        if (player == null) {
            return this.owner.getYRot();
        }
        double dx = player.getX() - this.owner.getX();
        double dz = player.getZ() - this.owner.getZ();
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
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
