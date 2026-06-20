package resforge.io;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Sequential little-endian writer, matching haven.Message add* methods. */
public class MessageWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public MessageWriter int8(int v) {
        out.write(v & 0xff);
        return this;
    }

    public MessageWriter uint8(int v) {
        return int8(v);
    }

    public MessageWriter int16(int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        return this;
    }

    public MessageWriter uint16(int v) {
        return int16(v);
    }

    public MessageWriter int32(int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
        return this;
    }

    public MessageWriter int64(long v) {
        for(int i = 0; i < 8; i++)
            out.write((int) ((v >> (i * 8)) & 0xff));
        return this;
    }

    public MessageWriter float32(float v) {
        return int32(Float.floatToIntBits(v));
    }

    public MessageWriter float64(double v) {
        return int64(Double.doubleToLongBits(v));
    }

    /** IEEE-754 half-precision (16-bit) float, little-endian (round to nearest even). */
    public MessageWriter float16(float v) {
        return uint16(floatToHalf(v));
    }

    /** Converts a 32-bit float to a 16-bit half-precision bit pattern (round to nearest even). */
    public static int floatToHalf(float v) {
        int bits = Float.floatToIntBits(v);
        int sign = (bits >>> 16) & 0x8000;
        int mant = bits & 0x007fffff;
        int exp = (bits >>> 23) & 0xff;

        if(exp == 0xff)
            return sign | (mant != 0 ? 0x7e00 : 0x7c00);   // NaN : Inf

        int unbiased = exp - 127 + 15;
        if(unbiased >= 0x1f)
            return sign | 0x7c00;                          // overflow -> Inf
        if(unbiased <= 0) {
            if(unbiased < -10)
                return sign;                               // too small -> 0
            mant |= 0x00800000;                            // restore implicit 1
            int shift = 14 - unbiased;
            int half = mant >> shift;
            int rem = mant & ((1 << shift) - 1);
            int halfBit = 1 << (shift - 1);
            if(rem > halfBit || (rem == halfBit && (half & 1) != 0))
                half++;
            return sign | half;
        }
        int half = sign | (unbiased << 10) | (mant >> 13);
        int rem = mant & 0x1fff;
        if(rem > 0x1000 || (rem == 0x1000 && (half & 1) != 0))
            half++;                                        // round (carry into exp is correct)
        return half;
    }

    /** NUL-terminated UTF-8 string. */
    public MessageWriter string(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        return this;
    }

    public MessageWriter bytes(byte[] b) {
        out.writeBytes(b);
        return this;
    }

    public MessageWriter bytes(byte[] b, int off, int len) {
        out.write(b, off, len);
        return this;
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    public int size() {
        return out.size();
    }
}
