package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.res.Layer;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailLoaderTest {
    private static final Icon ICON = new ImageIcon(new byte[]{1});

    @Test
    void coalescesPendingLayerAndPublishesOnEdt() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        AtomicInteger decodes = new AtomicInteger();
        ThumbnailLoader loader = new ThumbnailLoader(worker, edt, layer -> {
            decodes.incrementAndGet();
            return ICON;
        });
        Layer layer = new Layer("image", new byte[]{1});
        List<Icon> completed = new ArrayList<>();

        assertTrue(loader.load(layer, (ignored, icon) -> completed.add(icon)));
        assertFalse(loader.load(layer, (ignored, icon) -> completed.add(icon)));
        assertEquals(1, loader.pendingCount());
        worker.runNext();
        assertTrue(completed.isEmpty());
        edt.runNext();

        assertEquals(List.of(ICON), completed);
        assertEquals(1, decodes.get());
        assertEquals(0, loader.pendingCount());
        loader.close();
    }

    @Test
    void invalidationSkipsQueuedDecodeAndLateEdtPublication() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor edt = new ManualExecutor();
        AtomicInteger decodes = new AtomicInteger();
        ThumbnailLoader loader = new ThumbnailLoader(worker, edt, layer -> {
            decodes.incrementAndGet();
            return ICON;
        });
        Layer layer = new Layer("image", new byte[]{1});
        List<Icon> completed = new ArrayList<>();

        loader.load(layer, (ignored, icon) -> completed.add(icon));
        loader.invalidate();
        worker.runNext();
        assertEquals(0, decodes.get());
        assertTrue(edt.isEmpty());

        loader.load(layer, (ignored, icon) -> completed.add(icon));
        worker.runNext();
        loader.invalidate();
        edt.runNext();
        assertTrue(completed.isEmpty());
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
