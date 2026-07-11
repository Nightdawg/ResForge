package resforge.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePreviewLoaderTest {
    @Test
    void decodeRunsOnWorkerAndPublishesOnEdt() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        ImagePreviewLoader loader = new ImagePreviewLoader(worker, edt,
                (bytes, kind) -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        List<BufferedImage> images = new ArrayList<>();
        AtomicInteger sourceReads = new AtomicInteger();

        loader.load(() -> {
            sourceReads.incrementAndGet();
            return new ImagePreviewLoader.Range(new byte[]{1}, 0, 1, "test");
        }, "image", result -> images.add(result.image()));

        assertTrue(images.isEmpty());
        assertEquals(0, sourceReads.get(), "source extraction must not run on the caller/EDT");
        worker.runNext();
        assertEquals(1, sourceReads.get());
        assertTrue(images.isEmpty());
        edt.runNext();
        assertEquals(1, images.size());
        loader.close();
    }

    @Test
    void invalidationDropsLateWorkerAndEdtCallbacks() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        ImagePreviewLoader loader = new ImagePreviewLoader(worker, edt,
                (bytes, kind) -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        List<String> callbacks = new ArrayList<>();

        AtomicInteger sourceReads = new AtomicInteger();
        loader.load(() -> {
            sourceReads.incrementAndGet();
            return new ImagePreviewLoader.Range(new byte[]{1}, 0, 1, "test");
        }, "old", result -> callbacks.add("old"));
        loader.invalidate();
        worker.runNext();
        assertTrue(edt.isEmpty());
        assertEquals(0, sourceReads.get(), "queued stale work is skipped before source parsing");

        loader.load(() -> new ImagePreviewLoader.Range(new byte[]{1}, 0, 1, "test"),
                "current", result -> callbacks.add("current"));
        worker.runNext();
        loader.invalidate();
        edt.runNext();
        assertTrue(callbacks.isEmpty());
        loader.close();
    }

    @Test
    void encodedByteLimitRejectsBeforeDecoder() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        AtomicInteger decodes = new AtomicInteger();
        ImagePreviewLoader loader = new ImagePreviewLoader(worker, edt, (bytes, kind) -> {
            decodes.incrementAndGet();
            return null;
        });
        byte[] oversized = new byte[PreviewBudget.MAX_ENCODED_IMAGE_BYTES + 1];
        List<ImagePreviewLoader.Result> results = new ArrayList<>();

        loader.load(() -> new ImagePreviewLoader.Range(
                oversized, 0, oversized.length, "test"), "image", results::add);
        worker.runNext();
        edt.runNext();

        assertEquals(0, decodes.get());
        assertTrue(results.get(0).failure().contains("encoded-image byte limit"));
        loader.close();
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
