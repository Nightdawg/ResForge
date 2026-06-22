/*
 * ResForge — a modding tool for Haven & Hearth .res files.
 *
 * The .res binary encoding mirrors haven.Message / haven.Utils from the game
 * client. This reader reproduces the subset of decoders that the tool needs,
 * using little-endian byte order throughout (as the client does).
 */
package resforge.io;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Sequential little-endian reader over a byte array, matching haven.Message. */
public class MessageReader {
    private final byte[] buf;
    private int pos;
    private final int end;

    public MessageReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public MessageReader(byte[] buf, int off, int len) {
        if(off < 0 || len < 0 || off > buf.length - len)
            throw new IllegalArgumentException("invalid range off=" + off + " len=" + len + " (buf=" + buf.length + ")");
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return end - pos;
    }

    public boolean eom() {
        return pos >= end;
    }

    private void ensure(int n) {
        if(n < 0 || n > end - pos)
            throw new IllegalStateException("Required " + n + " bytes, only " + (end - pos) + " left");
    }

    public int int8() {
        ensure(1);
        return buf[pos++];
    }

    public int uint8() {
        return int8() & 0xff;
    }

    public int int16() {
        ensure(2);
        int v = (buf[pos] & 0xff) | ((buf[pos + 1] & 0xff) << 8);
        pos += 2;
        return (short) v;
    }

    public int uint16() {
        return int16() & 0xffff;
    }

    public int int32() {
        ensure(4);
        int v = (buf[pos] & 0xff) | ((buf[pos + 1] & 0xff) << 8)
                | ((buf[pos + 2] & 0xff) << 16) | ((buf[pos + 3] & 0xff) << 24);
        pos += 4;
        return v;
    }

    public long uint32() {
        return int32() & 0xffffffffL;
    }

    public long int64() {
        ensure(8);
        long v = 0;
        for(int i = 0; i < 8; i++)
            v |= (long) (buf[pos + i] & 0xff) << (i * 8);
        pos += 8;
        return v;
    }

    public float float32() {
        return Float.intBitsToFloat(int32());
    }

    public double float64() {
        return Double.longBitsToDouble(int64());
    }

    /** IEEE-754 half-precision (16-bit) float, little-endian. */
    public float float16() {
        return halfToFloat(uint16());
    }

    /** Converts a 16-bit half-precision bit pattern to a 32-bit float (exact). */
    public static float halfToFloat(int h) {
        int sign = (h >> 15) & 0x1;
        int exp = (h >> 10) & 0x1f;
        int mant = h & 0x3ff;
        int bits;
        if(exp == 0) {
            if(mant == 0) {
                bits = sign << 31;                       // +/- 0
            } else {
                exp = 127 - 15 + 1;                      // subnormal -> normalize
                while((mant & 0x400) == 0) {
                    mant <<= 1;
                    exp--;
                }
                mant &= 0x3ff;
                bits = (sign << 31) | (exp << 23) | (mant << 13);
            }
        } else if(exp == 0x1f) {
            bits = (sign << 31) | 0x7f800000 | (mant << 13);   // inf / NaN
        } else {
            bits = (sign << 31) | ((exp - 15 + 127) << 23) | (mant << 13);
        }
        return Float.intBitsToFloat(bits);
    }

    /** Custom-packed float (haven {@code Message.cpfloat}): a signed {@code int8}
     *  exponent followed by a little-endian {@code uint32} where the top bit is the
     *  sign and the low 31 bits are the mantissa. Mirrors {@code Utils.floatd}. */
    public double cpfloat() {
        int e = int8();
        long t = uint32();
        int m = (int) (t & 0x7fffffffL);
        boolean s = (t & 0x80000000L) != 0;
        if(e == -128) {
            if(m == 0)
                return 0.0;
            throw new IllegalStateException("invalid special cpfloat (" + m + ")");
        }
        double v = (m / 2147483648.0) + 1.0;
        if(s)
            v = -v;
        return Math.pow(2.0, e) * v;
    }

    /** Signed normalized 16-bit value in [-1, 1] (haven {@code Message.snorm16}). */
    public float snorm16() {
        int v = int16();
        if(v < -0x7fff)
            v = -0x7fff;
        if(v > 0x7fff)
            v = 0x7fff;
        return v / (float) 0x7fff;
    }

    /** Unsigned normalized 16-bit value in [0, 1] (haven {@code Message.unorm16}). */
    public float unorm16() {
        return uint16() / (float) 0xffff;
    }

    /** Modular normalized 16-bit value in [0, 1) (haven {@code Message.mnorm16}). */
    public float mnorm16() {
        return uint16() / (float) 0x10000;
    }

    /** Decodes an octahedral-encoded unit vector from two snorm components into
     *  {@code out[0..2]} (mirrors {@code Utils.oct2uvec}). */
    public static void oct2uvec(float[] out, float x, float y) {
        float z = 1 - (Math.abs(x) + Math.abs(y));
        if(z < 0) {
            float xc = x, yc = y;
            x = (1 - Math.abs(yc)) * Math.copySign(1, xc);
            y = (1 - Math.abs(xc)) * Math.copySign(1, yc);
        }
        float f = 1 / (float) Math.sqrt((x * x) + (y * y) + (z * z));
        out[0] = x * f;
        out[1] = y * f;
        out[2] = z * f;
    }

    /** NUL-terminated UTF-8 string. */
    public String string() {
        int start = pos;
        while(pos < end && buf[pos] != 0)
            pos++;
        if(pos >= end)
            throw new IllegalStateException("Found no NUL terminator");
        // Strict UTF-8: reject malformed bytes rather than substituting U+FFFD, so
        // a decode -> encode is byte-identical (the lossless invariant) and a
        // non-round-trippable name/string is surfaced instead of silently changed.
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buf, start, pos - start)).toString();
        } catch(CharacterCodingException e) {
            throw new IllegalStateException("Invalid UTF-8 string", e);
        }
        pos++;
        return s;
    }

    public byte[] bytes(int n) {
        ensure(n);
        byte[] ret = new byte[n];
        System.arraycopy(buf, pos, ret, 0, n);
        pos += n;
        return ret;
    }

    public void skip(int n) {
        ensure(n);
        pos += n;
    }
}
