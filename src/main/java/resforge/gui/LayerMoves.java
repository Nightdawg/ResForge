package resforge.gui;

import resforge.res.Layer;

import java.util.ArrayList;
import java.util.List;

final class LayerMoves {
    private LayerMoves() {
    }

    static List<Layer> move(List<Layer> layers, int from, int delta) {
        List<Layer> reordered = new ArrayList<>(layers);
        long target = (long) from + delta;
        if(from < 0 || from >= reordered.size() || target < 0 || target >= reordered.size())
            return reordered;
        Layer layer = reordered.remove(from);
        reordered.add((int) target, layer);
        return reordered;
    }
}
