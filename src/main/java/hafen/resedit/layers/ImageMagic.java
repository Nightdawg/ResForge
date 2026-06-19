package hafen.resedit.layers;

/** Detects common encoded-image formats by their magic bytes. */
public final class ImageMagic {
    private ImageMagic() {
    }

    /** Returns "png"/"jpg"/"gif"/"bmp" if an image starts at index {@code i}, else null. */
    public static String formatAt(byte[] b, int i) {
        if(i < 0)
            return null;
        if(match(b, i, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            return "png";
        if(match(b, i, 0xFF, 0xD8, 0xFF))
            return "jpg";
        if(match(b, i, 0x47, 0x49, 0x46, 0x38))
            return "gif";
        if(match(b, i, 0x42, 0x4D))
            return "bmp";
        return null;
    }

    private static boolean match(byte[] b, int i, int... sig) {
        if(i < 0 || i + sig.length > b.length)
            return false;
        for(int k = 0; k < sig.length; k++) {
            if((b[i + k] & 0xff) != sig[k])
                return false;
        }
        return true;
    }
}
