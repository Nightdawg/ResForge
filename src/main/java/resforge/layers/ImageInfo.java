package resforge.layers;

import resforge.io.MessageReader;

/**
 * Parses the header of an "image" layer payload to (a) report metadata and
 * (b) locate where the embedded image (PNG/JPEG/...) begins, so the layer can
 * be split into a byte-exact header part and a replaceable image part.
 *
 * Image layer format (from haven.Resource.Image):
 *   ver = uint8
 *   if ver < 128:
 *       z    = int8*256 + ver
 *       subz = int16
 *       fl   = uint8           (bit2 = nooff, bit3 = has-info)
 *       id   = int16
 *       off  = (int16,int16)
 *       if (fl & 4): repeated [ string key; uint8/int32 len; len bytes ] until key==""
 *       <encoded image bytes>
 *   else (ver-128 == 1):
 *       id   = int16
 *       repeated [ string key; tto value ] until key==""
 *       <encoded image bytes>
 */
public class ImageInfo {
    public boolean recognized;
    public int headerVer;
    public int z, subz, id;
    public int offX, offY;
    public boolean nooff;
    public int imageOffset = -1;   // byte index where the embedded image begins
    public String imageFormat;     // "png", "jpg", "gif", "bmp", or null

    public static ImageInfo parse(byte[] payload) {
        ImageInfo ii = new ImageInfo();
        try {
            MessageReader in = new MessageReader(payload);
            int ver = in.uint8();
            if(ver < 128) {
                ii.headerVer = ver;
                ii.z = (in.int8() * 256) + ver;
                ii.subz = in.int16();
                int fl = in.uint8();
                ii.nooff = (fl & 2) != 0;
                ii.id = in.int16();
                ii.offX = in.int16();
                ii.offY = in.int16();
                if((fl & 4) != 0) {
                    while(true) {
                        String key = in.string();
                        if(key.isEmpty())
                            break;
                        int len = in.uint8();
                        if((len & 0x80) != 0)
                            len = in.int32();
                        in.skip(len);
                    }
                }
                ii.recognized = true;
                ii.imageOffset = in.position();
            } else if(ver - 128 == 1) {
                ii.headerVer = ver;
                ii.id = in.int16();
                // New-style header uses typed (tto) values which this tool does
                // not fully decode; fall back to magic-scanning for the split.
                ii.recognized = true;
            }
        } catch(RuntimeException e) {
            ii.recognized = false;
        }

        // Validate / locate the embedded image by its magic bytes.
        int scanFrom = (ii.imageOffset >= 0) ? ii.imageOffset : 1;
        int magic = magicAt(payload, scanFrom);
        if(magic < 0 || ii.imageOffset < 0) {
            int found = findMagic(payload, Math.max(1, ii.imageOffset < 0 ? 1 : ii.imageOffset));
            if(found >= 0) {
                ii.imageOffset = found;
                magic = magicAt(payload, found);
            }
        }
        ii.imageFormat = formatName(magic);
        if(ii.imageFormat == null)
            ii.imageOffset = -1;
        return ii;
    }

    private static int findMagic(byte[] b, int from) {
        for(int i = Math.max(0, from); i < b.length; i++) {
            if(magicAt(b, i) >= 0)
                return i;
        }
        return -1;
    }

    /** Returns a small format code (0=png,1=jpg,2=gif,3=bmp) or -1 if no match. */
    private static int magicAt(byte[] b, int i) {
        if(i < 0)
            return -1;
        if(match(b, i, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            return 0;
        if(match(b, i, 0xFF, 0xD8, 0xFF))
            return 1;
        if(match(b, i, 0x47, 0x49, 0x46, 0x38))
            return 2;
        if(match(b, i, 0x42, 0x4D))
            return 3;
        return -1;
    }

    private static String formatName(int code) {
        switch(code) {
            case 0: return "png";
            case 1: return "jpg";
            case 2: return "gif";
            case 3: return "bmp";
            default: return null;
        }
    }

    private static boolean match(byte[] b, int i, int... sig) {
        if(i + sig.length > b.length)
            return false;
        for(int k = 0; k < sig.length; k++) {
            if((b[i + k] & 0xff) != sig[k])
                return false;
        }
        return true;
    }
}
