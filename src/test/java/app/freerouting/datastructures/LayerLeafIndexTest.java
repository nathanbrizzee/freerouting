package app.freerouting.datastructures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.geometry.planar.FortyfiveDegreeBoundingDirections;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.TileShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A per-layer overlap query returns exactly what the full query returns once the other layers'
 * leaves are left out, in the same order, through inserts, removals and index rebuilds.
 */
class LayerLeafIndexTest {

  private static final int LAYERS = 3;

  @Test
  void layerQueryMatchesFilteredFullQuery() {
    LayeredTree tree = new LayeredTree();
    Random random = new Random(7);
    List<Box> live = new ArrayList<>();
    int nextId = 0;
    for (int round = 0; round < 4; round++) {
      for (int i = 0; i < 1500; i++) {
        Box box = Box.random(nextId++, random);
        tree.insert(box);
        live.add(box);
      }
      for (int i = 0; i < 600; i++) {
        Box box = live.remove(random.nextInt(live.size()));
        tree.remove(box.entries);
      }
      for (int q = 0; q < 200; q++) {
        IntBox query = Box.randomShape(random, 20000);
        int layer = random.nextInt(LAYERS);
        List<ShapeTree.Leaf> expected = new ArrayList<>();
        for (ShapeTree.Leaf leaf : tree.overlaps(query)) {
          int leafLayer = ((Box) leaf.object).layer;
          if (leafLayer == layer || leafLayer < 0) {
            expected.add(leaf);
          }
        }
        List<ShapeTree.Leaf> actual = new ArrayList<>();
        tree.overlapsUnlocked(query, layer, actual);
        assertEquals(expected, actual, "round " + round + ", query " + q);
      }
    }
    assertTrue(tree.size() > 0);
  }

  private static final class LayeredTree extends MinAreaTree {
    LayeredTree() {
      super(FortyfiveDegreeBoundingDirections.INSTANCE);
    }

    @Override
    protected int indexedLayerCount() {
      return LAYERS;
    }

    @Override
    protected int leafLayer(ShapeTree.Leaf leaf) {
      return ((Box) leaf.object).layer;
    }
  }

  private static final class Box implements ShapeTree.Storable {
    final int id;
    final int layer;
    final TileShape shape;
    ShapeTree.Leaf[] entries;

    Box(int id, int layer, TileShape shape) {
      this.id = id;
      this.layer = layer;
      this.shape = shape;
    }

    static Box random(int id, Random random) {
      // Layer -1 stands for a shape on all layers, kept outside the per-layer indexes.
      int layer = random.nextInt(LAYERS + 1) - 1;
      return new Box(id, layer, randomShape(random, random.nextInt(10) == 0 ? 30000 : 2000));
    }

    static IntBox randomShape(Random random, int maxSize) {
      int x = random.nextInt(200000) - 100000;
      int y = random.nextInt(200000) - 100000;
      return new IntBox(x, y, x + 1 + random.nextInt(maxSize), y + 1 + random.nextInt(maxSize));
    }

    @Override
    public int compareTo(Object other) {
      return Integer.compare(id, ((Box) other).id);
    }

    @Override
    public int treeShapeCount(ShapeTree shapeTree) {
      return 1;
    }

    @Override
    public TileShape getTreeShape(ShapeTree shapeTree, int index) {
      return shape;
    }

    @Override
    public void setSearchTreeEntries(ShapeTree.Leaf[] entries, ShapeTree tree) {
      this.entries = entries;
    }
  }
}
