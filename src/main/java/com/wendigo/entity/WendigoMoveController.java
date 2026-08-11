package com.wendigo.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import com.nyfaria.awcapi.entity.movement.ClimberMoveController;

/**
 * Fixes the recurring "flips to the wrong normal" bug - root-caused via decompiled bytecode
 * comparison against Stormy's Spiders' own, independent (non-AWCAPI) climbing implementation, which
 * has never had this bug, and against AWCAPI's own AdvancedClimberPathNavigator.followThePath()
 * (confirmed via full decompile, not guessed): every tick, it reads the CURRENT path node and only
 * calls this class's own rich {@code setMoveTo(x,y,z,block,side,speed)} - the one that actually
 * carries a real attachment side - when that node is a {@code DirectionalPathPoint} AND that node's
 * own {@code getPathSide()} is non-null. Whenever either check fails (a plain Node, or a
 * DirectionalPathPoint whose own side wasn't resolved), it falls back to the plain vanilla
 * {@code MoveControl.setWantedPosition(x,y,z,speed)} instead - which {@link ClimberMoveController}'s
 * own {@code setWantedPosition} override (confirmed via decompile) forwards straight into
 * {@code setMoveTo(x,y,z,null,null,speed)}, leaving {@link ClimberMoveController#side} NULL for the
 * rest of that move segment. Live-diagnosed (via a purpose-built CHASE_REPATH/ONGROUND_TRANSITION
 * log correlation) as the actual mechanism behind the ceiling-flip bug: the user's own precise
 * observation - "ALL paths created when he's already on the ceiling lead to detaching, not just some
 * of them" - matches exactly, since combat.chase/internal.chase_until_light repath toward the
 * player's raw position (through PathNavigation's own generic entry points, not
 * AdvancedClimberPathNavigator's own richer directional-node path), which readily produces plain
 * Nodes or side-less DirectionalPathPoints, unlike internal.orbit's own wander (which never flips)
 * - though live testing hasn't yet pinned down exactly why wander's own moveTo calls consistently
 * land on directional nodes and chase's don't; this fix sidesteps that question entirely rather than
 * depending on an answer to it.
 *
 * <p>Overrides {@code setMoveTo} itself (not {@code setWantedPosition}) - the single choke point both
 * of AdvancedClimberPathNavigator's own two call sites funnel through - substituting this entity's own
 * reliable {@link WendigoEntity#getOrientation()}{@code .normal} (snapped to the nearest axis, the
 * same signal PlanRunner.resolveChaseDestination already trusts) ONLY when AWCAPI's own side would
 * otherwise be null, so the genuinely-good DirectionalPathPoint case (a real side already resolved)
 * passes through completely unchanged.
 */
public class WendigoMoveController extends ClimberMoveController<WendigoEntity> {
    // How close to the current move target (blocks squared) counts as "arriving" - matches the
    // narrow window Stormy's Spiders' own fix targets (right as a path segment completes), not
    // meant to replicate its exact numeric threshold byte-for-byte - the intent (catch the cached
    // side going stale right at arrival) is what matters, not an exact distance match.
    private static final double ARRIVAL_DISTANCE_SQR = 0.01;

    public WendigoMoveController(WendigoEntity mob) {
        super(mob);
    }

    @Override
    public void setMoveTo(double x, double y, double z, BlockPos block, Direction side, double speedModifier) {
        Direction resolvedSide = side != null ? side : reliableSurfaceDirection();
        super.setMoveTo(x, y, z, block, resolvedSide, speedModifier);
    }

    @Override
    public void tick() {
        if (this.hasWanted() && this.side != null) {
            double distanceSqr = this.mob.position().distanceToSqr(this.getWantedX(), this.getWantedY(), this.getWantedZ());
            if (distanceSqr < ARRIVAL_DISTANCE_SQR) {
                this.side = reliableSurfaceDirection();
            }
        }
        super.tick();
    }

    /** WendigoEntity.getGroundSide() (NOT IAdvancedClimber's raw default, and deliberately NOT
     * getOrientation().normal either) - live WDIAG evidence during a "constant bearing, forever"
     * chase-glitch repro showed orientation.normal frozen at the plain (0,1,0) floor default for the
     * entire episode, so using it here would just feed the same stuck value straight back into
     * movement steering. WendigoEntity's own getGroundSide() override is the one signal this whole
     * class already trusts for exactly this reason (see its own doc comment: confirmed via live
     * debugging to stay correct and stable through an entire ceiling-flip reproduction, recomputed
     * from real per-tick collision geometry, no dependency on the cosmetic orientation blend at all).
     * getGroundSide() uses the opposite convention from orientation.normal (points TOWARD the surface,
     * not away from it - confirmed via that method's own doc comment), so it's negated here to match
     * what setMoveTo's own "side" parameter expects (the same away-from-surface convention
     * orientation.normal uses, per AWCAPI's own DirectionalPathPoint.getPathSide() contract). */
    private Direction reliableSurfaceDirection() {
        return this.climber.getGroundSide().getOpposite();
    }
}
