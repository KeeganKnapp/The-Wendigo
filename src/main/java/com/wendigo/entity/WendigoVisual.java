package com.wendigo.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;

import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents; // TODO verify against this MC version's generated mappings
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items; // TODO verify against this MC version's generated mappings
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.nyfaria.awcapi.entity.Orientation;

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
    private static final float YAW_LERP_FACTOR = 0.4f;
    // Same easing treatment as YAW_LERP_FACTOR, for the same reason - a wall/ceiling attachment
    // orientation swap (see onTick's rigOrientation branch) is physics-driven, not eased on
    // AWCAPI's own side, and popping straight to a new orientation the instant ClimberComponent
    // decides the mob has attached to a new face would read just as jerky as raw yaw did. Lower
    // than YAW_LERP_FACTOR's own value on purpose - live testing on natural (irregular, non-flat)
    // stone specifically showed the rig visibly snapping back and forth between orientations, not
    // just a single jerky transition - the attachment normal itself is presumably oscillating tick
    // to tick as ClimberComponent's own collision-smoothing search finds a slightly different
    // nearest point on bumpy terrain each time, something this file has no control over. A
    // single-pole lerp toward a genuinely oscillating target can only ever reduce the oscillation's
    // amplitude, never eliminate it outright, but slowing convergence further meaningfully reduces
    // how far it swings each tick. (A windowed-average alternative was tried for the flip-flopping
    // "upside-down pyramid" case specifically - reverted back to this by request.) First pass,
    // adjust by feel.
    private static final float ORIENTATION_LERP_FACTOR = 0.15f;
    // See onTick's rigOrientation block - empirically-found correction for the rig facing exactly
    // backwards while climbing, not derived from any formula. Was 180 while
    // RigMatrices.fromOrientationAndYaw's composition still had the yaw-dependent handedness bug
    // (see that method's own doc comment) - 180 was only really correcting a mix of that bug (which
    // needed different corrections at different yaw angles) and this genuine, uniform front/back
    // mismatch, and happened to net out close to 180 for the east/west cases that fix was originally
    // tuned against. Once the composition bug was fixed, live testing showed a uniform backwards
    // facing on every surface (all 4 cardinal walls and the ceiling alike) - confirming the
    // remaining error really is just this one constant, not a second yaw-dependent bug - so this
    // flips to 0 rather than needing another offset value.
    private static final float CLIMB_YAW_OFFSET_DEGREES = 0.6f;
    private static final double LOOK_AT_PLAYER_RADIUS = 64.0;
    // Empirical, same class of fix as CLIMB_YAW_OFFSET_DEGREES above but for the head bone instead
    // of the whole rig: live testing showed the crawl-pose head-look tracking the player's bearing
    // correctly (turning the right amount as the player moved) but facing exactly away from them, on
    // both the floor and while climbing - a clean 180 is exactly what a front/back mismatch in the
    // head bone's own baked "forward" produces, regardless of which yaw formula fed the delta.
    private static final float HEAD_LOOK_YAW_CORRECTION_DEGREES = 180.0f;
    // Bright, unmistakably-artificial cyan - distinct from anything else the rig or the cave itself
    // could plausibly tint, so it reads as "debug indicator" at a glance.
    private static final int DEBUG_GLOW_COLOR = 0x00FFFF;

    // This rig is a Polymer virtual entity (packet-only item_display elements), not a real
    // LivingEntity, so unlike a vanilla mob it gets NO free per-render-frame rotation interpolation
    // from a LivingEntityRenderer - the only client-side smoothing it ever gets is however many
    // ticks setTransformation's own interpolation window covers. Comparing against Stormy's Spiders
    // (a fork of this same AWCAPI, but rendered as a real entity - confirmed via decompiling both
    // jars) showed its climbing rotation looking far smoother at the same effective speed; its own
    // extra smoothing (a client-side low-pass filter, then AWCAPI's own
    // calculateOrientation(partialTick) interpolation) is inherently a per-render-frame,
    // real-entity-only mechanism that doesn't transplant onto a packet-driven virtual entity - but
    // stretching this element-level interpolation window is the direct Polymer/vanilla-Display
    // equivalent available to us.
    //
    // setTransformation bakes the CRAWL animation's own per-frame limb pose into the exact same
    // matrix as the rotation/orientation composition - Polymer has no way to interpolate one part of
    // a bone's transformation but not another, so this window smooths BOTH together, which is a
    // problem if it doesn't match the PUSH RATE: naively pushing a new transformation every single
    // tick while asking for a multi-tick interpolation window meant the client was ALWAYS still
    // mid-interpolation toward the PREVIOUS tick's target when a newer one replaced it (vanilla
    // Display-entity interpolation restarts from wherever it currently sits, not from the old
    // target) - undershooting the crawl animation's own fast limb swing far more visibly than it
    // ever undershot the rig's comparatively slow rotation. Live testing confirmed exactly that
    // (limbs barely moving) at both 3 and, less severely, 2.
    //
    // The actual fix (see onTick's "PUSH THROTTLE" section) was to stop pushing every tick and
    // instead only push once every this-many ticks, matching the push rate to the interpolation
    // window so each leg is a genuine two-keyframe tween that completes before the next one starts,
    // rather than a constantly-redirected chase. That decoupling is what makes this value safe to
    // tune more freely now (it no longer trades animation amplitude for rotation smoothness the way
    // it did before the throttle existed) - first pass, adjust by feel; higher values mean fewer,
    // bigger jumps per push (smoothed by interpolation, but a coarser sampling of the crawl loop's
    // own timing), lower values track both animation and rotation more responsively at the cost of
    // less interpolation runway.
    private static final int ROTATION_INTERPOLATION_TICKS = 0;

    // Mirrors SemanticBands.speedMultiplier's slow/normal/fast values (com.wendigo.plan, package-
    // private - a plan-schema concern, not an animation one) - duplicated here rather than widening
    // that class's visibility for this, same tradeoff already accepted for
    // SemanticBands.DARKNESS_LIGHT_THRESHOLD vs DarkSpotScanner's own darkness cutoff. Used only to
    // classify the entity's actual live speed into a band below, not to drive movement itself.
    private static final double SLOW_MOVE_MULTIPLIER = 1.25;
    private static final double NORMAL_MOVE_MULTIPLIER = 1.5;
    private static final double FAST_MOVE_MULTIPLIER = 1.75;

    // How much faster than real-time the crawl loop plays back at each band - even the slowest
    // moving speed still doubles it, so the limb cycle never reads as visibly skating/sliding across
    // the ground the way 1x playback did against real movement speed. First-pass, adjust by feel.
    private static final double SLOW_ANIMATION_SPEED = 3.0;
    private static final double NORMAL_ANIMATION_SPEED = 1.5;
    private static final double FAST_ANIMATION_SPEED = 4.0;

    // How many consecutive not-moving ticks owner.isMoving() must read before the ANIMATION commits
    // to idle-crawl instead of the crawl loop - see onTick's debouncedMoving. Only the "stopping"
    // direction is debounced (mirrors WendigoEntity's own asymmetric pose debounce) - starting to
    // move stays instant.
    private static final int MOVING_ANIMATION_DEBOUNCE_TICKS = 6;
    // Window isGenuinelyProgressing checks NET displacement over - owner.isMoving() is pure
    // instantaneous velocity, which a mob wedged in a tight/concave gap (still bumping/jittering
    // against the obstacle tick to tick, never fully at rest) can keep satisfying every tick despite
    // making zero real progress, which read as the crawl loop playing indefinitely while genuinely
    // stuck. Same technique as PlanRunner's own isMakingNoProgress (a snapshot-and-compare over a
    // real window, not a rolling average), but with a shorter fuse - purely cosmetic here (doesn't
    // give up on anything, just picks idle-crawl over the crawl loop), so it should react faster
    // than PlanRunner's own gameplay-facing give-up logic does.
    private static final int PROGRESS_CHECK_TICKS = 10; // ~0.75s
    private static final double PROGRESS_CHECK_DISTANCE_SQR = 0.01; // net displacement under ~0.1 blocks

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
    private final Quaternionf smoothedOrientation = new Quaternionf();
    private boolean orientationInitialized;
    private boolean debugGlowing;
    // Debounced view of owner.isMoving(), used only for ANIMATION selection below - NOT a
    // replacement for the raw signal, which pose/hitbox logic still needs unchanged. Climbing's
    // stepwise, discontinuous movement can make the raw velocity-based isMoving() flicker true/false
    // tick to tick, which without this would flap the crawl/idle-crawl item models rapidly instead of
    // holding the animated crawl loop the way ordinary floor movement does.
    private boolean debouncedMoving;
    private int notMovingStableTicks;
    // See isGenuinelyProgressing's own comment.
    private Vec3 progressCheckPosition;
    private int progressCheckTicks;
    private boolean genuinelyProgressing = true;
    // How many ticks since the rig's elements last actually got a new setTransformation - see
    // onTick's "PUSH THROTTLE" section for why this exists and pushRigToClient's own comment for
    // what it's throttling against.
    private int ticksSinceRigPush;

    public WendigoVisual(WendigoEntity owner) {
        this.owner = owner;
        for (String bone : WendigoAnimationData.REST_POSE.keySet()) {
            ItemDisplayElement element = new ItemDisplayElement();

            ItemStack stack = new ItemStack(Items.WHITE_DYE);
            stack.set(DataComponents.ITEM_MODEL, WendigoMod.id("blueprint/wendigo/" + bone));
            element.setItem(stack);
            element.setItemDisplayContext(ItemDisplayContext.HEAD);

            element.setInterpolationDuration(ROTATION_INTERPOLATION_TICKS);
            element.setTransformation(RigMatrices.fromRowMajor(WendigoAnimationData.REST_POSE.get(bone)));

            this.bones.put(bone, element);
            this.addElement(element);
        }

        ItemStack eyeStack = new ItemStack(Items.WHITE_DYE);
        eyeStack.set(DataComponents.ITEM_MODEL, WendigoMod.id("blueprint/wendigo/eyes"));
        this.eyes.setItem(eyeStack);
        this.eyes.setItemDisplayContext(ItemDisplayContext.HEAD);
        this.eyes.setInterpolationDuration(ROTATION_INTERPOLATION_TICKS);
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
        boolean moving = updateDebouncedMoving(this.owner.isMoving() && isGenuinelyProgressing());
        boolean crawlHitbox = this.owner.isCrawling();
        // The reference datapack kept every bone's facing in sync with the root each tick
        // (root/on_tick.mcfunction: "execute on passengers run rotate @s ~ ~") -- our bones are
        // independent virtual elements with their own yaw, so we have to do that sync ourselves
        // or the rig stays facing whatever direction it last had regardless of which way the
        // real (invisible) entity turns.
        boolean onFloor = this.owner.isRestingOnFloor();
        boolean crawlPoseActive = moving || crawlHitbox;
        // A held stare OR an active chase both want the same "keep watching the target" treatment -
        // see trackingTarget's use below (headLookExtra) and applyEyes' glow condition.
        boolean trackingTarget = this.owner.isStaring() || this.owner.isChasing();
        // Fetched once, used both by headLookExtra's climbing-case computation below and by
        // rigOrientation's own (unrelated) tilt composition further down.
        Orientation orientation = this.owner.getOrientation();
        // While staring in the standing rest pose, the whole rig turns to face the nearest player
        // instead of following movement. Each bone's transformation matrix bakes in its own
        // translation offset from the rig's shared anchor point (see the class doc), and that
        // translation gets rotated along with whatever yaw is applied to that element; giving only
        // the head bone its own yaw rotated its *position* toward the player as well as its facing,
        // which reads as the head detaching and poking forward off the neck the further it turned -
        // turning every bone together keeps the whole rig internally consistent. In a crawl/idle-
        // crawl pose, though, snapping the WHOLE body around just to look at someone reads wrong -
        // see the headLookExtra block below for the independent, pivot-preserving head-only look
        // used there instead, so the body keeps its own natural crawl-facing while just the head
        // turns to watch (still doesn't touch the real entity's yaw/LookControl, that stays driven
        // purely by movement/pathing either way). Chasing is deliberately NOT included here - a
        // chase is always crawlPoseActive (moving plays the crawl loop), so this branch never
        // applies to it regardless; the whole-body override is a REST_POSE-only, stare-only thing.
        float targetYaw = (onFloor && this.owner.isStaring() && !crawlPoseActive) ? lookAtPlayerYaw() : this.owner.getYRot();
        if (!this.yawInitialized) {
            this.smoothedYaw = targetYaw;
            this.yawInitialized = true;
        } else {
            this.smoothedYaw = Mth.rotLerp(YAW_LERP_FACTOR, this.smoothedYaw, targetYaw);
        }
        float yaw = this.smoothedYaw;

        // smoothedOrientation converges every tick regardless of the push throttle below, same
        // reason smoothedYaw does above - see PUSH THROTTLE's own comment for why the throttle only
        // gates the client-facing push, not this convergence.
        if (!onFloor) {
            Quaternionf targetOrientation = new Quaternionf().setFromUnnormalized(
                RigMatrices.fromOrientationAndYaw(orientation, yaw + CLIMB_YAW_OFFSET_DEGREES));
            if (!this.orientationInitialized) {
                this.smoothedOrientation.set(targetOrientation);
                this.orientationInitialized = true;
            } else {
                this.smoothedOrientation.slerp(targetOrientation, ORIENTATION_LERP_FACTOR);
            }
        }

        // The crawl loop's own frame index also always advances/resets at the true tick rate,
        // independent of the push throttle below - see that section's own comment for why. Losing
        // track of exactly how many frames elapsed between two pushes (by throttling this too) would
        // desync the loop's timing from animationSpeedMultiplier's intended real-time playback rate.
        if (moving) {
            this.animationPhase += animationSpeedMultiplier();
        } else {
            this.animationPhase = 0.0;
        }

        // PUSH THROTTLE: only actually send a new keyframe to the client every
        // ROTATION_INTERPOLATION_TICKS ticks, instead of every tick - see that constant's own
        // comment for the full reasoning (in short: Polymer interpolates a bone's ENTIRE
        // transformation - baked animation pose and rig rotation together, no way to split them -
        // and sending a new one more often than the interpolation window covers means the client is
        // always still catching up to the PREVIOUS target when a newer one arrives, undershooting
        // fast-changing things like the crawl animation's own limb swing. Matching the push rate to
        // the interpolation window instead lets each leg fully complete - a real two-keyframe tween,
        // not a constantly-redirected chase - which is what actually lets the animation reach its
        // full amplitude AND the rig's rotation stay smooth at the same time. Everything above this
        // point (yaw/orientation convergence, animation phase) still updates every tick regardless,
        // so the state being pushed once every N ticks reflects N real ticks' worth of progress, not
        // just a single tick's - the push rate is throttled, not the underlying simulation.
        this.ticksSinceRigPush++;
        if (this.ticksSinceRigPush < ROTATION_INTERPOLATION_TICKS) {
            return;
        }
        this.ticksSinceRigPush = 0;

        // Independent head-only look: while staring or actively chasing, in a crawl/idle-crawl pose
        // (works on the floor AND while climbing now - see computeClimbingHeadLookYaw for the
        // attachment-relative version), the head turns to face the player while the body keeps its
        // own natural crawl-facing (`yaw`), instead of the whole rig snapping around the way the
        // standing rest pose still does. This is a rotation-only delta applied on top of the head/
        // eyes bones' own matrix, pivoting around each bone's own baked position rather than the
        // rig's shared anchor - see applyBoneTransform's own doc comment for why naively giving the
        // head a different yaw the same way the whole body gets one would reproduce the exact "head
        // detaches from the neck" artifact that pattern was already built to avoid.
        Matrix4f headLookExtra = null;
        if (crawlPoseActive && trackingTarget) {
            // Vertical component - see computeHeadLookPitch's own comment for why this one formula
            // covers both the floor and climbing cases without needing an onFloor branch of its own.
            // Composed as rotateY(...).rotateX(...) rather than the other order deliberately: JOML
            // post-multiplies each call, so this applies Rx(pitch) to the bone's own neutral axes
            // FIRST, then Ry(yaw) sweeps the whole already-tilted result around to the target bearing
            // - the standard "pitch in local space, then yaw" composition, which is what makes the
            // pitch tilt read as correct up/down regardless of which way delta/CORRECTION end up
            // pointing the head horizontally - see computeHeadLookPitch's own comment for the sign
            // (live-testing found the naive algebraic sign backwards and negated it there).
            float headLookPitch = computeHeadLookPitch(orientation);
            // The formula genuinely differs between the two branches below - not a typo. Verified
            // via javap against the real client renderer
            // (net.minecraft.client.renderer.entity.DisplayRenderer#calculateOrientation,
            // BillboardConstraints.FIXED case): an item_display's entity-level yaw is composed as
            // Ry(-yaw), not Ry(+yaw) - Minecraft yaw increases clockwise viewed from above, the
            // opposite sense from a standard right-handed rotation about +Y.
            //
            // FLOOR: applyBoneTransform's else-branch calls element.setYaw(yaw) directly (same as
            // every other bone), so the renderer contributes Ry(-yaw), and headLookYaw's own
            // coefficient has to be -1 to track the player's world bearing in the right rotational
            // direction (confirmed live: a full circle walked around a stationary floor-crawl-chase
            // headtest dummy using a +1 coefficient tracked in the OPPOSITE rotational direction -
            // increasing player bearing produced decreasing head rotation). With that fixed, no
            // additive correction is needed either - HEAD_LOOK_YAW_CORRECTION_DEGREES does NOT apply
            // here despite its name suggesting it's shared; two earlier attempts this session each
            // isolated one half of this floor formula's actual bug (first, using "headLookYaw - yaw"
            // uniformly across both branches, left a yaw-dependent "-2*yaw" error - a no-op at yaw
            // 0/180, a full 180 flip at yaw +/-90, and NOT the same bug as this one; then, once that
            // yaw-cancellation was fixed, a coefficient sign error on headLookYaw itself remained,
            // first as an outright rotational mirror when accidentally left at +1, then landing here
            // once corrected to -1 - at which point CORRECTION was still being added on top and had
            // to be dropped too, since it was only ever calibrated against the OLD, yaw=0-only-tested
            // formula and the corrected floor formula needs none).
            //
            // CLIMBING: applyBoneTransform's rigOrientation-branch calls element.setYaw(0f) instead -
            // the renderer contributes nothing here. Instead rigOrientation itself (built from
            // fromOrientationAndYaw(orientation, yaw+CLIMB_YAW_OFFSET_DEGREES)) bakes in a
            // Ry(-(yaw+OFFSET)) of its own - see fromOrientationAndYaw's own doc comment for why its
            // handedness fix inverts the effective yaw direction. Canceling THAT needs a "+yaw" term
            // (opposite of the floor case, since the Ry(-yaw) source is structurally different here),
            // and computeClimbingHeadLookYaw's own atan2(-local.x,-local.z) (solving
            // Ry(delta-yaw-OFFSET)*(0,0,-1)=local directly, in the attachment-local frame rather than
            // lookAtPlayerYaw's world atan2(dz,dx) frame) is used as-is, PLUS
            // HEAD_LOOK_YAW_CORRECTION_DEGREES - this branch (unlike the floor one above) has been
            // confirmed correct via live testing across a full compass sweep (/wendigo headtest's
            // climbing dummies) and hasn't needed to change this session.
            float headLookYawDelta = onFloor
                ? yaw - lookAtPlayerYaw()
                : yaw + CLIMB_YAW_OFFSET_DEGREES + computeClimbingHeadLookYaw(orientation) + HEAD_LOOK_YAW_CORRECTION_DEGREES;
            headLookExtra = new Matrix4f()
                .rotateY((float) Math.toRadians(headLookYawDelta))
                .rotateX((float) Math.toRadians(headLookPitch));
        }

        // Off a horizontal floor (climbing a wall/ceiling), a plain yaw can no longer represent the
        // rig's real orientation - ItemDisplayElement only exposes yaw/pitch, no roll (confirmed via
        // javap), so an arbitrary attachment tilt has to be composed straight into each bone's own
        // matrix instead (see RigMatrices.fromOrientationAndYaw). Null while on a genuine floor, so
        // that path stays exactly what it always was - see applyBoneTransform/applyEyes below.
        Matrix4f rigOrientation = null;
        if (!onFloor) {
            // CLIMB_YAW_OFFSET_DEGREES: live testing showed the rig facing exactly backwards (moving
            // forward, model's back leading) - the model's own baked "front" axis (from the
            // Blockbench/Animated-Java export, unrelated to AWCAPI) doesn't agree with which
            // direction AWCAPI's own getGlobal formula calls "forward". A clean, exact 180 flip is
            // exactly what a front/back mismatch like that produces - same kind of empirically-driven
            // correction the floor rig's own face-UV orientation needed earlier. Confirmed (by
            // comparing real east-wall and south-wall attachment data against AWCAPI's own formula
            // by hand) to hold consistently across every cardinal direction once yaw is baked in
            // before quaternion extraction rather than composed separately afterward - see
            // fromOrientationAndYaw's own doc comment for why the order matters here. The slerp
            // itself already happened above (every tick, ahead of the push throttle) - this just
            // reads the current converged value.
            //
            // The crawl pose's own baked anchor-to-body offset was authored for a floor crawl - the
            // anchor sits roughly at ground level with the body a bit above it. Composed with this
            // attachment orientation, that same offset used to read as the whole rig floating off or
            // sinking into whatever it's attached to, which a hand-tuned geometric ramp (based on how
            // flat vs diagonal the surface was) tried to compensate for - REPLACED with AWCAPI's own
            // ClimberComponent.getAttachmentOffset(Axis, partialTick), the exact per-axis world-space
            // offset AWCAPI itself computes (and already eases in over ~5 ticks after a fresh
            // attachment - see its own attachedTicks*0.2f ramp, confirmed via decompiling the jar)
            // to visually hug a surface, rather than reasoning about it independently from scratch.
            // 1.0f (not a real per-render-frame partial tick, which doesn't exist server-side) just
            // reads the fully-caught-up-to-this-tick value - the closest server-side equivalent of
            // "now". Also the mechanism Stormy's Spiders (a fork of this same AWCAPI, decompiled to
            // confirm) uses to look like it's traveling a smooth ramp across a stair-stepped surface
            // instead of visibly stepping up/down each block edge - see WendigoEntity's real
            // Entity#move() collision physics for why the entity's own raw position genuinely IS
            // stair-stepped on blocky terrain (a true waypoint-level effect, not a rendering gap
            // client-side interpolation alone could ever fix) - this offset is a cosmetic,
            // render-only correction on top of that, not a change to the real collision path.
            //
            // Applied as translationRotate (T(offset) * R(orientation), not rotation().translate())
            // deliberately - unlike the old ramp's offset (a LOCAL "toward the surface" nudge, added
            // BEFORE rotation), AWCAPI's per-axis offset is WORLD-space (X/Y/Z independently, the
            // same convention getAdjustedOnPosition adds it to a world BlockPos with) and needs to be
            // added AFTER rotation instead - translationRotate's argument order does exactly that
            // (T*R applied to a vector: rotate first, then shift by the translation in world axes).
            // First pass at actually wiring this in - tweak from here by feel, live-tested.
            float offsetX = this.owner.getAttachmentOffset(Direction.Axis.X, 1.0f);
            float offsetY = this.owner.getAttachmentOffset(Direction.Axis.Y, 1.0f);
            float offsetZ = this.owner.getAttachmentOffset(Direction.Axis.Z, 1.0f);
            rigOrientation = new Matrix4f().translationRotate(offsetX, offsetY, offsetZ, this.smoothedOrientation);
        }

        if (moving) {
            int frame = ((int) this.animationPhase) % WendigoAnimationData.CRAWL_FRAME_COUNT;
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = WendigoAnimationData.CRAWL.get(entry.getKey())[frame];
                applyBoneTransform(entry.getValue(), matrix, yaw, rigOrientation,
                    "head".equals(entry.getKey()) ? headLookExtra : null);
            }
            applyEyes(WendigoAnimationData.CRAWL.get("head")[frame], yaw, rigOrientation, headLookExtra);
        } else {
            Map<String, float[]> pose = crawlHitbox ? WendigoAnimationData.CRAWL_IDLE : WendigoAnimationData.REST_POSE;
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = pose.get(entry.getKey());
                applyBoneTransform(entry.getValue(), matrix, yaw, rigOrientation,
                    "head".equals(entry.getKey()) ? headLookExtra : null);
            }
            applyEyes(pose.get("head"), yaw, rigOrientation, headLookExtra);
        }
    }

    /** Applies one bone's baked animation matrix, plus either the plain floor-yaw rotation (see
     * onTick) or a full wall/ceiling attachment orientation composed directly into the matrix -
     * fresh copy of rigOrientation per bone since Matrix4f.mul mutates its receiver in place and
     * every bone needs the same starting orientation, not a cumulative one. Doesn't yet attempt the
     * climbing-path "face the nearest player while staring" behavior - AWCAPI's own orientation
     * reflects the entity's actual physics-driven facing, not that override; layering the two
     * together is a live-tuning problem for later, not attempted here.
     *
     * <p>headLookExtra (null for every bone except the head/eyes while independently looking at a
     * player - see onTick) is composed by left-multiplying only the bone's own ROTATION, leaving
     * its baked TRANSLATION untouched - naively rotating the bone's whole matrix (translation
     * included, the same way rigOrientation/yaw rotate the rig as a rigid body) would move the
     * head's POSITION too, not just its facing, reading as the head detaching and poking off the
     * neck the further it turned (the exact reason every OTHER bone shares one rotation instead of
     * each having its own - see targetYaw's own comment in onTick). */
    private void applyBoneTransform(ItemDisplayElement element, float[] matrix, float yaw, Matrix4f rigOrientation, Matrix4f headLookExtra) {
        Matrix4f bone = RigMatrices.fromRowMajor(matrix);
        if (headLookExtra != null) {
            Vector3f position = bone.getTranslation(new Vector3f());
            bone = new Matrix4f(headLookExtra).mul(bone).setTranslation(position);
        }
        if (rigOrientation != null) {
            element.setTransformation(new Matrix4f(rigOrientation).mul(bone));
            element.setYaw(0f);
            element.setPitch(0f);
        } else {
            element.setTransformation(bone);
            element.setYaw(yaw);
        }
        element.startInterpolationIfDirty();
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
        double speed = this.owner.currentSpeed();
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
    /** Asymmetric debounce for animation selection only - see debouncedMoving's own comment. Starting
     * to move commits instantly (no reason to delay a real movement start); stopping only commits
     * after MOVING_ANIMATION_DEBOUNCE_TICKS consecutive not-moving ticks, absorbing the raw signal's
     * climbing-induced flicker. */
    private boolean updateDebouncedMoving(boolean rawMoving) {
        if (rawMoving) {
            this.debouncedMoving = true;
            this.notMovingStableTicks = 0;
        } else {
            this.notMovingStableTicks++;
            if (this.notMovingStableTicks >= MOVING_ANIMATION_DEBOUNCE_TICKS) {
                this.debouncedMoving = false;
            }
        }
        return this.debouncedMoving;
    }

    /** True unless NET displacement over the last PROGRESS_CHECK_TICKS has stayed under
     * PROGRESS_CHECK_DISTANCE_SQR - see PROGRESS_CHECK_TICKS' own comment for why owner.isMoving()
     * alone (pure instantaneous velocity) isn't enough to tell a mob that's genuinely crawling
     * forward apart from one that's wedged and just jittering in place. Snapshot-and-compare over a
     * real window, same technique (and same caveat: a mob that starts genuinely moving mid-window
     * still reads as not-progressing until the NEXT window closes) as PlanRunner's own
     * isMakingNoProgress, which this deliberately mirrors so the two don't quietly drift apart in
     * how they define "stuck." Defaults true (not false) so a freshly-spawned/attached rig doesn't
     * spend its first PROGRESS_CHECK_TICKS looking like it's idling before its first real check. */
    private boolean isGenuinelyProgressing() {
        Vec3 current = this.owner.position();
        if (this.progressCheckPosition == null) {
            this.progressCheckPosition = current;
            this.progressCheckTicks = 0;
            return this.genuinelyProgressing;
        }
        this.progressCheckTicks++;
        if (this.progressCheckTicks < PROGRESS_CHECK_TICKS) {
            return this.genuinelyProgressing;
        }
        this.genuinelyProgressing = current.distanceToSqr(this.progressCheckPosition) >= PROGRESS_CHECK_DISTANCE_SQR;
        this.progressCheckPosition = current;
        this.progressCheckTicks = 0;
        return this.genuinelyProgressing;
    }

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

    /** The climbing-case equivalent of lookAtPlayerYaw() - the LOCAL yaw value (in the same
     * attachment-relative sense owner.getYRot() itself has while climbing - see targetYaw's own
     * comment in onTick) that points the head at the nearest player, instead of a plain world
     * bearing (which isn't valid input to RigMatrices.fromOrientationAndYaw off a flat floor).
     *
     * <p>Derived by solving {@code Ry(delta - yaw - CLIMB_YAW_OFFSET_DEGREES) * (0,0,-1) = local}
     * for the delta-independent part (i.e. treating this method's own return value as exactly the
     * {@code atan2(-local.x, -local.z)} term onTick's headLookYawDelta then adds yaw/OFFSET back
     * onto - see that composition's own doc comment for why climbing needs the OPPOSITE sign
     * convention from lookAtPlayerYaw's floor-case use, and don't "fix" this formula in isolation
     * again without re-deriving that composition too - a previous session flipped local.x's sign
     * here alone, which happened to reproduce the correct result at yaw=0 (where the onTick delta
     * formula was ALSO still wrong the same way, canceling out) but was wrong in general, and got
     * reverted once the actual onTick-side bug was found instead. */
    private float computeClimbingHeadLookYaw(Orientation orientation) {
        Vector3f local = localDirectionToPlayer(orientation);
        if (local == null) {
            return this.owner.getYRot();
        }
        return (float) Math.toDegrees(Mth.atan2(-local.x, -local.z));
    }

    /** Vertical head-look angle toward the nearest player, in the same attachment-local frame
     * localDirectionToPlayer resolves - one formula for both the floor and climbing cases, since it
     * only ever reads that frame's own up axis (local.y) rather than a separate world-space formula,
     * so it can't disagree with whichever yaw formula (lookAtPlayerYaw or
     * computeClimbingHeadLookYaw) onTick used to get here. On the floor, getOrientation()'s local
     * frame is just the trivial world-aligned attachment (localY = world up), so this reduces to the
     * plain "angle above/below eye level" you'd expect. 0 (looking level) if no player is nearby.
     *
     * <p>Negated relative to asin(local.y)'s own algebraic sign: hand-derivation said JOML's
     * rotateX(+angle) in onTick's rotateY(yaw).rotateX(pitch) composition should tilt the forward
     * vector's local.y toward positive (up) for positive angle, matching a plain +asin(local.y) - but
     * live testing showed the head tilting up when the player was below and down when they were
     * above, the exact opposite. Rather than re-derive which sign convention JOML's rotateX actually
     * uses here, just negate the empirically-wrong result - same pragmatic approach already used for
     * CLIMB_YAW_OFFSET_DEGREES/HEAD_LOOK_YAW_CORRECTION_DEGREES elsewhere in this file.
     *
     * <p>That live test was only run against a south-facing (yaw=0) climbing dummy - the same
     * facing where computeClimbingHeadLookYaw's now-fixed sign bug happened to be a no-op (see its
     * own doc comment). This formula only ever reads local.y, which the yaw-mixing/handedness-flip
     * columns (localX/localZ) never touch, so it should be yaw-independent by construction - but
     * that hasn't actually been confirmed against an east/west-facing dummy the way the yaw bug's
     * fix was. The expanded /wendigo headtest rig's yaw sweep covers this now; if pitch turns out to
     * also misbehave off south-facing, look here first. */
    private float computeHeadLookPitch(Orientation orientation) {
        Vector3f local = localDirectionToPlayer(orientation);
        if (local == null) {
            return 0f;
        }
        return -(float) Math.toDegrees(Math.asin(Mth.clamp(local.y, -1f, 1f)));
    }

    /** Unit direction from the wendigo's eyes to the nearest player, transformed into the given
     * attachment orientation's own local frame (localX=right, localY=up/the surface normal,
     * localZ=back - see RigMatrices.fromOrientationAndYaw) - shared by
     * computeClimbingHeadLookYaw (the horizontal component) and computeHeadLookPitch (the vertical
     * one). Null if no player is nearby. */
    private Vector3f localDirectionToPlayer(Orientation orientation) {
        Player player = this.owner.level().getNearestPlayer(this.owner, LOOK_AT_PLAYER_RADIUS);
        if (player == null) {
            return null;
        }
        Vec3 toPlayer = new Vec3(
            player.getX() - this.owner.getX(),
            player.getEyeY() - this.owner.getEyeY(),
            player.getZ() - this.owner.getZ()
        ).normalize();
        Vector3f local = new Vector3f((float) toPlayer.x, (float) toPlayer.y, (float) toPlayer.z);
        RigMatrices.fromOrientationAndYaw(orientation, 0f).transpose().transformDirection(local);
        return local;
    }

    /** Keeps the eyes overlay locked to the head bone's own transform every frame, in either pose -
     * see applyBoneTransform for the rigOrientation (wall/ceiling attachment) and headLookExtra
     * (independent crawl-pose stare/chase tracking) parameters. */
    private void applyEyes(float[] headMatrix, float yaw, Matrix4f rigOrientation, Matrix4f headLookExtra) {
        applyBoneTransform(this.eyes, headMatrix, yaw, rigOrientation, headLookExtra);
        // Only actually visible-in-the-dark while the entity is mid-stare or actively chasing (see
        // WendigoEntity#isStaring/#isChasing) -- null resets to natural lighting, same as everywhere
        // else on the rig, the rest of the time.
        this.eyes.setBrightness((this.owner.isStaring() || this.owner.isChasing()) ? Brightness.FULL_BRIGHT : null);
    }
}
