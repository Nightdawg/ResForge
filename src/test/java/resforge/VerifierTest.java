package resforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import resforge.res.Layer;
import resforge.res.ResContainer;
import resforge.res.Verifier;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierTest {
    @TempDir
    Path tmp;

    @Test
    void verifiesSingleSyntheticResource() throws Exception {
        Path file = writeResource(tmp.resolve("one.res"), new Layer("tooltip", bytes("hello")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Verifier.Summary summary = Verifier.run(file, new PrintStream(output, true, StandardCharsets.UTF_8));
        String text = output.toString(StandardCharsets.UTF_8);

        assertEquals(1, summary.total);
        assertEquals(1, summary.passed);
        assertEquals(0, summary.failed);
        assertEquals(1, summary.layerHist.get("tooltip"));
        assertTrue(text.contains("PASS"));
        assertTrue(text.contains("Verified 1 file(s): 1 passed, 0 failed"));
    }

    @Test
    void directoryOrderFailuresAndHistogramsAreDeterministic() throws Exception {
        writeResource(tmp.resolve("b.res"), new Layer("tooltip", bytes("b")));
        writeResource(tmp.resolve("a.res"), new Layer("font",
                new byte[]{1, 0, 'O', 'T', 'T', 'O', 0}));
        Files.write(tmp.resolve("z.res"), bytes("Not Haven Resource!"));
        Files.write(tmp.resolve("ignored.txt"), new byte[0]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Verifier.Summary summary = Verifier.run(tmp, new PrintStream(output, true, StandardCharsets.UTF_8));
        String text = output.toString(StandardCharsets.UTF_8);

        assertEquals(3, summary.total);
        assertEquals(2, summary.passed);
        assertEquals(1, summary.failed);
        assertEquals(1, summary.layerHist.get("font"));
        assertEquals(1, summary.layerHist.get("tooltip"));
        assertEquals(1, summary.fontHist.get("otf"));
        assertTrue(text.indexOf("a.res") < text.indexOf("b.res"));
        assertTrue(text.indexOf("b.res") < text.indexOf("z.res"));
        assertTrue(text.contains("parse failed: Invalid .res signature"));
        assertTrue(text.contains("Verified 3 file(s): 2 passed, 1 failed"));
        int layerHistogram = text.indexOf("Layer histogram:");
        assertTrue(text.indexOf("font", layerHistogram) < text.indexOf("tooltip", layerHistogram));
        assertTrue(text.contains("Font histogram:" + System.lineSeparator() + "  otf"));
    }

    @Test
    void emptyDirectoryReportsNoResources() throws Exception {
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Verifier.Summary summary = Verifier.run(empty, new PrintStream(output, true, StandardCharsets.UTF_8));

        assertEquals(0, summary.total);
        assertEquals("No .res files found under " + empty + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    private static Path writeResource(Path file, Layer... layers) throws Exception {
        ResContainer resource = new ResContainer(7);
        resource.layers.addAll(java.util.List.of(layers));
        Files.write(file, resource.serialize());
        return file;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
