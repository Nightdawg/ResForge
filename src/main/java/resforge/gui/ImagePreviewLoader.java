package resforge.gui;

import resforge.layers.ImageInfo;
import resforge.layers.TexInfo;
import resforge.layers.TileInfo;
import resforge.res.Layer;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Decodes bounded single-image previews away from the EDT. */
final class ImagePreviewLoader implements AutoCloseable {
    @FunctionalInterface
    interface Source {
        Range locate();
    }

    interface Decoder {
        BufferedImage decode(byte[] bytes, String kind) throws PreviewFailure;
    }

    interface Callback {
        void complete(Result result);
    }

    record Range(byte[] payload, int offset, int length, String format) {
        Range {
            if(payload == null || offset < 0 || length < 0
                    || (long) offset + length > payload.length)
                throw new IllegalArgumentException("invalid encoded-image range");
        }
    }

    record Result(BufferedImage image, boolean present, String format, String failure) {
    }

    private final Executor worker;
    private final Executor edt;
    private final Decoder decoder;
    private final ExecutorService ownedWorker;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;

    ImagePreviewLoader() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "image-preview");
            thread.setDaemon(true);
            return thread;
        }), SwingUtilities::invokeLater, PreviewBudget::decode, true);
    }

    ImagePreviewLoader(Executor worker, Executor edt, Decoder decoder) {
        this(worker, edt, decoder, false);
    }

    private ImagePreviewLoader(Executor worker, Executor edt, Decoder decoder,
                               boolean ownsWorker) {
        this.worker = worker;
        this.edt = edt;
        this.decoder = decoder;
        this.ownedWorker = ownsWorker ? (ExecutorService) worker : null;
    }

    void load(Source source, String kind, Callback callback) {
        long current = generation.get();
        if(closed)
            return;
        worker.execute(() -> {
            if(!isCurrent(current))
                return;
            BufferedImage image = null;
            Range range = null;
            String failure = null;
            try {
                range = source.locate();
                if(range == null) {
                    publish(current, callback, new Result(null, false, null, null));
                    return;
                }
                PreviewBudget.encodedImageBytes(range.length, kind);
                byte[] bytes = Arrays.copyOfRange(
                        range.payload, range.offset, range.offset + range.length);
                image = decoder.decode(bytes, kind);
            } catch(PreviewFailure e) {
                failure = e.getMessage();
            } catch(RuntimeException e) {
                failure = kind + " could not be decoded: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            if(!isCurrent(current))
                return;
            publish(current, callback, new Result(
                    image, range != null, range != null ? range.format : null, failure));
        });
    }

    private void publish(long current, Callback callback, Result result) {
        if(!isCurrent(current))
            return;
        edt.execute(() -> {
            if(isCurrent(current))
                callback.complete(result);
        });
    }

    static Source embedded(Layer layer) {
        return () -> {
            if(layer.name.equals("image")) {
                ImageInfo info = ImageInfo.parse(layer.data);
                if(info.imageFormat != null && info.imageOffset > 0)
                    return new Range(layer.data, info.imageOffset,
                            layer.data.length - info.imageOffset, info.imageFormat);
            } else if(layer.name.equals("tex")) {
                TexInfo info = TexInfo.parse(layer.data);
                if(info.found)
                    return new Range(layer.data, info.imageOffset, info.imageLen,
                            info.imageFormat);
            } else if(layer.name.equals("tile")) {
                TileInfo info = TileInfo.parse(layer.data);
                if(info.found)
                    return new Range(layer.data, info.imageOffset,
                            layer.data.length - info.imageOffset, info.imageFormat);
            }
            return null;
        };
    }

    static Source textureMask(Layer layer) {
        return () -> {
            if(!layer.name.equals("tex"))
                return null;
            TexInfo info = TexInfo.parse(layer.data);
            return info.maskFound
                    ? new Range(layer.data, info.maskOffset, info.maskLen, info.maskFormat)
                    : null;
        };
    }

    void invalidate() {
        generation.incrementAndGet();
    }

    private boolean isCurrent(long value) {
        return !closed && generation.get() == value;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        if(ownedWorker != null)
            ownedWorker.shutdownNow();
    }
}
