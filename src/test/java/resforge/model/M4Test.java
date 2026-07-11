package resforge.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class M4Test {
    private static final float EPSILON = 1e-5f;

    @Test
    void identityHasColumnMajorDiagonal() {
        assertArrayEquals(new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        }, M4.identity());
    }

    @Test
    void multiplicationUsesLeftToRightTransformComposition() {
        float[] rotation = M4.fromQuat(0, 0, 0, 1);
        float[] translation = M4.translate(2, 3, 4);

        assertArrayEquals(new float[]{
                -1, 0, 0, 0,
                0, -1, 0, 0,
                0, 0, 1, 0,
                -2, -3, 4, 1
        }, M4.mul(rotation, translation), EPSILON);
        assertArrayEquals(new float[]{
                -1, 0, 0, 0,
                0, -1, 0, 0,
                0, 0, 1, 0,
                2, 3, 4, 1
        }, M4.mul(translation, rotation), EPSILON);
    }

    @Test
    void quaternionProducesKnownQuarterTurnAroundZ() {
        float rootHalf = (float) Math.sqrt(0.5);

        assertArrayEquals(new float[]{
                0, 1, 0, 0,
                -1, 0, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        }, M4.fromQuat(rootHalf, 0, 0, rootHalf), EPSILON);
    }

    @Test
    void quaternionMultiplicationUsesHamiltonProductOrder() {
        float rootHalf = (float) Math.sqrt(0.5);
        float[] xQuarterTurn = {rootHalf, rootHalf, 0, 0};
        float[] yQuarterTurn = {rootHalf, 0, rootHalf, 0};

        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f, 0.5f},
                M4.qmul(xQuarterTurn, yQuarterTurn), EPSILON);
    }

    @Test
    void rigidTransformTimesInverseIsApproximatelyIdentity() {
        float[] quaternion = M4.quat(0, 0, 1, 0.73f);
        float[] rotation = M4.fromQuat(
                quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
        float[] transform = M4.mul(M4.translate(3.5f, -2.25f, 8), rotation);

        assertArrayEquals(M4.identity(), M4.mul(transform, M4.rigidInverse(transform)), EPSILON);
    }
}
