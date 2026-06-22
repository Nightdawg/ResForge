package resforge.res;

/**
 * A single resource layer: a type name plus its raw payload bytes.
 *
 * <p>Treated as immutable: {@code name} and the {@code data} reference are final,
 * and the {@code data} bytes are never modified in place anywhere in the tool — an
 * edit always builds a new {@code Layer} and replaces the old one in the container's
 * list. Undo/redo snapshots rely on this (they keep {@code Layer} references, not
 * copies), so callers must likewise not mutate {@code data}.
 */
public class Layer {
    public final String name;
    public final byte[] data;

    public Layer(String name, byte[] data) {
        this.name = name;
        this.data = data;
    }
}
