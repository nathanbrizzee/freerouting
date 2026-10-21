package app.freerouting.board.model.items;

import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.TileShape;
import java.util.ArrayList;
import java.util.List;

/**
 * The tiles of an area in a grid of their bounding boxes, to find the tiles that contain a point
 * without testing every tile. A point that is not an {@link IntPoint} is tested against every tile,
 * so the answer is exactly that of {@link TileShape#contains(Point)} on each tile.
 */
final class TileBoxGrid {

  private static final int MAX_CELLS_PER_SIDE = 64;

  private final TileShape[] tiles;
  private final IntBox[] boxes;
  private final IntBox bounds;
  private final int cellsX;
  private final int cellsY;
  private final double cellWidth;
  private final double cellHeight;
  private final int[][] cellTiles;

  TileBoxGrid(TileShape[] tiles) {
    this.tiles = tiles;
    this.boxes = new IntBox[tiles.length];
    IntBox union = IntBox.EMPTY;
    for (int i = 0; i < tiles.length; i++) {
      boxes[i] = tiles[i].boundingBox();
      union = union.union(boxes[i]);
    }
    this.bounds = union;
    int side = (int) Math.ceil(Math.sqrt(tiles.length));
    side = Math.max(1, Math.min(MAX_CELLS_PER_SIDE, side));
    this.cellsX = side;
    this.cellsY = side;
    this.cellWidth = Math.max(1.0, ((double) bounds.ur.x - bounds.ll.x + 1) / cellsX);
    this.cellHeight = Math.max(1.0, ((double) bounds.ur.y - bounds.ll.y + 1) / cellsY);
    List<List<Integer>> lists = new ArrayList<>(cellsX * cellsY);
    for (int i = 0; i < cellsX * cellsY; i++) {
      lists.add(new ArrayList<>());
    }
    for (int t = 0; t < tiles.length; t++) {
      if (boxes[t].isEmpty()) {
        continue;
      }
      int x0 = cellX(boxes[t].ll.x);
      int x1 = cellX(boxes[t].ur.x);
      int y0 = cellY(boxes[t].ll.y);
      int y1 = cellY(boxes[t].ur.y);
      for (int x = x0; x <= x1; x++) {
        for (int y = y0; y <= y1; y++) {
          lists.get(y * cellsX + x).add(t);
        }
      }
    }
    this.cellTiles = new int[lists.size()][];
    for (int i = 0; i < lists.size(); i++) {
      List<Integer> list = lists.get(i);
      int[] arr = new int[list.size()];
      for (int j = 0; j < arr.length; j++) {
        arr[j] = list.get(j);
      }
      cellTiles[i] = arr;
    }
  }

  private int cellX(int x) {
    int c = (int) ((x - (double) bounds.ll.x) / cellWidth);
    return Math.max(0, Math.min(cellsX - 1, c));
  }

  private int cellY(int y) {
    int c = (int) ((y - (double) bounds.ll.y) / cellHeight);
    return Math.max(0, Math.min(cellsY - 1, c));
  }

  /** Adds to result the indices of the tiles that contain point, in increasing order. */
  void tilesContaining(Point point, List<Integer> result) {
    result.clear();
    if (tiles.length == 0) {
      return;
    }
    if (point instanceof IntPoint p) {
      if (p.x < bounds.ll.x || p.x > bounds.ur.x || p.y < bounds.ll.y || p.y > bounds.ur.y) {
        return;
      }
      for (int t : cellTiles[cellY(p.y) * cellsX + cellX(p.x)]) {
        IntBox box = boxes[t];
        if (p.x >= box.ll.x
            && p.x <= box.ur.x
            && p.y >= box.ll.y
            && p.y <= box.ur.y
            && tiles[t].contains(point)) {
          result.add(t);
        }
      }
      return;
    }
    for (int t = 0; t < tiles.length; t++) {
      if (tiles[t].contains(point)) {
        result.add(t);
      }
    }
  }

  TileShape tile(int index) {
    return tiles[index];
  }
}
