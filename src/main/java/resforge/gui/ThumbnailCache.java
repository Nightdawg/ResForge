package resforge.gui;

import resforge.res.Layer;

import javax.swing.Icon;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Bounded, EDT-confined cache that never retains obsolete document layers. */
final class ThumbnailCache {
    static final int MAX_ENTRIES = 256;

    private final Map<Layer, Icon> entries =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Layer, Icon> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    Icon get(Layer layer, Function<Layer, Icon> loader) {
        if(entries.containsKey(layer))
            return entries.get(layer);
        Icon icon = loader.apply(layer);
        entries.put(layer, icon);
        return icon;
    }

    Icon get(Layer layer) {
        return entries.get(layer);
    }

    void put(Layer layer, Icon icon) {
        entries.put(layer, icon);
    }

    void remove(Layer layer) {
        entries.remove(layer);
    }

    void retainOnly(Collection<Layer> layers) {
        entries.keySet().retainAll(layers);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    boolean contains(Layer layer) {
        return entries.containsKey(layer);
    }
}
