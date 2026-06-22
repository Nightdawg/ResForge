package resforge;

import resforge.io.Json;
import resforge.io.MessageWriter;
import resforge.layers.AnimCodec;
import resforge.layers.ImageInfo;
import resforge.layers.Mat2Codec;
import resforge.layers.NegCodec;
import resforge.res.Packer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edit-time validation: out-of-range JSON edits must fail loudly, not silently wrap. */
class EditValidationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String s) {
        return (Map<String, Object>) Json.parse(s);
    }

    private static byte[] animPayload() {
        MessageWriter w = new MessageWriter();
        w.int16(-1);          // id
        w.uint16(100);        // delay
        w.uint16(2);          // frame count
        w.int16(5).int16(6);  // frames
        return w.toByteArray();
    }

    @Test
    void validAnimEditStillRoundTrips() {
        // Sanity: an in-range edit encodes fine and reproduces the bytes.
        byte[] payload = animPayload();
        Map<String, Object> model = AnimCodec.decode(payload);
        assertArrayEquals(payload, AnimCodec.encode(model));
    }

    @Test
    void animDelayOutOfUint16RangeIsRejected() {
        Map<String, Object> bad = json("{\"id\":-1,\"delay\":70000,\"frames\":[5,6]}");
        assertThrows(RuntimeException.class, () -> AnimCodec.encode(bad));
    }

    @Test
    void animFrameOutOfInt16RangeIsRejected() {
        Map<String, Object> bad = json("{\"id\":-1,\"delay\":100,\"frames\":[40000]}");
        assertThrows(RuntimeException.class, () -> AnimCodec.encode(bad));
    }

    @Test
    void negTooManyEndpointGroupsIsRejected() {
        StringBuilder groups = new StringBuilder();
        for(int i = 0; i < 300; i++)
            groups.append(i == 0 ? "" : ",").append("{\"id\":0,\"coords\":[]}");
        Map<String, Object> bad = json("{\"center\":[0,0],\"bounds\":[[0,0],[0,0],[0,0]],"
                + "\"endpoints\":[" + groups + "]}");
        assertThrows(RuntimeException.class, () -> NegCodec.encode(bad));
    }

    @Test
    void mat2ColorComponentOutOfRangeIsRejected() {
        Map<String, Object> bad = json("{\"id\":1,\"entries\":[{\"key\":\"col\","
                + "\"values\":[{\"color\":[999,0,0,0]}]}]}");
        assertThrows(RuntimeException.class, () -> Mat2Codec.encode(bad));
    }

    @Test
    void packRejectsUnknownCodec(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("bad.resdir");
        Files.createDirectories(dir.resolve("layers"));
        Files.writeString(dir.resolve("layers/000_x.bin"), "data");
        Files.writeString(dir.resolve("manifest.txt"),
                "res-version: 1\nlayer\tx\tlayers/000_x.bin\tmat22\n");
        IOException ex = assertThrows(IOException.class, () -> Packer.pack(dir));
        assertTrue(ex.getMessage().contains("Unknown codec"), ex.getMessage());
    }

    @Test
    void newStyleImageMagicInHeaderIsNotMistakenForTheImage() {
        // A new-style (typed) image header whose tto block contains the bytes "BM"
        // (a 2-byte BMP magic) before the real PNG. The old magic-scan would split
        // at the spurious "BM"; the exact tto parse must land on the PNG instead.
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        MessageWriter w = new MessageWriter();
        w.uint8(128 + 1);     // new-style header
        w.int16(5);           // id
        w.string("k");        // tto key
        w.uint8(2);           // tto tag = T_STR
        w.string("BM here");  // string value containing the BMP magic bytes
        w.string("");         // empty key -> end of tto block
        w.bytes(png);
        byte[] payload = w.toByteArray();

        ImageInfo ii = ImageInfo.parse(payload);
        assertEquals("png", ii.imageFormat, "must locate the real PNG, not the stray 'BM'");
        assertArrayEquals(png, Arrays.copyOfRange(payload, ii.imageOffset, payload.length));
    }

    @Test
    void headerWithoutRealImageStaysRaw() {
        // Old-style header followed by bytes that are not any image magic: no split
        // should be offered (imageOffset = -1), so the layer stays raw and lossless.
        MessageWriter w = new MessageWriter();
        w.uint8(0).int8(0).int16(0).uint8(0).int16(1).int16(0).int16(0);  // 11-byte header
        w.bytes(new byte[]{0, 1, 2, 3, 4});                               // not an image
        ImageInfo ii = ImageInfo.parse(w.toByteArray());
        assertEquals(-1, ii.imageOffset, "no real image -> stays raw");
        assertEquals(null, ii.imageFormat);
    }
}
