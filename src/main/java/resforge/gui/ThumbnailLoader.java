package resforge.gui;

import resforge.res.Layer;

import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Coalesces table-thumbnail work and publishes only the current generation on the EDT. */
final class ThumbnailLoader implements AutoCloseable {
    interface Decoder {
        Icon decode(Layer layer);
    }

    interface Callback {
        void complete(Layer layer, Icon icon);
    }

    private final Executor worker;
    private final Executor edt;
    private final Decoder decoder;
    private final ExecutorService ownedWorker;
    private final AtomicLong generation = new AtomicLong();
    private final Map<Layer, Long> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    ThumbnailLoader(Decoder decoder) {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "table-thumbnails");
            thread.setDaemon(true);
            return thread;
        }), SwingUtilities::invokeLater, decoder, true);
    }

    ThumbnailLoader(Executor worker, Executor edt, Decoder decoder) {
        this(worker, edt, decoder, false);
    }

    private ThumbnailLoader(Executor worker, Executor edt, Decoder decoder, boolean ownsWorker) {
        this.worker = worker;
        this.edt = edt;
        this.decoder = decoder;
        this.ownedWorker = ownsWorker ? (ExecutorService) worker : null;
    }

    boolean load(Layer layer, Callback callback) {
        if(closed)
            return false;
        long current = generation.get();
        if(pending.putIfAbsent(layer, current) != null)
            return false;
        worker.execute(() -> {
            if(!isCurrent(current)) {
                pending.remove(layer, current);
                return;
            }
            Icon icon;
            try {
                icon = decoder.decode(layer);
            } catch(RuntimeException ignored) {
                icon = null;
            }
            if(!isCurrent(current)) {
                pending.remove(layer, current);
                return;
            }
            Icon completed = icon;
            edt.execute(() -> {
                if(isCurrent(current) && pending.remove(layer, current))
                    callback.complete(layer, completed);
            });
        });
        return true;
    }

    void invalidate() {
        generation.incrementAndGet();
        pending.clear();
    }

    int pendingCount() {
        return pending.size();
    }

    private boolean isCurrent(long value) {
        return !closed && generation.get() == value;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        pending.clear();
        if(ownedWorker != null)
            ownedWorker.shutdownNow();
    }
}
