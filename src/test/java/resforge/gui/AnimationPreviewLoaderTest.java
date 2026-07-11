package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.layers.ImageHeaderCodec;
import resforge.res.Layer;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationPreviewLoaderTest {
    @Test
    void indexingPreservesFirstLayerAndOffsets() {
        Layer first = image(7, 3, -4, false, (byte) 1);
        Layer duplicate = image(7, 20, 30, false, (byte) 2);
        Layer noOffset = image(8, 50, 60, true, (byte) 3);

        var index = AnimationPreviewLoader.indexImages(
                List.of(first, duplicate, noOffset));
        assertEquals(2, index.size());
        assertSame(first.data, index.get(7).payload(),
                "indexing must retain a range instead of copying encoded images");
        assertEquals(3, index.get(7).offX());
        assertEquals(-4, index.get(7).offY());
        assertEquals(1, index.get(7).payload()[
                index.get(7).imageOffset() + index.get(7).imageLength() - 1]);
        assertEquals(0, index.get(8).offX());
        assertEquals(0, index.get(8).offY());
    }

    @Test
    void duplicateFrameIdsDecodeOnlyOnceAndKeepOrdering() {
        AtomicInteger decodes = new AtomicInteger();
        AnimationPreviewLoader.Decoder decoder = decoder(decodes, 1, 1);
        AnimationPreviewLoader.Result result = AnimationPreviewLoader.decode(
                List.of(image(1, 2, 3, false, (byte) 1),
                        image(2, 4, 5, false, (byte) 2)),
                List.of(1, 1, 99, 2, 1), decoder);

        assertEquals(2, decodes.get());
        assertEquals(2, result.uniqueDecodes());
        assertEquals(4, result.frames().size());
        assertSame(result.frames().get(0).img, result.frames().get(1).img);
        assertSame(result.frames().get(0).img, result.frames().get(3).img);
        assertEquals(2, result.frames().get(0).x);
        assertEquals(4, result.frames().get(2).x);
    }

    @Test
    void uniquePixelLimitAcceptsExactBoundaryAndRejectsNextImage() {
        AtomicInteger decodes = new AtomicInteger();
        AnimationPreviewLoader.Decoder decoder = decoder(decodes, 4_096, 4_096);
        List<Layer> layers = List.of(image(1, 0, 0, false, (byte) 1),
                image(2, 0, 0, false, (byte) 2),
                image(3, 0, 0, false, (byte) 3));
        AnimationPreviewLoader.Result exact =
                AnimationPreviewLoader.decode(layers, List.of(1, 2), decoder);
        assertEquals(2, exact.frames().size());
        assertThrows(PreviewFailure.class,
                () -> AnimationPreviewLoader.decode(layers, List.of(1, 2, 3), decoder));
    }

    @Test
    void staleWorkerAndEdtCallbacksAreIgnored() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        AtomicInteger decodes = new AtomicInteger();
        AnimationPreviewLoader loader = new AnimationPreviewLoader(
                worker, edt, decoder(decodes, 1, 1));
        List<String> callbacks = new ArrayList<>();
        List<Layer> layers = List.of(image(1, 0, 0, false, (byte) 1));

        loader.load(layers, List.of(1), (result, failure) -> callbacks.add("old"));
        loader.load(layers, List.of(1), (result, failure) -> callbacks.add("new"));
        worker.runNext();
        assertTrue(edt.isEmpty(), "superseded work must not enqueue an EDT callback");
        assertEquals(0, decodes.get(), "queued stale work is skipped before decoding");
        worker.runNext();
        assertTrue(callbacks.isEmpty());
        edt.runNext();
        assertEquals(List.of("new"), callbacks);

        loader.load(layers, List.of(1), (result, failure) -> callbacks.add("disposed"));
        loader.invalidate();
        worker.runNext();
        assertTrue(edt.isEmpty());
        loader.close();
    }

    @Test
    void frameCountRejectsOnlyAboveBoundary() {
        AnimationPreviewLoader.Decoder decoder = decoder(new AtomicInteger(), 1, 1);
        List<Integer> exact = java.util.Collections.nCopies(
                PreviewBudget.MAX_ANIMATION_FRAMES, 99);
        assertTrue(AnimationPreviewLoader.decode(List.of(), exact, decoder).frames().isEmpty());
        assertThrows(PreviewFailure.class, () -> AnimationPreviewLoader.decode(
                List.of(), java.util.Collections.nCopies(
                        PreviewBudget.MAX_ANIMATION_FRAMES + 1, 99), decoder));
    }

    private static AnimationPreviewLoader.Decoder decoder(
            AtomicInteger decodes, int width, int height) {
        return new AnimationPreviewLoader.Decoder() {
            public PreviewBudget.Dimensions dimensions(byte[] bytes, String kind) {
                return new PreviewBudget.Dimensions(width, height);
            }

            public BufferedImage decode(byte[] bytes, String kind) {
                decodes.incrementAndGet();
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
        };
    }

    private static Layer image(int id, int x, int y, boolean noOffset, byte marker) {
        byte[] fakePng = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, marker};
        return new Layer("image", ImageHeaderCodec.build(id, x, y, noOffset, fakePng));
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }

        boolean isEmpty() {
            return tasks.isEmpty();
        }
    }
}
