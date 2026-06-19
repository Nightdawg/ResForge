/*
 * hafen-resedit — a modding tool for Haven & Hearth .res files.
 *
 * The .res binary encoding mirrors haven.Message / haven.Utils from the game
 * client. This reader reproduces the subset of decoders that the tool needs,
 * using little-endian byte order throughout (as the client does).
 */
package hafen.resedit.io;

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
        if(pos + n > end)
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

    /** NUL-terminated UTF-8 string. */
    public String string() {
        int start = pos;
        while(pos < end && buf[pos] != 0)
            pos++;
        if(pos >= end)
            throw new IllegalStateException("Found no NUL terminator");
        String s = new String(buf, start, pos - start, StandardCharsets.UTF_8);
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
