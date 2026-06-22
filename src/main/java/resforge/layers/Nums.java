package resforge.layers;

/**
 * Range-checked narrowing of JSON numbers to fixed-width integer fields. A typed
 * layer's {@code encode} runs only at unpack time on values it just decoded (always
 * in range) and again at pack time on the user's edited JSON. Without a check, an
 * edited value outside the field's width would be silently truncated/wrapped on the
 * wire (e.g. {@code delay: 70000} written as {@code 4464}); these helpers reject it
 * instead, so a bad edit fails loudly rather than corrupting the layer.
 */
final class Nums {
    private Nums() {
    }

    static int u8(Object v)  { return range(v, 0, 0xff, "uint8"); }

    static int i8(Object v)  { return range(v, -0x80, 0x7f, "int8"); }

    static int u16(Object v) { return range(v, 0, 0xffff, "uint16"); }

    static int i16(Object v) { return range(v, -0x8000, 0x7fff, "int16"); }

    static int i32(Object v) { return range(v, Integer.MIN_VALUE, Integer.MAX_VALUE, "int32"); }

    /** Validates that a derived collection size fits a fixed-width count field. */
    static int count(int size, int max, String what) {
        if(size > max)
            throw new IllegalArgumentException(what + " count " + size + " exceeds the field maximum " + max);
        return size;
    }

    private static int range(Object v, long lo, long hi, String what) {
        long n = integral(v, what);
        if(n < lo || n > hi)
            throw new IllegalArgumentException(what + " value out of range [" + lo + ".." + hi + "]: " + n);
        return (int) n;
    }

    private static long integral(Object v, String what) {
        if(!(v instanceof Number))
            throw new IllegalArgumentException("expected a number for " + what + ", got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        if(v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte)
            return ((Number) v).longValue();
        double d = ((Number) v).doubleValue();
        if(Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)
                || d < Long.MIN_VALUE || d > Long.MAX_VALUE)
            throw new IllegalArgumentException(what + " must be an integer, got " + v);
        return (long) d;
    }
}
