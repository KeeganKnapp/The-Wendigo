package com.wendigo.entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;

import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents; // TODO verify against this MC version's generated mappings
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
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
 * Animated-Java-exported wendigo rig (see WendigoAnimationData), PLUS one extra, slightly-larger
 * head-only {@code item_display} (see {@link #headGlow}) wearing the emissive "head_e" texture for
 * the glowing-eyes effect. Attached to a real, invisible {@link WendigoEntity} via
 * {@code EntityAttachment.ofTicking} -- see WendigoMod's ServerEntityEvents hooks for the
 * attach/detach wiring.
 *
 * Every bone is given zero offset from the holder's tracked position, same as the reference
 * datapack: there every bone was a literal vehicle Passenger of one root item_display (so their
 * world position always equals the root's), and all visual placement came entirely from each
 * bone's own "transformation" NBT. We reproduce that exactly: offset stays default (0,0,0), and
 * setTransformation() does all the work.
 */
public class WendigoVisual extends ElementHolder {
    // The real entity's own yaw is driven directly by pathfinding/move-control snapping toward the
    // next path node every tick (no LookAtGoal/RandomLookAroundGoal-style smoothing, since it has no
    // Goals beyond FloatGoal) -- reading it raw made the rig visibly jerky. Easing toward it instead
    // of copying it verbatim smooths that out; Mth.rotLerp already takes the shortest way around.
    private static final float YAW_LERP_FACTOR = 0.5f;
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
    private static final float ORIENTATION_LERP_FACTOR = 0.4f;
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
    // Was 180 (empirically, for the OLD rig's own head export) - live testing after the new
    // per-bone-textured rig swap (see the 20th-pass memory entry) found the new head model's own
    // baked "forward" no longer has the front/back mismatch the old 180 was compensating for
    // ("in blockbench his head is completely on straight"): standing-idle and ordinary crawl
    // navigation both confirmed correct at a flat 0 via live /wendigo headoffset testing, so that's
    // now the default every non-climbing pose falls back to unless overridden. Still live-adjustable
    // per-pose (see WendigoDebug's standing/tracking head-yaw overrides) in case a future geometry/UV
    // change needs this revisited yet again - same reasoning CLIMB_YAW_OFFSET_DEGREES above documents.
    private static final float HEAD_LOOK_YAW_CORRECTION_DEGREES = 0.0f;
    // The climbing-tracking counterpart to HEAD_LOOK_YAW_CORRECTION_DEGREES above - a separate value
    // since the climbing formula composes the correction differently (see this method's own comment on
    // headLookYawDelta). Confirmed live via /wendigo climbheadoffset: 180 is correct for the new rig,
    // the exact opposite of the floor case's own flip (180 -> 0) - matches this file's running pattern
    // of the new per-bone-textured export needing every "front/back" style correction re-tuned, not
    // necessarily in the same direction each time.
    private static final float CLIMB_TRACKING_HEAD_LOOK_YAW_CORRECTION_DEGREES = 180.0f;
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
    private static final double SLOW_ANIMATION_SPEED = 2.5;
    private static final double NORMAL_ANIMATION_SPEED = 3.25;
    private static final double FAST_ANIMATION_SPEED = 4.0;
    // Real-time (1x) playback for the new looping "stand" idle animation - unlike the crawl loop, there's
    // no live movement speed to scale this against while genuinely standing still. First-pass, adjust by
    // feel once actually watched in-game.
    private static final double STAND_ANIMATION_SPEED = 1.0;

    // How many consecutive not-moving ticks owner.isMoving() must read before the ANIMATION commits
    // to idle-crawl instead of the crawl loop - see onTick's debouncedMoving. Only the "stopping"
    // direction is debounced (mirrors WendigoEntity's own asymmetric pose debounce) - starting to
    // move stays instant. Bumped from 6 to 10 to match WendigoEntity's own POSE_SWITCH_DEBOUNCE_TICKS/
    // RESTING_ON_FLOOR_DEBOUNCE_TICKS (see their own comments for the live-reported model-flicker bug
    // this is a companion fix for) - crawlPoseActive combines this flag with both of those, so an
    // independently-shorter window here could still let the model set flip even once the other two
    // settle down. Real bug found live: this had silently drifted back down to 5 (half the documented/
    // intended value) at some point after that comment was written, exactly reproducing the failure
    // mode the comment itself warns about - a slope-crossing wendigo cycling through poses roughly
    // every 10 ticks, this constant's own now-too-short window flipping independently of (and roughly
    // twice as often as) the two 10-tick entity-side debounces it's supposed to stay locked to.
    private static final int MOVING_ANIMATION_DEBOUNCE_TICKS = 2;
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
    // "open" (the jaw) is a head sub-bone in the new rig, baked as its own independent bone matrix
    // rather than parented under "head" -- without also routing headLookExtra to it, the jaw stayed
    // frozen in its animation-only orientation while the head itself turned to face the player,
    // reading as the jaw detaching/poking off the head the further it turned (same failure mode
    // applyBoneTransform's own doc comment describes for naively rotating a bone's full matrix).
    private static final Set<String> HEAD_LOOK_BONES = Set.of("head", "open");
    // Not one of the animated bones in WendigoAnimationData (that file is generated from the
    // Animated-Java export and isn't meant to be hand-edited) -- instead this just mirrors "head"'s
    // transformation every tick, at the exact same size/rotation, but with the head-only emissive
    // "head_e" texture and its own independent brightness override driven by WendigoEntity#isStaring/
    // isChasing. Was a separate floating "eyes" display in the old rig (its own bone, its own
    // whole-body-sized emissive texture) - the new rig has no such bone at all, so this is now
    // literally just a second head-shaped layer, same coincident-layers trick as before, just scoped
    // to the head instead of a standalone element. Its model geometry is a hair larger than the head's
    // own cubes (see head_e.json, generated from head.json's own bounds expanded by a small margin) so
    // the two coincident item-display layers don't z-fight where they overlap.
    private final ItemDisplayElement headGlow = new ItemDisplayElement();
    // A fractional frame accumulator rather than an int frame-index -- advancing by a non-integer
    // multiplier (2.5x, etc) every tick needs the fractional remainder to carry over, or the loop's
    // real-time speed would round back down toward 1x. Drives the CRAWL_NORMAL/CRAWL_ANGRY loop while
    // actually moving - see standAnimationPhase for the equivalent while standing idle.
    private double animationPhase = 0.0;
    // Same fractional-carry reasoning as animationPhase, for the new looping "stand" animation (41
    // keyframes now, was a single static pose) - the user's own explicit "play it as a real looping
    // idle animation" request. Advances only while genuinely standing idle (not moving, not in the
    // crawl/swim hitbox), independent of animationPhase's own moving-only advancement.
    private double standAnimationPhase = 0.0;
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
        WendigoEntity.TexturePreviewMode previewMode = owner.getTexturePreviewMode();
        boolean showBase = previewMode == WendigoEntity.TexturePreviewMode.ALL
            || previewMode == WendigoEntity.TexturePreviewMode.BASE_ONLY;
        boolean showEyes = previewMode == WendigoEntity.TexturePreviewMode.ALL
            || previewMode == WendigoEntity.TexturePreviewMode.EYES_ONLY;
        for (String bone : WendigoAnimationData.REST_POSE.keySet()) {
            ItemDisplayElement element = new ItemDisplayElement();

            if (showBase) {
                ItemStack stack = new ItemStack(Items.WHITE_DYE);
                stack.set(DataComponents.ITEM_MODEL, WendigoMod.id("blueprint/wendigo/" + bone));
                element.setItem(stack);
            }
            element.setItemDisplayContext(ItemDisplayContext.HEAD);

            element.setInterpolationDuration(ROTATION_INTERPOLATION_TICKS);
            element.setTransformation(RigMatrices.fromRowMajor(WendigoAnimationData.REST_POSE.get(bone)));

            this.bones.put(bone, element);
            this.addElement(element);
        }

        if (showEyes) {
            ItemStack glowStack = new ItemStack(Items.WHITE_DYE);
            glowStack.set(DataComponents.ITEM_MODEL, WendigoMod.id("blueprint/wendigo/head_e"));
            this.headGlow.setItem(glowStack);
        }
        this.headGlow.setItemDisplayContext(ItemDisplayContext.HEAD);
        this.headGlow.setInterpolationDuration(ROTATION_INTERPOLATION_TICKS);
        this.headGlow.setTransformation(RigMatrices.fromRowMajor(WendigoAnimationData.REST_POSE.get("head")));
        this.addElement(this.headGlow);
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
        // see trackingTarget's use below (headLookExtra) and applyHeadGlow's glow condition.
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
        // Same "always advances/resets at the true tick rate, independent of the push throttle" reasoning
        // as animationPhase above, for the new looping stand animation - genuinely standing idle only
        // (not moving, not in the crawl/swim hitbox); crawlHitbox-but-not-moving still holds the single
        // CRAWL_IDLE frame, unchanged.
        if (!moving && !crawlHitbox) {
            this.standAnimationPhase += STAND_ANIMATION_SPEED;
        } else {
            this.standAnimationPhase = 0.0;
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
        //
        // headLookExtra is now NEVER null (renamed in spirit, not in code, to avoid a much larger
        // diff): the head/eyes bones' own baked "forward" is backwards - a front/back mismatch baked
        // into the exported rig, the same root cause HEAD_LOOK_YAW_CORRECTION_DEGREES was already
        // compensating for in the climbing branch below - which never mattered visually while
        // texture_e was a solid black copy of the base texture (a 180-flipped solid color is
        // indistinguishable from itself), but reads as "the face is on the back of the head" the
        // instant the base texture actually has a real front/back. The else-branch below applies
        // that exact same correction as a standalone rotation whenever there's no active look-at
        // delta to fold it into instead - the model's own north/south UV fix (see head.json/
        // head_overlay.json) only corrects which texture paints which face; this corrects which way
        // the corrected face physically points.
        Matrix4f headLookExtra;
        if (trackingTarget) {
            // Used to require crawlPoseActive too, so a STANDING wendigo that's staring (posture.stare
            // only ever sets a boolean flag - see WendigoEntity.setStaring/PlanRunner's "posture.stare"
            // case - it never actually turns the entity's real yRot toward the player) got no
            // independent head-look at all, just the body's own leftover yaw plus the flat idle
            // correction below: it would visibly stand there staring at whatever direction it happened
            // to already be facing, not at the player. Dropping the crawlPoseActive requirement lets
            // this same look-at math run for standing+tracking too - the formula itself already only
            // depends on yaw/orientation, not on which pose is currently animating, so nothing else
            // about it needs to change for the standing case.
            // Vertical component - see computeHeadLookPitch's own comment for why this one formula
            // covers both the floor and climbing cases without needing an onFloor branch of its own.
            // Composed as rotateY(...).rotateX(...) rather than the other order deliberately: JOML
            // post-multiplies each call, so this applies Rx(pitch) to the bone's own neutral axes
            // FIRST, then Ry(yaw) sweeps the whole already-tilted result around to the target bearing
            // - the standard "pitch in local space, then yaw" composition, which is what makes the
            // pitch tilt read as correct up/down regardless of which way delta/CORRECTION end up
            // pointing the head horizontally - see computeHeadLookPitch's own comment for the sign
            // (live-testing found the naive algebraic sign backwards and negated it there).
            //
            // onFloor gets an extra negation on top of that: live testing this session found the
            // floor case tracking vertically backwards (tilting down as the player got higher, up as
            // they got lower) while the climbing case read correctly with the exact same
            // computeHeadLookPitch call - since that method is shared verbatim between both branches
            // and only ever reads orientation's local.y, this means the floor "trivial" orientation
            // (see getOrientation()'s own floor-case behavior) disagrees with the climbing
            // orientation's sense of "up" by a sign flip that computeHeadLookPitch's own derivation
            // didn't anticipate - negating here, at the one point where the two branches' pitch
            // values are known to diverge, fixes the symptom without assuming why the two
            // orientations disagree.
            float headLookPitch = computeHeadLookPitch(orientation);
            if (onFloor) {
                if (WendigoDebug.isFloorCrawlPitchInverted()) {
                    headLookPitch = -headLookPitch;
                }
            } else if (WendigoDebug.isClimbPitchInverted()) {
                headLookPitch = -headLookPitch;
            }
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
            // increasing player bearing produced decreasing head rotation). Two earlier attempts this
            // session each isolated one half of this floor formula's actual bug (first, using
            // "headLookYaw - yaw" uniformly across both branches, left a yaw-dependent "-2*yaw" error
            // - a no-op at yaw 0/180, a full 180 flip at yaw +/-90, and NOT the same bug as this one;
            // then, once that yaw-cancellation was fixed, a coefficient sign error on headLookYaw
            // itself remained, first as an outright rotational mirror when accidentally left at +1,
            // then landing here once corrected to -1). An earlier session tried adding
            // HEAD_LOOK_YAW_CORRECTION_DEGREES here too and found it wrong for this branch
            // specifically at the time - but that finding predates this session's head/growth-
            // overlay/eyes texture UV swaps (see head.json/head_overlay.json/eyes.json), which
            // changed which physical face the "front" artwork actually lands on. Re-tested this
            // session via /wendigo headoffset (crawl-tracking doesn't go through that override, so
            // this needed its own separate live confirmation): the correction is needed again now -
            // see the "+ HEAD_LOOK_YAW_CORRECTION_DEGREES" folded into headLookYawDelta below.
            //
            // CLIMBING: applyBoneTransform's rigOrientation-branch calls element.setYaw(0f) instead -
            // the renderer contributes nothing here. Instead rigOrientation itself (built from
            // fromOrientationAndYaw(orientation, yaw+CLIMB_YAW_OFFSET_DEGREES)) bakes in a
            // Ry(-(yaw+OFFSET)) of its own - see fromOrientationAndYaw's own doc comment for why its
            // handedness fix inverts the effective yaw direction. Canceling THAT needs a "+yaw" term
            // (opposite of the floor case, since the Ry(-yaw) source is structurally different here),
            // and computeClimbingHeadLookYaw's own atan2(-local.x,-local.z) (solving
            // Ry(delta-yaw-OFFSET)*(0,0,-1)=local directly, in the attachment-local frame rather than
            // lookAtPlayerYaw's world atan2(dz,dx) frame) is used as-is - this branch was confirmed
            // correct WITH a plain "+ HEAD_LOOK_YAW_CORRECTION_DEGREES" folded in via a full compass
            // sweep in an earlier session, before this session's head/growth-overlay/eyes texture UV
            // swaps changed which physical face the "front" artwork lands on (the same swap that
            // flipped the floor branch's own correction need - see headLookYawDelta below). Live
            // testing this session (back of the head facing the player while climbing, vertical
            // tracking otherwise correct) confirmed the correction needs to come OUT now rather than
            // doubling up - hence no HEAD_LOOK_YAW_CORRECTION_DEGREES term in the climbing case below
            // anymore, the opposite change from what the floor case needed.
            // Floor case gained its own + HEAD_LOOK_YAW_CORRECTION_DEGREES this session: live
            // testing via /wendigo headoffset (which only overrides the standing branch below, not
            // this one) pinned down that crawling while actively tracking (chasing/staring) reads
            // backwards at the previous plain "yaw - lookAtPlayerYaw()" and reads correctly with
            // this same 180 folded in - a real behavior difference from the standing/climbing cases
            // below, not a mistake; see this method's other two corrections for why each pose needs
            // its own independently-tuned value instead of one shared constant. Now independently
            // live-tunable too (see WendigoDebug.getTrackingHeadYawOverride, set via
            // /wendigo trackheadoffset) - the new per-bone head texture changed which physical face
            // the "front" artwork lands on again, same class of change that has flipped this
            // constant's needed value more than once before; NaN falls back to the same plain
            // HEAD_LOOK_YAW_CORRECTION_DEGREES default every other still-hardcoded pose keeps using.
            float trackingOverride = WendigoDebug.getTrackingHeadYawOverride();
            float trackingCorrectionDegrees = Float.isNaN(trackingOverride) ? HEAD_LOOK_YAW_CORRECTION_DEGREES : trackingOverride;
            float climbTrackingOverride = WendigoDebug.getClimbTrackingHeadYawOverride();
            float climbTrackingCorrectionDegrees = Float.isNaN(climbTrackingOverride)
                ? CLIMB_TRACKING_HEAD_LOOK_YAW_CORRECTION_DEGREES : climbTrackingOverride;
            float headLookYawDelta = onFloor
                ? yaw - lookAtPlayerYaw() + trackingCorrectionDegrees
                : yaw + CLIMB_YAW_OFFSET_DEGREES + computeClimbingHeadLookYaw(orientation) + climbTrackingCorrectionDegrees;
            headLookExtra = new Matrix4f()
                .rotateY((float) Math.toRadians(headLookYawDelta))
                .rotateX((float) Math.toRadians(headLookPitch));
        } else if (onFloor && !crawlPoseActive) {
            // Standing rest-pose, NOT tracking anyone (trackingTarget already routed above if it were
            // true) - just a flat UV/front-facing correction on top of whatever the STAND animation's
            // own baked yaw already is, same idea as the plain else branch below. Confirmed correct via
            // live /wendigo headoffset testing (0 degrees, after the new per-bone head texture).
            // Left live-adjustable (NaN override = "use that same default") rather than hardcoded,
            // in case a future geometry/UV change needs it revisited again.
            float override = WendigoDebug.getStandingHeadYawOverride();
            float standingCorrectionDegrees = Float.isNaN(override) ? HEAD_LOOK_YAW_CORRECTION_DEGREES : override;
            headLookExtra = new Matrix4f().rotateY((float) Math.toRadians(standingCorrectionDegrees));
        } else {
            // Crawl-idle, floor or climbing, AND ordinary crawl-navigation movement while not
            // actively tracking anyone (i.e. crawlPoseActive true, trackingTarget false covers both -
            // this isn't just the held idle pose). An earlier pass this session special-cased
            // floor-crawl-idle to a flat 0 based on /wendigo headtest's "crawling" dummies reading
            // correctly at that value - but every headtest crawling dummy spawns with
            // setDebugForceChasing(true) (see WendigoCommands.HEADTEST_SLOTS), so that observation was
            // actually confirming the ACTIVE-TRACKING branch above, not this one; it never tested a
            // genuinely non-tracking crawl-idle wendigo at all. Live testing an actual idle (not
            // staring/chasing) wendigo this session found floor-idle backwards at that flat 0 and
            // correct at the same plain HEAD_LOOK_YAW_CORRECTION_DEGREES climbing-idle already used -
            // so both now share the one plain correction, same as standing - reading the SAME live
            // override as the standing branch above (not just sharing its default value) so a single
            // /wendigo headoffset test covers both instead of only ever affecting the standing pose,
            // which is what let this one silently stay wrong after the newest texture swap despite the
            // active-tracking pose (a separately-tuned override, see trackingCorrectionDegrees above)
            // reading correctly.
            float idleOverride = WendigoDebug.getStandingHeadYawOverride();
            float idleCorrectionDegrees = Float.isNaN(idleOverride) ? HEAD_LOOK_YAW_CORRECTION_DEGREES : idleOverride;
            headLookExtra = new Matrix4f().rotateY((float) Math.toRadians(idleCorrectionDegrees));
        }

        // Off a horizontal floor (climbing a wall/ceiling), a plain yaw can no longer represent the
        // rig's real orientation - ItemDisplayElement only exposes yaw/pitch, no roll (confirmed via
        // javap), so an arbitrary attachment tilt has to be composed straight into each bone's own
        // matrix instead (see RigMatrices.fromOrientationAndYaw). Null while on a genuine floor, so
        // that path stays exactly what it always was - see applyBoneTransform/applyHeadGlow below.
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
            // The user's own explicit "angry crawl" trigger: chase/lunge/carrying a grabbed player
            // (isCapturing) play CRAWL_ANGRY, everything else that's simply navigating plays
            // CRAWL_NORMAL - see PlanRunner.isCapturing's own doc comment for the exact trigger list.
            boolean angry = this.owner.isCapturing();
            Map<String, float[][]> crawlAnimation = angry ? WendigoAnimationData.CRAWL_ANGRY : WendigoAnimationData.CRAWL_NORMAL;
            int frameCount = angry ? WendigoAnimationData.CRAWL_ANGRY_FRAME_COUNT : WendigoAnimationData.CRAWL_NORMAL_FRAME_COUNT;
            int frame = ((int) this.animationPhase) % frameCount;
            float[] headFrameMatrix = crawlAnimation.get("head")[frame];
            Vector3f headPivot = RigMatrices.fromRowMajor(headFrameMatrix).getTranslation(new Vector3f());
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = crawlAnimation.get(entry.getKey())[frame];
                Matrix4f boneHeadLookExtra = HEAD_LOOK_BONES.contains(entry.getKey()) ? headLookExtra : null;
                applyBoneTransform(entry.getValue(), matrix, yaw, rigOrientation, boneHeadLookExtra, headPivot);
            }
            applyHeadGlow(headFrameMatrix, yaw, rigOrientation, headLookExtra, headPivot);
        } else if (crawlHitbox) {
            // Held crawl-idle pose - the user's own explicit "crawl idle stays the same" (a single
            // frame, unchanged, not looped like stand/crawl now both are).
            float[] headFrameMatrix = WendigoAnimationData.CRAWL_IDLE.get("head");
            Vector3f headPivot = RigMatrices.fromRowMajor(headFrameMatrix).getTranslation(new Vector3f());
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = WendigoAnimationData.CRAWL_IDLE.get(entry.getKey());
                Matrix4f boneHeadLookExtra = HEAD_LOOK_BONES.contains(entry.getKey()) ? headLookExtra : null;
                applyBoneTransform(entry.getValue(), matrix, yaw, rigOrientation, boneHeadLookExtra, headPivot);
            }
            applyHeadGlow(headFrameMatrix, yaw, rigOrientation, headLookExtra, headPivot);
        } else {
            // Standing idle - now a real looping animation (41 keyframes), not a single static rest
            // pose - the user's own explicit "play it as a real looping idle animation" request.
            int frame = ((int) this.standAnimationPhase) % WendigoAnimationData.STAND_FRAME_COUNT;
            float[] headFrameMatrix = WendigoAnimationData.STAND.get("head")[frame];
            Vector3f headPivot = RigMatrices.fromRowMajor(headFrameMatrix).getTranslation(new Vector3f());
            for (Map.Entry<String, ItemDisplayElement> entry : this.bones.entrySet()) {
                float[] matrix = WendigoAnimationData.STAND.get(entry.getKey())[frame];
                Matrix4f boneHeadLookExtra = HEAD_LOOK_BONES.contains(entry.getKey()) ? headLookExtra : null;
                applyBoneTransform(entry.getValue(), matrix, yaw, rigOrientation, boneHeadLookExtra, headPivot);
            }
            applyHeadGlow(headFrameMatrix, yaw, rigOrientation, headLookExtra, headPivot);
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
     * <p>headLookExtra (null for every bone except the head-look bones - the head itself plus any
     * head sub-bones, see HEAD_LOOK_BONES - which always get a non-null value now, see onTick's own
     * comment on why headLookExtra is never null there anymore) orbits the bone around headPivot (the
     * HEAD bone's own baked position for this frame, not necessarily this bone's own translation):
     * T(headPivot) * headLookExtra * T(-headPivot) * bone. For the head bone itself this degenerates
     * to exactly the old "rotate in place" behavior (its own translation IS headPivot, by
     * construction, so the net translation is unchanged - naively rotating the bone's whole matrix
     * around the WORLD origin instead would move the head's POSITION too, not just its facing,
     * reading as the head detaching and poking off the neck the further it turned, the exact reason
     * every OTHER bone shares one rotation instead of each having its own - see targetYaw's own
     * comment in onTick). For a head SUB-bone (the jaw/"open"), whose own baked translation sits
     * elsewhere (near the mouth, not the neck), orbiting around the shared headPivot instead of the
     * jaw's own translation is what keeps it rigidly attached and turning together with the head
     * instead of spinning in place at its own separate anchor - confirmed live: reusing the old
     * "preserve own translation" trick for the jaw read as the jaw moving independently of the head
     * rather than as a child of it. */
    private void applyBoneTransform(ItemDisplayElement element, float[] matrix, float yaw, Matrix4f rigOrientation, Matrix4f headLookExtra, Vector3f headPivot) {
        Matrix4f bone = RigMatrices.fromRowMajor(matrix);
        if (headLookExtra != null) {
            Vector3f negPivot = new Vector3f(headPivot).negate();
            bone = new Matrix4f().translate(headPivot).mul(headLookExtra).translate(negPivot).mul(bone);
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
     * slow/normal midpoint still gets the slow-band (floor) speed, not something slower.
     * <p>
     * Band selection itself deliberately still reads the LIVE (possibly WendigoEntity.
     * updateClimbingSpeedPenalty-reduced) attribute value for baseSpeed, not the unmodified base -
     * currentSpeed() is driven by that same live value, so the speed/baseSpeed ratio this buckets
     * against is already invariant to the penalty (both shrink together), meaning band selection
     * always reflects which semantic tier (slow/normal/fast) was actually commanded, never
     * misreading a penalized "normal" chase as "slow" just because it's moving more slowly in
     * absolute terms. What that invariance means, though, is the banded result on its own never
     * changes just because the penalty is active - a real, live-reported bug: nearing a steep
     * ceiling visibly cuts the entity's real movement speed by up to 40%, but the crawl loop kept
     * playing at exactly the same rate throughout, desyncing the limb cycle from the real motion the
     * same way unscaled 1x playback against real movement speed already did before this whole
     * banding system existed (see SLOW_ANIMATION_SPEED's own comment). Fixed by separately reading
     * how much of the base attribute survives after modifiers (climbingPenaltyMultiplier, 1.0 with no
     * penalty down to 1.0 + CLIMBING_SPEED_PENALTY_MAX_FRACTION at a dead-on ceiling) and applying it
     * on top of the picked band, so the animation slows down by the same real fraction the movement
     * itself just did - the same "playback rate matches real motion" contract the band system already
     * guarantees for the slow/normal/fast tiers themselves. */
    private double animationSpeedMultiplier() {
        AttributeInstance movementSpeedAttribute = this.owner.getAttribute(Attributes.MOVEMENT_SPEED);
        double baseSpeed = movementSpeedAttribute.getValue();
        if (baseSpeed <= 0.0) {
            return NORMAL_ANIMATION_SPEED;
        }
        double speed = this.owner.currentSpeed();
        double slowNormalBoundary = baseSpeed * (SLOW_MOVE_MULTIPLIER + NORMAL_MOVE_MULTIPLIER) / 2.0;
        double normalFastBoundary = baseSpeed * (NORMAL_MOVE_MULTIPLIER + FAST_MOVE_MULTIPLIER) / 2.0;
        double bandAnimationSpeed;
        if (speed >= normalFastBoundary) {
            bandAnimationSpeed = FAST_ANIMATION_SPEED;
        } else if (speed >= slowNormalBoundary) {
            bandAnimationSpeed = NORMAL_ANIMATION_SPEED;
        } else {
            bandAnimationSpeed = SLOW_ANIMATION_SPEED;
        }
        double unpenalizedBaseSpeed = movementSpeedAttribute.getBaseValue();
        double climbingPenaltyMultiplier = unpenalizedBaseSpeed > 0.0
            ? Math.clamp(baseSpeed / unpenalizedBaseSpeed, 0.0, 1.0)
            : 1.0;
        return bandAnimationSpeed * climbingPenaltyMultiplier;
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
        this.headGlow.setGlowing(glowing);
        if (glowing) {
            this.headGlow.setGlowColorOverride(DEBUG_GLOW_COLOR);
        }
    }

    /** Live world-space position of a named bone right now, mirroring exactly the same transform
     * onTick composes for rendering (see applyBoneTransform's two branches) - used by
     * WendigoEntity.getVisualEyePosition() so stare-detection targets precisely what the player
     * actually sees, in every pose (moving crawl-loop frame, held crawl-idle, standing rest, floor
     * or climbing alike) instead of a fixed-offset approximation. Reads this.debouncedMoving/
     * this.smoothedYaw/this.smoothedOrientation (the exact values the MOST RECENT onTick call
     * already converged and rendered with) rather than recomputing anything fresh - a query between
     * ticks should reflect what's actually on screen right now, not a hypothetical this-instant
     * recalculation. headLookExtra is deliberately NOT applied here - see applyBoneTransform's own
     * doc comment: it only rotates a bone's FACING, never its baked position, so it doesn't affect
     * a pure position query at all. */
    Vec3 getBoneWorldPosition(String bone) {
        float[] matrix;
        if (this.debouncedMoving) {
            boolean angry = this.owner.isCapturing();
            Map<String, float[][]> crawlAnimation = angry ? WendigoAnimationData.CRAWL_ANGRY : WendigoAnimationData.CRAWL_NORMAL;
            int frameCount = angry ? WendigoAnimationData.CRAWL_ANGRY_FRAME_COUNT : WendigoAnimationData.CRAWL_NORMAL_FRAME_COUNT;
            int frame = ((int) this.animationPhase) % frameCount;
            matrix = crawlAnimation.get(bone)[frame];
        } else if (this.owner.isCrawling()) {
            matrix = WendigoAnimationData.CRAWL_IDLE.get(bone);
        } else {
            int frame = ((int) this.standAnimationPhase) % WendigoAnimationData.STAND_FRAME_COUNT;
            matrix = WendigoAnimationData.STAND.get(bone)[frame];
        }
        Matrix4f boneMatrix = RigMatrices.fromRowMajor(matrix);
        Matrix4f world;
        if (this.owner.isRestingOnFloor()) {
            world = new Matrix4f().rotateY((float) Math.toRadians(-this.smoothedYaw)).mul(boneMatrix);
        } else {
            float offsetX = this.owner.getAttachmentOffset(Direction.Axis.X, 1.0f);
            float offsetY = this.owner.getAttachmentOffset(Direction.Axis.Y, 1.0f);
            float offsetZ = this.owner.getAttachmentOffset(Direction.Axis.Z, 1.0f);
            Matrix4f rigOrientation = new Matrix4f().translationRotate(offsetX, offsetY, offsetZ, this.smoothedOrientation);
            world = new Matrix4f(rigOrientation).mul(boneMatrix);
        }
        Vector3f translation = world.getTranslation(new Vector3f());
        return this.owner.position().add(translation.x, translation.y, translation.z);
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

    /** Keeps the head-only emissive glow overlay locked to the head bone's own transform every frame,
     * in every pose - see applyBoneTransform for the rigOrientation (wall/ceiling attachment) and
     * headLookExtra (independent crawl-pose stare/chase tracking) parameters. */
    private void applyHeadGlow(float[] headMatrix, float yaw, Matrix4f rigOrientation, Matrix4f headLookExtra, Vector3f headPivot) {
        applyBoneTransform(this.headGlow, headMatrix, yaw, rigOrientation, headLookExtra, headPivot);
        // Only actually visible-in-the-dark while the entity is mid-stare or actively chasing (see
        // WendigoEntity#isStaring/#isChasing) -- null resets to natural lighting, same as everywhere
        // else on the rig, the rest of the time. Deliberately still isStaring/isChasing, NOT the new
        // broader isCapturing - the user only asked isCapturing to pick the crawl animation, not to
        // widen the glow trigger.
        this.headGlow.setBrightness((this.owner.isStaring() || this.owner.isChasing()) ? Brightness.FULL_BRIGHT : null);
    }
}
