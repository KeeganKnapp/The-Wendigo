package com.wendigo.entity;

import org.joml.Matrix4f;

import com.nyfaria.awcapi.entity.Orientation;

/**
 * {@link WendigoAnimationData} stores each bone transform as the same row-major 16-float layout
 * the reference datapack used ({@code [r00,r01,r02,tx, r10,r11,r12,ty, r20,r21,r22,tz, 0,0,0,1]}),
 * so it stays directly diffable against the mcfunction source it was generated from. JOML's
 * {@link Matrix4f} 16-arg constructor instead takes its arguments column-major (its own m/row/col
 * naming is transposed from ours -- see https://javadoc.io/doc/org.joml/joml Matrix4f docs), so this
 * is the one place that transposition happens.
 */
final class RigMatrices {
    private RigMatrices() {}

    static Matrix4f fromRowMajor(float[] m) {
        return new Matrix4f(
                m[0], m[4], m[8], m[12],
                m[1], m[5], m[9], m[13],
                m[2], m[6], m[10], m[14],
                m[3], m[7], m[11], m[15]
        );
    }

    /**
     * A pure-rotation matrix (no translation) representing an AWCAPI surface attachment
     * orientation, WITH the entity's own local-frame yaw (see WendigoEntity.isRestingOnFloor's own
     * doc comment - owner.getYRot() while climbing is a yaw relative to this same attachment frame,
     * not a world-absolute angle) already composed in - lets {@link WendigoVisual} tilt the whole
     * rig to lay flush against a wall or ceiling instead of always standing upright (Polymer's
     * {@code ItemDisplayElement} only exposes yaw/pitch, not roll - confirmed via javap against
     * GenericEntityElement - so an arbitrary attachment orientation has to be composed directly into
     * each bone's matrix instead).
     *
     * <p>{@link Orientation#getGlobal} (verified via javap: {@code localX.scale(local.x) +
     * localY.scale(local.y) + localZ.scale(local.z)}) is exactly {@code M * local} for a matrix M
     * whose columns are localX/localY/localZ - column0/1/2 below are exactly
     * {@code getGlobal(Ry(yaw)*(1,0,0))}/{@code getGlobal(Ry(yaw)*(0,1,0))}/
     * {@code getGlobal(Ry(yaw)*(0,0,1))} for a standard right-handed rotation-around-Y Ry(yaw)
     * (verified against JOML's own Matrix4f.rotateY empirically), computed directly via the linear
     * combination rather than by literally constructing Ry(yaw) and multiplying - same result,
     * avoids a redundant matrix multiply.
     *
     * <p>Yaw has to be baked in here, before any quaternion conversion, not composed afterward via a
     * separate rotation call on the un-rotated basis. AWCAPI's own ClimberComponent.calculateOrientation
     * (verified via javap) builds localZ as {@code M * (0,0,-1)}, not {@code M * (0,0,1)}, for a
     * proper-rotation M - so (localX, localY, localZ) as AWCAPI hands them out is always a
     * LEFT-handed basis (determinant -1, a reflection, not a rotation): harmless everywhere AWCAPI
     * itself uses these vectors (getGlobal only ever combines them linearly - direction math doesn't
     * care about handedness), but fatal for {@code Quaternionf.setFromUnnormalized}, which assumes a
     * proper rotation matrix and silently produces a near-degenerate, non-unit quaternion for a
     * reflection instead of erroring. Restoring right-handedness by negating one column has to
     * happen on the FINAL (yaw-inclusive) basis, computed here, not on the pre-yaw basis alone - an
     * earlier version of this fix negated localZ before ever touching yaw, which (confirmed via a
     * live test comparing real east-wall and south-wall data by hand) only happens to cancel out
     * near yaw=+/-90 degrees and leaves a ~180 degree residual error at yaw=0/180, since the
     * negated column's own contribution to the composed result is itself yaw-dependent.
     * <p>
     * The correction itself is UNCONDITIONAL (always negates c2), not a per-frame determinant check
     * - a live-reported bug traced to exactly that check: (c0,c1,c2) is {@code [localX localY localZ]
     * * Ry(yaw)}, and det(Ry(yaw)) = +1 for every yaw (a proper rotation) - so det(c0,c1,c2) =
     * det(localX,localY,localZ) * det(Ry(yaw)) = -1 * 1 = -1, ALWAYS, exactly, for every yaw and
     * every orientation (confirmed calculateOrientation has no conditional branching before
     * constructing localX/localY/localZ - the same formula runs for floor, wall, and ceiling alike,
     * so this isn't an orientation-dependent special case). A per-frame {@code det < 0} check on a
     * value that's mathematically guaranteed to already be exactly -1 does nothing useful when it's
     * working, and is pure fragility when it isn't: ordinary floating-point rounding in a
     * near-degenerate composition (a yaw/orientation combination where the columns become nearly
     * parallel) can push the COMPUTED det to the wrong side of zero despite the TRUE value always
     * being negative, flipping sign to +1 for that one frame with no corresponding change in the
     * real orientation data at all - exactly what live debug logging on getGroundSide()/
     * orientation.normal (which this composition doesn't even read) failed to explain: the rig
     * visibly flipped while every other signal stayed correct and unchanged the whole time. Removing
     * the runtime check entirely removes the only thing that could misfire.
     */
    static Matrix4f fromOrientationAndYaw(Orientation orientation, float yawDegrees) {
        double yawRad = Math.toRadians(yawDegrees);
        float cy = (float) Math.cos(yawRad);
        float sy = (float) Math.sin(yawRad);
        double lxx = orientation.localX.x, lxy = orientation.localX.y, lxz = orientation.localX.z;
        double lyx = orientation.localY.x, lyy = orientation.localY.y, lyz = orientation.localY.z;
        double lzx = orientation.localZ.x, lzy = orientation.localZ.y, lzz = orientation.localZ.z;

        float c0x = (float) (lxx * cy - lzx * sy);
        float c0y = (float) (lxy * cy - lzy * sy);
        float c0z = (float) (lxz * cy - lzz * sy);
        float c1x = (float) lyx;
        float c1y = (float) lyy;
        float c1z = (float) lyz;
        float c2x = (float) (lxx * sy + lzx * cy);
        float c2y = (float) (lxy * sy + lzy * cy);
        float c2z = (float) (lxz * sy + lzz * cy);

        return new Matrix4f(
                c0x, c0y, c0z, 0f,
                c1x, c1y, c1z, 0f,
                -c2x, -c2y, -c2z, 0f,
                0f, 0f, 0f, 1f
        );
    }
}
