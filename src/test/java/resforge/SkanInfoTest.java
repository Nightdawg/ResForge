package resforge;

import org.junit.jupiter.api.Test;
import resforge.io.MessageWriter;
import resforge.layers.SkanInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkanInfoTest {
    @Test
    void formatZeroDecodesNspeedAndCpfloatFrame() {
        MessageWriter w = header(12, 1, 2, 3.5);
        cpfloat(w, 0.75);
        w.string("root").uint16(1);
        cpfloat(w, 1.25);
        cpfloat(w, 1);
        cpfloat(w, -2);
        cpfloat(w, 3);
        cpfloat(w, Math.PI);
        cpfloat(w, 0);
        cpfloat(w, 0);
        cpfloat(w, 1);

        SkanInfo info = SkanInfo.parse(w.toByteArray());

        assertTrue(info.recognized);
        assertTrue(info.reachedEnd);
        assertEquals(0, info.fmt);
        assertEquals(12, info.id);
        assertEquals("pong", info.mode);
        assertEquals(3.5f, info.len, 1e-6);
        assertEquals(0.75, info.nspeed, 1e-6);
        assertEquals(1, info.totalFrames());
        SkanInfo.Track track = info.tracks.get(0);
        assertEquals("root", track.bone);
        assertEquals(1.25f, track.times[0], 1e-6);
        assertEquals(1f, track.trans[0][0], 1e-6);
        assertEquals(-2f, track.trans[0][1], 1e-6);
        assertEquals(3f, track.trans[0][2], 1e-6);
        assertEquals(0f, track.rot[0][0], 1e-5);
        assertEquals(1f, track.rot[0][3], 1e-5);
    }

    @Test
    void formatZeroWalksAllSupportedControlEvents() {
        MessageWriter w = header(4, 0, 1, 2);
        w.string("{ctl}").uint16(5);

        eventTime(w, 0);
        w.uint8(0).string("gfx/spawn").uint16(7).uint8(2).bytes(new byte[]{1, 2});

        eventTime(w, 0.25);
        w.uint8(1).string("trigger");

        eventTime(w, 0.5);
        w.uint8(2).string("gfx/equipped").uint16(8).uint8(0).uint8(1).string("hand");

        eventTime(w, 0.75);
        w.uint8(3).uint8(6).string("overlay").string("gfx/overlay").uint16(9)
                .uint8(1).uint8(42);

        eventTime(w, 1);
        w.uint8(4).string("overlay");

        SkanInfo info = SkanInfo.parse(w.toByteArray());

        assertTrue(info.recognized);
        assertTrue(info.reachedEnd);
        assertEquals(1, info.fxTracks.size());
        assertEquals(5, info.fxTracks.get(0).events);
        assertEquals(List.of("gfx/spawn", "gfx/equipped", "gfx/overlay"),
                info.fxTracks.get(0).refs);
        assertTrue(info.tracks.isEmpty());
    }

    @Test
    void malformedPayloadsFailGracefully() {
        MessageWriter badMode = header(1, 0, 7, 1);
        MessageWriter unknownEvent = header(1, 0, 0, 1);
        unknownEvent.string("{ctl}").uint16(1);
        eventTime(unknownEvent, 0);
        unknownEvent.uint8(9);

        for(byte[] payload : List.of(
                new byte[0],
                new byte[]{1, 0, 0},
                badMode.toByteArray(),
                unknownEvent.toByteArray())) {
            SkanInfo info = SkanInfo.parse(payload);
            assertFalse(info.recognized);
            assertFalse(info.reachedEnd);
        }
    }

    private static MessageWriter header(int id, int flags, int mode, double len) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint8(flags).uint8(mode);
        cpfloat(w, len);
        return w;
    }

    private static void eventTime(MessageWriter w, double time) {
        cpfloat(w, time);
    }

    private static void cpfloat(MessageWriter w, double value) {
        if(value == 0) {
            w.int8(-128).int32(0);
            return;
        }
        boolean negative = value < 0;
        double absolute = Math.abs(value);
        int exponent = (int) Math.floor(Math.log(absolute) / Math.log(2));
        double normalized = absolute / Math.pow(2, exponent);
        long mantissa = Math.round((normalized - 1) * 2147483648.0);
        if(mantissa >= 2147483648L) {
            exponent++;
            mantissa = 0;
        }
        int bits = (int) (mantissa & 0x7fffffffL);
        if(negative)
            bits |= 0x80000000;
        w.int8(exponent).int32(bits);
    }
}
