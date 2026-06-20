package resforge;

import resforge.io.MessageWriter;
import resforge.layers.AudioHeaderCodec;
import resforge.layers.AudioInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioHeaderTest {
    private static final byte[] OGG = {0x4F, 0x67, 0x67, 0x53, 1, 2, 3, 4, 5};   // "OggS" + filler

    /** A ver-2 audio header: id + uint16 volume, then the Ogg stream. */
    private static byte[] ver2(String id, int vol) {
        MessageWriter w = new MessageWriter();
        w.uint8(2).string(id).uint16(vol).bytes(OGG);
        return w.toByteArray();
    }

    @Test
    void parsesVer2AndReEncodesByteIdentical() {
        byte[] payload = ver2("cl", 1000);
        AudioHeaderCodec h = AudioHeaderCodec.parse(payload);
        assertTrue(h.editable);
        assertEquals(2, h.ver);
        assertEquals("cl", h.id);
        assertTrue(h.hasVol);
        assertEquals(1000, h.vol);
        assertEquals(1.0, h.bvol(), 1e-9);
        assertArrayEquals(payload, h.encode());
    }

    @Test
    void editingVolumeAndIdKeepsOggStream() {
        byte[] payload = ver2("cl", 1000);
        AudioHeaderCodec h = AudioHeaderCodec.parse(payload);
        byte[] edited = h.encodeWithBvol("blu", 0.5);   // half volume, new id

        AudioInfo ai = AudioInfo.parse(edited);
        assertEquals("blu", ai.id);
        assertEquals(0.5, ai.bvol, 1e-9);
        assertArrayEquals(OGG, Arrays.copyOfRange(edited, ai.audioOffset, edited.length));

        AudioHeaderCodec h2 = AudioHeaderCodec.parse(edited);
        assertEquals(500, h2.vol);
    }

    @Test
    void ver1HasNoVolumeButIdEditable() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).string("snd").bytes(OGG);   // ver 1: no volume field
        byte[] payload = w.toByteArray();
        AudioHeaderCodec h = AudioHeaderCodec.parse(payload);
        assertTrue(h.editable);
        assertFalse(h.hasVol);
        assertArrayEquals(payload, h.encode());

        byte[] edited = h.encodeWith("snd2", 0);    // vol ignored for ver 1
        assertEquals("snd2", AudioInfo.parse(edited).id);
    }

    @Test
    void ver3IsNotEditable() {
        MessageWriter w = new MessageWriter();
        w.uint8(3).string("cl").string("").bytes(OGG);   // ver 3: typed metadata
        assertFalse(AudioHeaderCodec.parse(w.toByteArray()).editable);
    }

    @Test
    void rejectsOutOfRangeVolume() {
        AudioHeaderCodec h = AudioHeaderCodec.parse(ver2("cl", 1000));
        assertThrows(IllegalArgumentException.class, () -> h.encodeWith("cl", 70000));
    }
}
