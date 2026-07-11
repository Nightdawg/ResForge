package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.res.Layer;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailCacheTest {
    private static final Icon ICON = new ImageIcon(new byte[]{1});

    @Test
    void evictsLeastRecentlyUsedEntriesAtLimit() {
        ThumbnailCache cache = new ThumbnailCache();
        Layer first = layer(0);
        Layer second = layer(1);
        cache.get(first, ignored -> ICON);
        cache.get(second, ignored -> ICON);
        cache.get(first, ignored -> ICON);

        for(int i = 2; i <= ThumbnailCache.MAX_ENTRIES; i++)
            cache.get(layer(i), ignored -> ICON);

        assertEquals(ThumbnailCache.MAX_ENTRIES, cache.size());
        assertTrue(cache.contains(first), "recent access keeps the first layer");
        assertFalse(cache.contains(second), "least-recently-used layer is evicted");
    }

    @Test
    void removeImmediatelyDropsReplacedOrDeletedLayer() {
        ThumbnailCache cache = new ThumbnailCache();
        Layer obsolete = layer(1);
        cache.get(obsolete, ignored -> ICON);

        cache.remove(obsolete);

        assertFalse(cache.contains(obsolete));
        assertEquals(0, cache.size());
    }

    @Test
    void retainOnlyPrunesLayersOutsideRestoredSnapshot() {
        ThumbnailCache cache = new ThumbnailCache();
        Layer restored = layer(1);
        Layer obsolete = layer(2);
        cache.get(restored, ignored -> ICON);
        cache.get(obsolete, ignored -> ICON);

        cache.retainOnly(List.of(restored));

        assertTrue(cache.contains(restored));
        assertFalse(cache.contains(obsolete));
    }

    @Test
    void nullThumbnailIsCachedWithoutRepeatedDecode() {
        ThumbnailCache cache = new ThumbnailCache();
        Layer invalidImage = layer(1);
        AtomicInteger loads = new AtomicInteger();

        assertNull(cache.get(invalidImage, ignored -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cache.get(invalidImage, ignored -> {
            loads.incrementAndGet();
            return null;
        }));

        assertEquals(1, loads.get());
    }

    private static Layer layer(int value) {
        return new Layer("image", new byte[]{(byte) value});
    }
}
