package resforge.gui;

import resforge.layers.ImageInfo;
import resforge.res.Layer;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Indexes and decodes one animation preview away from the EDT. */
final class AnimationPreviewLoader implements AutoCloseable {
    interface Decoder {
        PreviewBudget.Dimensions dimensions(byte[] bytes, String kind) throws PreviewFailure;
        BufferedImage decode(byte[] bytes, String kind) throws PreviewFailure;
    }

    interface Callback {
        void complete(Result result, String failure);
    }

    record Source(byte[] payload, int imageOffset, int imageLength, int offX, int offY) {
    }

    record Result(List<AnimView.Frame> frames, int uniqueDecodes) {
    }

    private static final Decoder BOUNDED_DECODER = new Decoder() {
        public PreviewBudget.Dimensions dimensions(byte[] bytes, String kind) throws PreviewFailure {
            return PreviewBudget.dimensions(bytes, kind);
        }

        public BufferedImage decode(byte[] bytes, String kind) throws PreviewFailure {
            return PreviewBudget.decode(bytes, kind);
        }
    };

    private final Executor worker;
    private final Executor edt;
    private final Decoder decoder;
    private final ExecutorService ownedWorker;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;

    AnimationPreviewLoader() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "animation-preview");
            t.setDaemon(true);
            return t;
        }), SwingUtilities::invokeLater, BOUNDED_DECODER, true);
    }

    AnimationPreviewLoader(Executor worker, Executor edt, Decoder decoder) {
        this(worker, edt, decoder, false);
    }

    private AnimationPreviewLoader(Executor worker, Executor edt, Decoder decoder, boolean ownsWorker) {
        this.worker = worker;
        this.edt = edt;
        this.decoder = decoder;
        this.ownedWorker = ownsWorker ? (ExecutorService) worker : null;
    }

    long load(List<Layer> layerSnapshot, List<Integer> frameIds, Callback callback) {
        long current = generation.incrementAndGet();
        if(closed)
            return current;
        List<Layer> layers = List.copyOf(layerSnapshot);
        List<Integer> ids = List.copyOf(frameIds);
        worker.execute(() -> {
            if(!isCurrent(current))
                return;
            Result result = null;
            String failure = null;
            try {
                result = decode(layers, ids, decoder);
            } catch(PreviewFailure e) {
                failure = e.getMessage();
            } catch(RuntimeException e) {
                failure = "Animation preview could not be decoded: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            if(!isCurrent(current))
                return;
            Result completed = result;
            String error = failure;
            edt.execute(() -> {
                if(isCurrent(current))
                    callback.complete(completed, error);
            });
        });
        return current;
    }

    void invalidate() {
        generation.incrementAndGet();
    }

    boolean isCurrent(long value) {
        return !closed && generation.get() == value;
    }

    static Result decode(List<Layer> layers, List<Integer> frameIds, Decoder decoder)
            throws PreviewFailure {
        checkFrameCount(frameIds.size());

        Map<Integer, Source> images = indexImages(layers);
        Map<Integer, AnimView.Frame> decoded = new LinkedHashMap<>();
        long pixels = 0;
        long encodedBytes = 0;
        for(int id : frameIds) {
            if(decoded.containsKey(id))
                continue;
            Source source = images.get(id);
            if(source == null)
                continue;
            String kind = "animation image " + id;
            PreviewBudget.encodedImageBytes(source.imageLength, kind);
            encodedBytes = addUniqueEncodedBytes(encodedBytes, source.imageLength);
            byte[] imageBytes = Arrays.copyOfRange(source.payload, source.imageOffset,
                    source.imageOffset + source.imageLength);
            PreviewBudget.Dimensions dimensions = decoder.dimensions(imageBytes, kind);
            pixels = addUniquePixels(pixels, dimensions.pixels());
            BufferedImage image = decoder.decode(imageBytes, kind);
            if(image != null)
                decoded.put(id, new AnimView.Frame(image, source.offX, source.offY));
        }

        List<AnimView.Frame> frames = new ArrayList<>();
        for(int id : frameIds) {
            AnimView.Frame frame = decoded.get(id);
            if(frame != null)
                frames.add(frame);
        }
        return new Result(List.copyOf(frames), decoded.size());
    }

    static void checkFrameCount(int frames) throws PreviewFailure {
        if(frames > PreviewBudget.MAX_ANIMATION_FRAMES)
            throw new PreviewFailure("Animation preview exceeds the frame limit of "
                    + PreviewBudget.MAX_ANIMATION_FRAMES);
    }

    static long addUniquePixels(long used, long added) throws PreviewFailure {
        if(used > PreviewBudget.MAX_ANIMATION_UNIQUE_PIXELS - added)
            throw new PreviewFailure("Animation preview exceeds the unique-image pixel limit of "
                    + PreviewBudget.MAX_ANIMATION_UNIQUE_PIXELS);
        return used + added;
    }

    static long addUniqueEncodedBytes(long used, long added) throws PreviewFailure {
        if(added < 0 || used > PreviewBudget.MAX_ANIMATION_UNIQUE_ENCODED_BYTES - added)
            throw new PreviewFailure("Animation preview exceeds the unique-image encoded-byte limit of "
                    + PreviewBudget.MAX_ANIMATION_UNIQUE_ENCODED_BYTES);
        return used + added;
    }

    static Map<Integer, Source> indexImages(List<Layer> layers) {
        Map<Integer, Source> images = new LinkedHashMap<>();
        for(Layer layer : layers) {
            if(!layer.name.equals("image"))
                continue;
            try {
                ImageInfo info = ImageInfo.parse(layer.data);
                if(!info.recognized || info.imageFormat == null || info.imageOffset <= 0)
                    continue;
                int offX = info.nooff ? 0 : info.offX;
                int offY = info.nooff ? 0 : info.offY;
                images.putIfAbsent(info.id, new Source(layer.data, info.imageOffset,
                        layer.data.length - info.imageOffset, offX, offY));
            } catch(RuntimeException ignored) {
                // A malformed image layer is simply not a matching animation frame.
            }
        }
        return images;
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        if(ownedWorker != null)
            ownedWorker.shutdownNow();
    }
}
