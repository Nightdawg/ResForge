package resforge;

import org.junit.jupiter.api.Test;
import resforge.io.MessageWriter;
import resforge.layers.SkanInfo;
import resforge.model.ModelGeometry;
import resforge.model.SkanPlayback;
import resforge.res.Layer;
import resforge.res.ResContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkanPlaybackTest {
    private static byte[] vbuf() {
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(int i = 0; i < 3; i++)
            w.float32(0).float32(0).float32(1);
        w.string("bones2").uint8(1).string("f4").uint8(1);
        w.string("root").uint16(3).uint16(0)
                .float32(1).float32(1).float32(1)
                .uint16(0).uint16(0);
        w.string("");
        return w.toByteArray();
    }

    private static byte[] mesh() {
        return new MessageWriter().uint8(16).uint16(1).int16(-1).int16(0)
                .uint16(0).uint16(1).uint16(2).toByteArray();
    }

    private static byte[] skeleton() {
        return new MessageWriter().string("\u0001").string("root").string("")
                .float32(0).float32(0).float32(0)
                .uint16(0).int16(0).int16(0).toByteArray();
    }

    private static byte[] animation() {
        MessageWriter w = new MessageWriter();
        w.int16(0).uint8(2).uint8(1).float32(2);
        w.string("root").uint16(2);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.uint16(0x8000).float16(2).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    private static byte[] secondAnimation() {
        MessageWriter w = new MessageWriter();
        w.int16(1).uint8(2).uint8(1).float32(2);
        w.string("root").uint16(2);
        w.uint16(0).float16(0).float16(0).float16(0);
        w.uint16(0).int16(0).int16(0);
        w.uint16(0x8000).float16(0).float16(2).float16(0);
        w.uint16(0).int16(0).int16(0);
        return w.toByteArray();
    }

    private static SkanPlayback playback() {
        return playback(false);
    }

    private static SkanPlayback playback(boolean twoClips) {
        ResContainer model = new ResContainer(1);
        model.layers.add(new Layer("vbuf2", vbuf()));
        model.layers.add(new Layer("mesh", mesh()));
        ResContainer skeleton = new ResContainer(1);
        skeleton.layers.add(new Layer("skel", skeleton()));
        ResContainer animation = new ResContainer(1);
        animation.layers.add(new Layer("skan", animation()));
        if(twoClips)
            animation.layers.add(new Layer("skan", secondAnimation()));
        ModelGeometry geometry = ModelGeometry.forAnimation(model, Integer.MAX_VALUE);
        assertNotNull(geometry);
        return SkanPlayback.from(geometry, skeleton, animation);
    }

    @Test
    void modelGeometryRetainsSoupSkinInfluences() {
        SkanPlayback playback = playback();
        assertNotNull(playback);
        SkanInfo clip = playback.clips().get(0);

        SkanPlayback.Pose pose = playback.pose(clip, 0.5f);

        assertEquals(1f, pose.positions()[0], 2e-3f);
        assertEquals(2f, pose.positions()[3], 2e-3f);
        assertEquals(1f, pose.normals()[2], 1e-6f);
    }

    @Test
    void loopAndPingPongAdvanceAcrossLargeSteps() {
        SkanPlayback.TimeState loop = SkanPlayback.advance(0.8f, false, 2.5f, 1, "loop");
        assertEquals(0.3f, loop.time(), 1e-6f);
        assertFalse(loop.done());

        SkanPlayback.TimeState pongLoop =
                SkanPlayback.advance(0.8f, false, 2.5f, 1, "pong-loop");
        assertEquals(0.7f, pongLoop.time(), 1e-6f);
        assertTrue(pongLoop.backward());

        SkanPlayback.TimeState pong = SkanPlayback.advance(0.8f, false, 2.5f, 1, "pong");
        assertEquals(0f, pong.time(), 1e-6f);
        assertTrue(pong.done());
    }

    @Test
    void compatibleClipPartsCombineIntoOnePose() {
        SkanPlayback playback = playback(true);
        assertTrue(playback.canCombineAll());

        SkanPlayback.Pose pose = playback.pose(playback.clips(), 0.5f);

        assertEquals(1f, pose.positions()[0], 2e-3f);
        assertEquals(1f, pose.positions()[1], 2e-3f);
    }

    @Test
    void mixedTimingLayersStillCombineAndUseTheirOwnPlaybackModes() {
        SkanPlayback playback = playback(true);
        SkanInfo second = playback.clips().get(1);
        second.len = 1;
        second.mode = "pong-loop";

        assertTrue(playback.canCombineAll());
        assertFalse(SkanPlayback.hasCommonTiming(playback.clips()));
        assertEquals(2f, SkanPlayback.combinedLength(playback.clips()), 1e-6f);
        assertEquals(1.5f, SkanPlayback.clipTime(1.5f, 2, "once"), 1e-6f);
        assertEquals(0.5f, SkanPlayback.clipTime(1.5f, 1, "pong-loop"), 1e-6f);

        SkanPlayback.Pose pose = playback.pose(playback.clips(), 1.5f);

        assertEquals(1f, pose.positions()[0], 2e-3f,
                "the two-second once clip samples at 1.5 seconds");
        assertEquals(1f, pose.positions()[1], 2e-3f,
                "the one-second pong-loop clip evaluates on its return leg");
    }

    @Test
    void combinedTimelineIncludesTheReturnLegOfTheLongestPingPongClip() {
        SkanPlayback playback = playback(true);
        SkanInfo first = playback.clips().get(0);
        first.len = 0.5f;
        SkanInfo second = playback.clips().get(1);
        second.len = 1;
        second.mode = "pong-loop";

        assertEquals(2f, SkanPlayback.combinedLength(playback.clips()), 1e-6f);
        SkanPlayback.Pose pose = playback.pose(playback.clips(), 1.5f);

        assertEquals(1f, pose.positions()[1], 2e-3f,
                "pose evaluation must not clamp the shared timeline to the raw one-second length");
    }

    @Test
    void onceStopsAtClipEnd() {
        SkanPlayback.TimeState state = SkanPlayback.advance(0.75f, false, 1, 1, "once");
        assertEquals(1f, state.time(), 1e-6f);
        assertTrue(state.done());
    }
}
