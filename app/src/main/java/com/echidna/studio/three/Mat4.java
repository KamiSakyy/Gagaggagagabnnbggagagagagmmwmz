package com.echidna.studio.three;

/**
 * The 4x4 matrix and quaternion arithmetic the 3D stage needs, in plain Java.
 *
 * <p>Matrices are column major float arrays, exactly the layout glTF stores and OpenGL expects, so
 * nothing has to be transposed between the file, the animation and the shader.</p>
 */
public final class Mat4 {

    private Mat4() {
    }

    public static float[] identity() {
        final float[] m = new float[16];
        m[0] = 1f;
        m[5] = 1f;
        m[10] = 1f;
        m[15] = 1f;
        return m;
    }

    public static void identity(float[] m) {
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = 1f;
        m[5] = 1f;
        m[10] = 1f;
        m[15] = 1f;
    }

    /**
     * {@code out = a * b}, both column major.
     *
     * <p>The result may be the same array as one of the inputs: chains like {@code M = M * N} are what
     * skinning is made of, and writing straight into the output would read half overwritten numbers.
     * The scratch copy below is what keeps {@code M = M * N} correct.</p>
     */
    public static void multiply(float[] out, float[] a, float[] b) {
        // The product is read from a and b alone; only a result that shares an array with one of them
        // needs a staging copy, which is exactly the "M = M * N" case skinning is made of.
        final float[] staging = out == a || out == b ? new float[16] : null;
        final float[] target = staging != null ? staging : out;
        for (int c = 0; c < 4; c++) {
            final int cb = c * 4;
            for (int r = 0; r < 4; r++) {
                target[cb + r] = a[r] * b[cb]
                        + a[4 + r] * b[cb + 1]
                        + a[8 + r] * b[cb + 2]
                        + a[12 + r] * b[cb + 3];
            }
        }
        if (staging != null) {
            System.arraycopy(staging, 0, out, 0, 16);
        }
    }

    /** Builds a transform from translation, a quaternion rotation and a scale. */
    public static void fromTrs(float[] out, float[] t, float[] q, float[] s) {
        final float x = q[0];
        final float y = q[1];
        final float z = q[2];
        final float w = q[3];
        final float x2 = x + x;
        final float y2 = y + y;
        final float z2 = z + z;
        final float xx = x * x2;
        final float xy = x * y2;
        final float xz = x * z2;
        final float yy = y * y2;
        final float yz = y * z2;
        final float zz = z * z2;
        final float wx = w * x2;
        final float wy = w * y2;
        final float wz = w * z2;
        final float sx = s[0];
        final float sy = s[1];
        final float sz = s[2];

        out[0] = (1f - (yy + zz)) * sx;
        out[1] = (xy + wz) * sx;
        out[2] = (xz - wy) * sx;
        out[3] = 0f;

        out[4] = (xy - wz) * sy;
        out[5] = (1f - (xx + zz)) * sy;
        out[6] = (yz + wx) * sy;
        out[7] = 0f;

        out[8] = (xz + wy) * sz;
        out[9] = (yz - wx) * sz;
        out[10] = (1f - (xx + yy)) * sz;
        out[11] = 0f;

        out[12] = t[0];
        out[13] = t[1];
        out[14] = t[2];
        out[15] = 1f;
    }

    /** A copy of {@code src} without its translation, for normals. */
    public static void upperLeft3x3(float[] out3x3, float[] src) {
        out3x3[0] = src[0];
        out3x3[1] = src[1];
        out3x3[2] = src[2];
        out3x3[3] = src[4];
        out3x3[4] = src[5];
        out3x3[5] = src[6];
        out3x3[6] = src[8];
        out3x3[7] = src[9];
        out3x3[8] = src[10];
    }

    /** Inverts an affine matrix (rotation plus translation, no shear): used for bind poses. */
    public static void invertAffine(float[] out, float[] m) {
        final float a00 = m[0];
        final float a01 = m[1];
        final float a02 = m[2];
        final float a10 = m[4];
        final float a11 = m[5];
        final float a12 = m[6];
        final float a20 = m[8];
        final float a21 = m[9];
        final float a22 = m[10];

        final float det = a00 * (a11 * a22 - a12 * a21)
                - a01 * (a10 * a22 - a12 * a20)
                + a02 * (a10 * a21 - a11 * a20);
        if (Math.abs(det) < 1e-12f) {
            identity(out);
            return;
        }
        final float inv = 1f / det;
        out[0] = (a11 * a22 - a12 * a21) * inv;
        out[1] = (a02 * a21 - a01 * a22) * inv;
        out[2] = (a01 * a12 - a02 * a11) * inv;
        out[3] = 0f;
        out[4] = (a12 * a20 - a10 * a22) * inv;
        out[5] = (a00 * a22 - a02 * a20) * inv;
        out[6] = (a02 * a10 - a00 * a12) * inv;
        out[7] = 0f;
        out[8] = (a10 * a21 - a11 * a20) * inv;
        out[9] = (a01 * a20 - a00 * a21) * inv;
        out[10] = (a00 * a11 - a01 * a10) * inv;
        out[11] = 0f;

        final float tx = m[12];
        final float ty = m[13];
        final float tz = m[14];
        out[12] = -(out[0] * tx + out[4] * ty + out[8] * tz);
        out[13] = -(out[1] * tx + out[5] * ty + out[9] * tz);
        out[14] = -(out[2] * tx + out[6] * ty + out[10] * tz);
        out[15] = 1f;
    }

    public static float[] transformPoint(float[] m, float x, float y, float z) {
        return new float[]{
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    /** A quaternion from Euler angles in degrees. The order is Y, X, Z: yaw, pitch, roll. */
    public static float[] quaternionFromEuler(float yawDeg, float pitchDeg, float rollDeg) {
        final float halfYaw = (float) Math.toRadians(yawDeg) * 0.5f;
        final float halfPitch = (float) Math.toRadians(pitchDeg) * 0.5f;
        final float halfRoll = (float) Math.toRadians(rollDeg) * 0.5f;
        final float cy = (float) Math.cos(halfYaw);
        final float sy = (float) Math.sin(halfYaw);
        final float cp = (float) Math.cos(halfPitch);
        final float sp = (float) Math.sin(halfPitch);
        final float cr = (float) Math.cos(halfRoll);
        final float sr = (float) Math.sin(halfRoll);
        return new float[]{
                cy * sp * cr + sy * cp * sr,     // x
                sy * cp * cr - cy * sp * sr,     // y
                cy * cp * sr - sy * sp * cr,     // z
                cy * cp * cr + sy * sp * sr};    // w
    }

    public static float[] multiplyQuaternions(float[] a, float[] b) {
        return new float[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    public static float[] normalizeQuaternion(float[] q) {
        final float length = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (length < 1e-8f) {
            return new float[]{0f, 0f, 0f, 1f};
        }
        return new float[]{q[0] / length, q[1] / length, q[2] / length, q[3] / length};
    }

    /** A rotation of {@code degrees} around an axis, as a quaternion. */
    public static float[] quaternionFromAxisAngle(float x, float y, float z, float degrees) {
        final float half = (float) Math.toRadians(degrees) * 0.5f;
        final float sin = (float) Math.sin(half);
        return new float[]{x * sin, y * sin, z * sin, (float) Math.cos(half)};
    }

    public static void lerp(float[] out, float[] a, float[] b, float t) {
        for (int i = 0; i < 16; i++) {
            out[i] = a[i] + (b[i] - a[i]) * t;
        }
    }
}
