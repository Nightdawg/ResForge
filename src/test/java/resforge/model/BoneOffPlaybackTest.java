package resforge.model;

import org.junit.jupiter.api.Test;
import resforge.io.MessageWriter;
import resforge.res.Layer;
import resforge.res.ResContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoneOffPlaybackTest {
    @Test
    void weaponFollowsAnimatedEquipBoneAndKeepsPlayerSkinning() {
        BoneOffPlayback playback = playback(new MessageWriter().string("h")
                .uint8(2).string("hand")
                .uint8(16).float32(0).float32(2).float32(0)
                .toByteArray());

        BoneOffPlayback.Pose pose = playback.pose(playback.clips().get(0), 0.5f);

        assertEquals(1f, pose.positions()[0], 2e-3f,
                "the player remains skinned by the animation");
        assertEquals(2f, pose.positions()[9], 2e-3f,
                "the weapon follows the animated hand at x=2");
        assertEquals(2f, pose.positions()[10], 2e-3f,
                "the local boneoff translation is composed after the hand transform");
    }

    @Test
    void everySupportedBoneOffOpcodeCanBePreviewed() {
        MessageWriter boneOff = new MessageWriter().string("h")
                .uint8(2).string("root")
                .uint8(0).cpfloat(1).cpfloat(0).cpfloat(0)
                .uint8(16).float32(0).float32(1).float32(0)
                .uint8(1).cpfloat(Math.PI / 4).cpfloat(0).cpfloat(0).cpfloat(1)
                .uint8(17).uint16(0x2000).int16(0).int16(0)
                .uint8(3).cpfloat(1).cpfloat(0).cpfloat(0).string("root").string("hand")
                .uint8(19).int16(0x7fff).int16(0).string("root").string("hand")
                .uint8(4)
                .uint8(5).float32(1.5f);

        BoneOffPlayback playback = playback(boneOff.toByteArray());
        BoneOffPlayback.Pose pose = playback.pose(playback.clips().get(0), 0);

        for(float value : pose.positions())
            assertTrue(Float.isFinite(value));
    }

    @Test
    void missingReferencedBoneIsRejectedBeforeOpeningPreview() {
        byte[] boneOff = new MessageWriter().string("h")
                .uint8(2).string("missing").toByteArray();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> playback(boneOff));

        assertTrue(failure.getMessage().contains("missing bone"));
    }

    @Test
    void boneAlignHandlesOppositeDirections() {
        byte[] boneOff = new MessageWriter().string("h")
                .uint8(3).cpfloat(-1).cpfloat(0).cpfloat(0)
                .string("root").string("hand").toByteArray();
        BoneOffPlayback playback = playback(boneOff);

        BoneOffPlayback.Pose pose = playback.pose(playback.clips().get(0), 0);

        assertEquals(-1f, pose.positions()[12], 1e-5f);
    }

    @Test
    void livePayloadUpdateIsAtomicAndRejectedUpdatesKeepLastTransform() {
        BoneOffPlayback playback = playback(new MessageWriter().string("h")
                .uint8(2).string("root").toByteArray());
        byte[] moved = new MessageWriter().string("h")
                .uint8(2).string("root")
                .uint8(16).float32(0).float32(4).float32(0).toByteArray();
        playback.updateBoneOff(moved);

        BoneOffPlayback.Pose updated = playback.pose(playback.clips().get(0), 0);
        assertEquals(4f, updated.positions()[10], 1e-6f);

        byte[] invalid = new MessageWriter().string("h")
                .uint8(2).string("missing").toByteArray();
        assertThrows(IllegalArgumentException.class, () -> playback.updateBoneOff(invalid));
        BoneOffPlayback.Pose retained = playback.pose(playback.clips().get(0), 0);
        assertEquals(4f, retained.positions()[10], 1e-6f);
    }

    private static BoneOffPlayback playback(byte[] boneOff) {
        ModelGeometry player = ModelGeometry.forAnimation(playerModel(), Integer.MAX_VALUE);
        ModelGeometry weapon = ModelGeometry.from(weaponModel());
        assertNotNull(player);
        assertNotNull(weapon);
        BoneOffPlayback playback =
                BoneOffPlayback.from(player, weapon, skeleton(), animation(), boneOff);
        assertNotNull(playback);
        assertEquals(2, playback.geometry().triangleCount);
        return playback;
    }

    private static ResContainer playerModel() {
        MessageWriter vbuf = baseVbuf();
        vbuf.string("bones2").uint8(1).string("f4").uint8(1);
        vbuf.string("root").uint16(3).uint16(0)
                .float32(1).float32(1).float32(1)
                .uint16(0).uint16(0);
        vbuf.string("");
        return model(vbuf.toByteArray());
    }

    private static ResContainer weaponModel() {
        MessageWriter vbuf = baseVbuf();
        return model(vbuf.toByteArray());
    }

    private static MessageWriter baseVbuf() {
        MessageWriter writer = new MessageWriter().uint8(0).uint16(3);
        writer.string("pos2").uint8(1).string("f4");
        for(float value : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            writer.float32(value);
        writer.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < 3; i++)
            writer.float32(0).float32(0).float32(1);
        return writer;
    }

    private static ResContainer model(byte[] vbuf) {
        ResContainer resource = new ResContainer(1);
        resource.layers.add(new Layer("vbuf2", vbuf));
        resource.layers.add(new Layer("mesh", new MessageWriter()
                .uint8(16).uint16(1).int16(-1).int16(0)
                .uint16(0).uint16(1).uint16(2).toByteArray()));
        return resource;
    }

    private static ResContainer skeleton() {
        MessageWriter writer = new MessageWriter().string("\u0001");
        writer.string("root").string("")
                .float32(0).float32(0).float32(0)
                .uint16(0).int16(0).int16(0);
        writer.string("hand").string("root")
                .float32(1).float32(0).float32(0)
                .uint16(0).int16(0).int16(0);
        ResContainer resource = new ResContainer(1);
        resource.layers.add(new Layer("skel", writer.toByteArray()));
        return resource;
    }

    private static ResContainer animation() {
        MessageWriter writer = new MessageWriter()
                .int16(0).uint8(2).uint8(1).float32(2)
                .string("root").uint16(2);
        writer.uint16(0).float16(0).float16(0).float16(0)
                .uint16(0).int16(0).int16(0);
        writer.uint16(0x8000).float16(2).float16(0).float16(0)
                .uint16(0).int16(0).int16(0);
        ResContainer resource = new ResContainer(1);
        resource.layers.add(new Layer("skan", writer.toByteArray()));
        return resource;
    }
}
