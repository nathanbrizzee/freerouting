package app.freerouting.datastructures;

import app.freerouting.datastructures.ShapeTree.Leaf;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.RegularTileShape;
import java.util.ArrayList;
import java.util.List;

/**
 * A second index over the leaves of one layer of a {@link MinAreaTree}, for overlap queries that
 * only want that layer. The main tree holds every layer, so on a board with many layers and large
 * pours most of the leaves a query visits are on other layers. The index is a binary tree of
 * bounding shapes like the main tree, rebuilt balanced after a quarter of its size in inserts. Its
 * shape never changes a query result: the callers sort the leaves they get.
 */
final class LayerLeafIndex {

  private static final int REBUILD_MIN_INSERTS = 64;

  private Node root;
  private int size;
  private int insertsSinceRebuild;

  /** A node of the index; a leaf node refers to one leaf of the main tree. */
  static final class Node {
    RegularTileShape bounds;
    Node parent;
    Node first;
    Node second;
    final Leaf leaf;
    final LayerLeafIndex owner;

    private Node(RegularTileShape bounds, Leaf leaf, LayerLeafIndex owner) {
      this.bounds = bounds;
      this.leaf = leaf;
      this.owner = owner;
    }
  }

  /** Adds a leaf of the main tree and returns its node, which the leaf keeps for removal. */
  Node insert(Leaf leaf) {
    Node node = new Node(leaf.boundingShape, leaf, this);
    ++this.size;
    if (this.root == null) {
      this.root = node;
    } else {
      Node sibling = locate(node.bounds);
      Node parent = new Node(sibling.bounds.union(node.bounds), null, this);
      Node grandParent = sibling.parent;
      parent.parent = grandParent;
      if (grandParent == null) {
        this.root = parent;
      } else if (grandParent.first == sibling) {
        grandParent.first = parent;
      } else {
        grandParent.second = parent;
      }
      parent.first = sibling;
      parent.second = node;
      sibling.parent = parent;
      node.parent = parent;
    }
    if (++this.insertsSinceRebuild >= Math.max(REBUILD_MIN_INSERTS, this.size / 4)) {
      rebuild();
    }
    return node;
  }

  /** Goes down to the leaf whose bounds grow least, widening the bounds on the way. */
  private Node locate(RegularTileShape bounds) {
    Node node = this.root;
    while (node.leaf == null) {
      node.bounds = bounds.union(node.bounds);
      double firstIncrease = bounds.union(node.first.bounds).area() - node.first.bounds.area();
      double secondIncrease = bounds.union(node.second.bounds).area() - node.second.bounds.area();
      node = firstIncrease <= secondIncrease ? node.first : node.second;
    }
    return node;
  }

  /** Removes a node returned by {@link #insert(Leaf)}. */
  void remove(Node node) {
    --this.size;
    Node parent = node.parent;
    node.parent = null;
    if (parent == null) {
      this.root = null;
      return;
    }
    Node other = parent.first == node ? parent.second : parent.first;
    Node grandParent = parent.parent;
    other.parent = grandParent;
    if (grandParent == null) {
      this.root = other;
      return;
    }
    if (grandParent.first == parent) {
      grandParent.first = other;
    } else {
      grandParent.second = other;
    }
    for (Node n = grandParent; n != null; n = n.parent) {
      RegularTileShape newBounds = n.first.bounds.union(n.second.bounds);
      if (newBounds.contains(n.bounds)) {
        break;
      }
      n.bounds = newBounds;
    }
  }

  /** Appends the leaves whose bounds intersect {@code shape}, in no particular order. */
  void overlaps(RegularTileShape shape, ArrayStack<Node> stack, List<Leaf> result) {
    if (this.root == null) {
      return;
    }
    stack.reset();
    stack.push(this.root);
    Node node;
    while ((node = stack.pop()) != null) {
      if (node.bounds.intersects(shape)) {
        if (node.leaf != null) {
          result.add(node.leaf);
        } else {
          stack.push(node.first);
          stack.push(node.second);
        }
      }
    }
  }

  /**
   * Rebuilds the index balanced: each group of leaves is split at the median center along its
   * longer side. Ties are broken by the order of a walk of the old index, which the same inserts
   * and removals repeat, so the same history gives the same index.
   */
  private void rebuild() {
    this.insertsSinceRebuild = 0;
    if (this.size < 3) {
      return;
    }
    List<Node> leaves = new ArrayList<>(this.size);
    ArrayStack<Node> stack = new ArrayStack<>(Math.max(64, this.size));
    stack.reset();
    stack.push(this.root);
    Node node;
    while ((node = stack.pop()) != null) {
      if (node.leaf != null) {
        leaves.add(node);
      } else {
        stack.push(node.first);
        stack.push(node.second);
      }
    }
    Entry[] entries = new Entry[leaves.size()];
    for (int i = 0; i < entries.length; i++) {
      Node leafNode = leaves.get(i);
      IntBox box = leafNode.bounds.boundingBox();
      entries[i] =
          new Entry(
              leafNode,
              0.5 * ((double) box.ll.x + box.ur.x),
              0.5 * ((double) box.ll.y + box.ur.y),
              i);
    }
    this.root = build(entries, 0, entries.length, null);
  }

  private Node build(Entry[] entries, int from, int to, Node parent) {
    if (to - from == 1) {
      Node leafNode = entries[from].node;
      leafNode.parent = parent;
      return leafNode;
    }
    double minX = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    for (int i = from; i < to; i++) {
      minX = Math.min(minX, entries[i].x);
      maxX = Math.max(maxX, entries[i].x);
      minY = Math.min(minY, entries[i].y);
      maxY = Math.max(maxY, entries[i].y);
    }
    boolean byX = maxX - minX >= maxY - minY;
    int mid = (from + to) >>> 1;
    select(entries, from, to - 1, mid, byX);
    Node inner = new Node(null, null, this);
    inner.parent = parent;
    inner.first = build(entries, from, mid, inner);
    inner.second = build(entries, mid, to, inner);
    inner.bounds = inner.first.bounds.union(inner.second.bounds);
    return inner;
  }

  private static int compare(Entry a, Entry b, boolean byX) {
    int c = byX ? Double.compare(a.x, b.x) : Double.compare(a.y, b.y);
    return c != 0 ? c : Integer.compare(a.rank, b.rank);
  }

  /**
   * Reorders entries[lo..hi] so that the entry at k is the one a sort would put there, with the
   * smaller ones before it and the larger ones after it (Hoare's selection, middle pivot).
   */
  private static void select(Entry[] entries, int lo, int hi, int k, boolean byX) {
    while (lo < hi) {
      Entry pivot = entries[(lo + hi) >>> 1];
      int i = lo;
      int j = hi;
      while (i <= j) {
        while (compare(entries[i], pivot, byX) < 0) {
          i++;
        }
        while (compare(entries[j], pivot, byX) > 0) {
          j--;
        }
        if (i <= j) {
          Entry tmp = entries[i];
          entries[i] = entries[j];
          entries[j] = tmp;
          i++;
          j--;
        }
      }
      if (k <= j) {
        hi = j;
      } else if (k >= i) {
        lo = i;
      } else {
        return;
      }
    }
  }

  private record Entry(Node node, double x, double y, int rank) {}
}
