package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class LayerMovesTest {
    private final Layer first = new Layer("first", new byte[]{1});
    private final Layer second = new Layer("second", new byte[]{2});
    private final Layer third = new Layer("third", new byte[]{3});
    private final List<Layer> layers = List.of(first, second, third);

    @Test
    void movesLayerUpWithoutMutatingInput() {
        List<Layer> moved = LayerMoves.move(layers, 1, -1);

        assertEquals(List.of(first, second, third), layers);
        assertNotSame(layers, moved);
        assertSame(second, moved.get(0));
        assertSame(first, moved.get(1));
        assertSame(third, moved.get(2));
    }

    @Test
    void movesLayerDownPreservingObjectIdentity() {
        List<Layer> moved = LayerMoves.move(layers, 1, 1);

        assertSame(first, moved.get(0));
        assertSame(third, moved.get(1));
        assertSame(second, moved.get(2));
    }

    @Test
    void boundaryMovesReturnUnchangedCopies() {
        List<Layer> beforeFirst = LayerMoves.move(layers, 0, -1);
        List<Layer> afterLast = LayerMoves.move(layers, 2, 1);

        assertEquals(layers, beforeFirst);
        assertEquals(layers, afterLast);
        assertNotSame(layers, beforeFirst);
        assertNotSame(layers, afterLast);
    }

    @Test
    void serializationUsesMovedLayerOrder() {
        ResContainer container = new ResContainer(7);
        container.layers.addAll(LayerMoves.move(layers, 0, 1));

        ResContainer parsed = ResContainer.parse(container.serialize());

        assertEquals(List.of("second", "first", "third"),
                parsed.layers.stream().map(layer -> layer.name).toList());
        assertEquals(List.of(2, 1, 3),
                parsed.layers.stream().map(layer -> (int) layer.data[0]).toList());
    }
}
