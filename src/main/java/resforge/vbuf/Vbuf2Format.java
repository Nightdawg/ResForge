package resforge.vbuf;

import java.util.Map;

/** Shared metadata for fixed-width floating-point VBUF2 attributes. */
public final class Vbuf2Format {
    private static final Map<String, Integer> ELEMENTS = Map.of(
            "pos", 3,
            "nrm", 3,
            "col", 4,
            "tex", 2,
            "tan", 3,
            "bit", 3,
            "otex", 2);

    private Vbuf2Format() {
    }

    /** Elements per vertex for a bare or {@code 2}-formatted attribute, or -1. */
    public static int elements(String attributeName) {
        String base = attributeName.endsWith("2")
                ? attributeName.substring(0, attributeName.length() - 1)
                : attributeName;
        return ELEMENTS.getOrDefault(base, -1);
    }
}
