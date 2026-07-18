package resforge.model;

/**
 * Minimal column-major 4×4 matrix maths for glTF export (skeleton bind matrices).
 * Matrices are {@code float[16]} in glTF's column-major order: element
 * {@code m[col*4 + row]}. Only the operations the skin builder needs are provided:
 * identity, multiply, translate, rotation-from-quaternion, and the inverse of a
 * rigid (rotation + translation) transform.
 */
final class M4 {
    private M4() {
    }

    static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
    }

    /** C = A · B (both column-major). */
    static float[] mul(float[] a, float[] b) {
        float[] c = new float[16];
        for(int col = 0; col < 4; col++) {
            for(int row = 0; row < 4; row++) {
                float s = 0;
                for(int k = 0; k < 4; k++)
                    s += a[k * 4 + row] * b[col * 4 + k];
                c[col * 4 + row] = s;
            }
        }
        return c;
    }

    static float[] translate(float x, float y, float z) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    static float[] rotate(float x, float y, float z, float angle) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if(length < 1e-12f)
            return identity();
        return fromQuat((float) Math.cos(angle / 2.0),
                (float) Math.sin(angle / 2.0) * x / length,
                (float) Math.sin(angle / 2.0) * y / length,
                (float) Math.sin(angle / 2.0) * z / length);
    }

    static float[] scale(float value) {
        float[] m = identity();
        m[0] = m[5] = m[10] = value;
        return m;
    }

    static float[] nullRotation(float[] source) {
        float[] result = source.clone();
        result[0] = result[5] = result[10] = 1;
        result[1] = result[2] = result[4] = result[6] = result[8] = result[9] = 0;
        return result;
    }

    /** Rotation matrix from a quaternion given as {@code [w, x, y, z]}. */
    static float[] fromQuat(float w, float x, float y, float z) {
        float[] m = identity();
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        m[0] = 1 - 2 * (yy + zz);
        m[1] = 2 * (xy + wz);
        m[2] = 2 * (xz - wy);
        m[4] = 2 * (xy - wz);
        m[5] = 1 - 2 * (xx + zz);
        m[6] = 2 * (yz + wx);
        m[8] = 2 * (xz + wy);
        m[9] = 2 * (yz - wx);
        m[10] = 1 - 2 * (xx + yy);
        return m;
    }

    /** Quaternion {@code [w, x, y, z]} from a (normalised) axis + angle. */
    static float[] quat(float ax, float ay, float az, float angle) {
        float s = (float) Math.sin(angle / 2.0);
        return new float[]{(float) Math.cos(angle / 2.0), s * ax, s * ay, s * az};
    }

    /** Quaternion product a·b, both {@code [w, x, y, z]}. */
    static float[] qmul(float[] a, float[] b) {
        float aw = a[0], ax = a[1], ay = a[2], az = a[3];
        float bw = b[0], bx = b[1], by = b[2], bz = b[3];
        return new float[]{
                aw * bw - ax * bx - ay * by - az * bz,
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw};
    }

    /** Inverse of a rigid transform (orthonormal rotation + translation). */
    static float[] rigidInverse(float[] m) {
        float[] r = identity();
        // R^T (transpose the upper-left 3×3)
        for(int i = 0; i < 3; i++)
            for(int j = 0; j < 3; j++)
                r[j * 4 + i] = m[i * 4 + j];
        // t' = -R^T · t
        float tx = m[12], ty = m[13], tz = m[14];
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz);
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz);
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz);
        return r;
    }
}
