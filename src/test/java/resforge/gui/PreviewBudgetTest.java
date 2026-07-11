package resforge.gui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewBudgetTest {
    @Test
    void imageLimitsAcceptExactBoundaryAndRejectOversize() {
        assertEquals(PreviewBudget.MAX_ENCODED_IMAGE_BYTES,
                PreviewBudget.encodedImageBytes(
                        PreviewBudget.MAX_ENCODED_IMAGE_BYTES, "image"));
        assertThrows(PreviewFailure.class, () -> PreviewBudget.encodedImageBytes(
                (long) PreviewBudget.MAX_ENCODED_IMAGE_BYTES + 1, "image"));
        assertEquals(PreviewBudget.MAX_IMAGE_PIXELS,
                assertDoesNotThrow(() -> PreviewBudget.imagePixels(8_192, 2_048, "image")));
        assertThrows(PreviewFailure.class,
                () -> PreviewBudget.imagePixels(8_193, 1, "image"));
        assertThrows(PreviewFailure.class,
                () -> PreviewBudget.imagePixels(8_192, 2_049, "image"));
    }

    @Test
    void imageReaderChecksMetadataBeforeDecode() throws Exception {
        PreviewBudget.Dimensions exact =
                PreviewBudget.dimensions(pngMetadata(8_192, 2_048), "image");
        assertEquals(8_192, exact.width());
        assertEquals(2_048, exact.height());

        PreviewFailure failure = assertThrows(PreviewFailure.class,
                () -> PreviewBudget.decode(pngMetadata(8_193, 1), "image"));
        assertTrue(failure.getMessage().contains("side limit"));
    }

    @Test
    void aggregateLimitsHaveExactBoundaries() {
        assertDoesNotThrow(() -> Model3DView.checkPaletteEntries(
                PreviewBudget.MAX_PALETTE_ENTRIES));
        assertThrows(PreviewFailure.class, () -> Model3DView.checkPaletteEntries(
                PreviewBudget.MAX_PALETTE_ENTRIES + 1));
        assertEquals(PreviewBudget.MAX_PALETTE_PIXELS,
                Model3DView.addPalettePixels(0, PreviewBudget.MAX_PALETTE_PIXELS));
        assertThrows(PreviewFailure.class,
                () -> Model3DView.addPalettePixels(PreviewBudget.MAX_PALETTE_PIXELS, 1));

        assertDoesNotThrow(() -> AnimationPreviewLoader.checkFrameCount(
                PreviewBudget.MAX_ANIMATION_FRAMES));
        assertThrows(PreviewFailure.class, () -> AnimationPreviewLoader.checkFrameCount(
                PreviewBudget.MAX_ANIMATION_FRAMES + 1));
        assertEquals(PreviewBudget.MAX_ANIMATION_UNIQUE_PIXELS,
                AnimationPreviewLoader.addUniquePixels(
                        0, PreviewBudget.MAX_ANIMATION_UNIQUE_PIXELS));
        assertThrows(PreviewFailure.class, () -> AnimationPreviewLoader.addUniquePixels(
                PreviewBudget.MAX_ANIMATION_UNIQUE_PIXELS, 1));
        assertEquals(PreviewBudget.MAX_ANIMATION_UNIQUE_ENCODED_BYTES,
                AnimationPreviewLoader.addUniqueEncodedBytes(
                        0, PreviewBudget.MAX_ANIMATION_UNIQUE_ENCODED_BYTES));
        assertThrows(PreviewFailure.class,
                () -> AnimationPreviewLoader.addUniqueEncodedBytes(
                        PreviewBudget.MAX_ANIMATION_UNIQUE_ENCODED_BYTES, 1));

        assertDoesNotThrow(() -> Model3DView.checkTriangleCount(
                PreviewBudget.MAX_RENDER_TRIANGLES));
        assertThrows(PreviewFailure.class, () -> Model3DView.checkTriangleCount(
                PreviewBudget.MAX_RENDER_TRIANGLES + 1));
    }

    @Test
    void framebufferIsCappedAtAndAboveBoundary() {
        assertEquals(PreviewBudget.MAX_FRAMEBUFFER_PIXELS,
                (long) PreviewBudget.framebufferSize(2_048, 2_048)[0]
                        * PreviewBudget.framebufferSize(2_048, 2_048)[1]);
        int[] capped = PreviewBudget.framebufferSize(4_096, 4_096);
        assertTrue((long) capped[0] * capped[1] <= PreviewBudget.MAX_FRAMEBUFFER_PIXELS);
        assertTrue(capped[0] < 4_096);
        assertTrue(capped[1] < 4_096);
    }

    private static byte[] pngMetadata(int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteArrayOutputStream ihdrBytes = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(ihdrBytes);
        ihdr.writeInt(width);
        ihdr.writeInt(height);
        ihdr.write(new byte[]{8, 2, 0, 0, 0});
        writeChunk(out, "IHDR", ihdrBytes.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] data) throws Exception {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
