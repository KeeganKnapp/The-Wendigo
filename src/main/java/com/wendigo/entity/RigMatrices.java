package com.wendigo.entity;

import org.joml.Matrix4f;

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
}
