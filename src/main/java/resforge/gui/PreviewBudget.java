package resforge.gui;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Allocation limits used only by GUI previews. */
final class PreviewBudget {
    static final int MAX_ENCODED_IMAGE_BYTES = 32 * 1_024 * 1_024;
    static final int MAX_IMAGE_SIDE = 8_192;
    static final long MAX_IMAGE_PIXELS = 16L * 1_024 * 1_024;
    static final int MAX_PALETTE_ENTRIES = 256;
    static final long MAX_PALETTE_PIXELS = 64L * 1_024 * 1_024;
    static final int MAX_ANIMATION_FRAMES = 1_024;
    static final long MAX_ANIMATION_UNIQUE_ENCODED_BYTES = 64L * 1_024 * 1_024;
    static final long MAX_ANIMATION_UNIQUE_PIXELS = 32L * 1_024 * 1_024;
    static final int MAX_RENDER_TRIANGLES = 1_000_000;
    static final long MAX_FRAMEBUFFER_PIXELS = 4L * 1_024 * 1_024;
    static final long MAX_RASTER_WORK = 64L * 1_024 * 1_024;

    private PreviewBudget() {
    }

    static long imagePixels(int width, int height, String kind) throws PreviewFailure {
        if(width <= 0 || height <= 0)
            throw new PreviewFailure(kind + " has invalid dimensions " + width + "\u00d7" + height);
        if(width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE)
            throw new PreviewFailure(kind + " exceeds the preview side limit of "
                    + MAX_IMAGE_SIDE + " pixels (" + width + "\u00d7" + height + ")");
        long pixels = (long) width * height;
        if(pixels > MAX_IMAGE_PIXELS)
            throw new PreviewFailure(kind + " exceeds the preview pixel limit of "
                    + MAX_IMAGE_PIXELS + " (" + width + "\u00d7" + height + ")");
        return pixels;
    }

    static int encodedImageBytes(long bytes, String kind) throws PreviewFailure {
        if(bytes < 0 || bytes > MAX_ENCODED_IMAGE_BYTES)
            throw new PreviewFailure(kind + " exceeds the encoded-image byte limit of "
                    + MAX_ENCODED_IMAGE_BYTES + " (" + bytes + ")");
        return (int) bytes;
    }

    static long addPixels(long used, int width, int height, long limit, String kind)
            throws PreviewFailure {
        long pixels = imagePixels(width, height, kind);
        if(used > limit - pixels)
            throw new PreviewFailure(kind + " exceeds the cumulative preview pixel limit of " + limit);
        return used + pixels;
    }

    static Dimensions dimensions(byte[] bytes, String kind) throws PreviewFailure {
        if(bytes == null)
            throw new PreviewFailure(kind + " is missing");
        encodedImageBytes(bytes.length, kind);
        try(ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if(in == null)
                throw new PreviewFailure(kind + " could not be decoded");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if(!readers.hasNext())
                throw new PreviewFailure(kind + " uses an unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                imagePixels(width, height, kind);
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch(IOException | RuntimeException e) {
            throw new PreviewFailure(kind + " could not be inspected: " + message(e), e);
        }
    }

    static BufferedImage decode(byte[] bytes, String kind) throws PreviewFailure {
        if(bytes == null)
            return null;
        encodedImageBytes(bytes.length, kind);
        try(ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if(in == null)
                throw new PreviewFailure(kind + " could not be decoded");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if(!readers.hasNext())
                throw new PreviewFailure(kind + " uses an unsupported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                imagePixels(width, height, kind);
                BufferedImage image = reader.read(0);
                if(image == null)
                    throw new PreviewFailure(kind + " could not be decoded");
                return image;
            } finally {
                reader.dispose();
            }
        } catch(IOException | RuntimeException e) {
            throw new PreviewFailure(kind + " could not be decoded: " + message(e), e);
        }
    }

    static int[] framebufferSize(int width, int height) {
        if(width <= 0 || height <= 0)
            return new int[]{0, 0};
        long pixels = (long) width * height;
        if(pixels <= MAX_FRAMEBUFFER_PIXELS)
            return new int[]{width, height};
        double scale = Math.sqrt((double) MAX_FRAMEBUFFER_PIXELS / pixels);
        int w = Math.max(1, (int) Math.floor(width * scale));
        int h = Math.max(1, (int) Math.floor(height * scale));
        while((long) w * h > MAX_FRAMEBUFFER_PIXELS) {
            if(w >= h)
                w--;
            else
                h--;
        }
        return new int[]{w, h};
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    record Dimensions(int width, int height) {
        long pixels() {
            return (long) width * height;
        }
    }
}
