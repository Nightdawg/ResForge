package hafen.resedit.res;

/** A single resource layer: a type name plus its raw payload bytes. */
public class Layer {
    public final String name;
    public final byte[] data;

    public Layer(String name, byte[] data) {
        this.name = name;
        this.data = data;
    }
}
