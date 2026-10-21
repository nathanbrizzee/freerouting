package app.freerouting.autoroute.plan;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-net corridors on a square grid: for each net and layer, the cells its route may use without
 * extra cost. Built from a board's copper (for the oracle experiment) or, later, from a global
 * plan.
 */
public final class CorridorMap {

  private final double originX;
  private final double originY;
  private final double cell;
  private final int nx;
  private final int ny;
  private final int layers;
  private final Map<Integer, BitSet[]> nets = new TreeMap<>();

  CorridorMap(double originX, double originY, double cell, int nx, int ny, int layers) {
    this.originX = originX;
    this.originY = originY;
    this.cell = cell;
    this.nx = nx;
    this.ny = ny;
    this.layers = layers;
  }

  /** An empty map over the board's bounding box with cells of {@code cell} board units. */
  static CorridorMap forBoard(RoutingBoard board, double cell) {
    IntBox box = board.getBoundingBox();
    int nx = Math.max(1, (int) Math.ceil((box.ur.x - box.ll.x) / cell));
    int ny = Math.max(1, (int) Math.ceil((box.ur.y - box.ll.y) / cell));
    return new CorridorMap(box.ll.x, box.ll.y, cell, nx, ny, board.getLayerCount());
  }

  /**
   * The copper of every net that is fully connected on {@code board}: traces with their width, vias
   * on all their layers, and pins on theirs.
   */
  static CorridorMap fromRoutedBoard(RoutingBoard board, double cell) {
    CorridorMap map = forBoard(board, cell);
    for (int net = 1; net <= board.rules.nets.maxNetNumber(); net++) {
      Collection<Item> items = board.getConnectableItems(net);
      if (items.isEmpty() || !fullyConnected(items, net)) {
        continue;
      }
      for (Item item : items) {
        if (item instanceof PolylineTrace trace) {
          FloatPoint[] corners = trace.polyline().cornerApproxArr();
          for (int i = 0; i + 1 < corners.length; i++) {
            map.markSegment(
                net, trace.getLayer(), corners[i], corners[i + 1], trace.getHalfWidth());
          }
        } else if (item instanceof Via via) {
          FloatPoint center = via.getCenter().toFloat();
          for (int layer = via.firstLayer(); layer <= via.lastLayer(); layer++) {
            map.markSegment(net, layer, center, center, 0);
          }
        } else if (item instanceof Pin pin) {
          FloatPoint center = pin.getCenter().toFloat();
          for (int layer = pin.firstLayer(); layer <= pin.lastLayer(); layer++) {
            map.markSegment(net, layer, center, center, 0);
          }
        } else if (item instanceof Trace trace) {
          map.markSegment(
              net,
              trace.getLayer(),
              trace.firstCorner().toFloat(),
              trace.lastCorner().toFloat(),
              trace.getHalfWidth());
        }
      }
    }
    return map;
  }

  private static boolean fullyConnected(Collection<Item> items, int net) {
    Item first = items.iterator().next();
    Set<Item> connected = first.getConnectedSet(net);
    return connected.containsAll(items);
  }

  /** Whether the map has a corridor for {@code net}. */
  public boolean hasNet(int net) {
    return this.nets.containsKey(net);
  }

  /** Number of nets with a corridor. */
  public int netCount() {
    return this.nets.size();
  }

  private BitSet layerCells(int net, int layer) {
    BitSet[] perLayer = this.nets.computeIfAbsent(net, n -> new BitSet[this.layers]);
    if (perLayer[layer] == null) {
      perLayer[layer] = new BitSet(this.nx * this.ny);
    }
    return perLayer[layer];
  }

  /** Marks the cells within {@code halfWidth} of the segment from a to b. */
  void markSegment(int net, int layer, FloatPoint a, FloatPoint b, double halfWidth) {
    if (layer < 0 || layer >= this.layers) {
      return;
    }
    BitSet cells = layerCells(net, layer);
    double length = a.distance(b);
    int steps = Math.max(1, (int) Math.ceil(length / (0.5 * this.cell)));
    for (int s = 0; s <= steps; s++) {
      double t = (double) s / steps;
      double x = a.x + t * (b.x - a.x);
      double y = a.y + t * (b.y - a.y);
      int x0 = cellX(x - halfWidth);
      int x1 = cellX(x + halfWidth);
      int y0 = cellY(y - halfWidth);
      int y1 = cellY(y + halfWidth);
      for (int cy = y0; cy <= y1; cy++) {
        for (int cx = x0; cx <= x1; cx++) {
          cells.set(cy * this.nx + cx);
        }
      }
    }
  }

  private int cellX(double x) {
    return Math.clamp((int) Math.floor((x - this.originX) / this.cell), 0, this.nx - 1);
  }

  private int cellY(double y) {
    return Math.clamp((int) Math.floor((y - this.originY) / this.cell), 0, this.ny - 1);
  }

  /**
   * The share of the move from a to b on {@code layer} that lies outside the net's corridor, from
   * samples every half cell. 0 for a net without a corridor.
   */
  public double outsideFraction(int net, int layer, FloatPoint a, FloatPoint b) {
    BitSet[] perLayer = this.nets.get(net);
    if (perLayer == null || layer < 0 || layer >= this.layers) {
      return 0;
    }
    BitSet cells = perLayer[layer];
    double length = a.distance(b);
    int samples = Math.max(1, (int) Math.ceil(length / (0.5 * this.cell)));
    int outside = 0;
    for (int s = 0; s < samples; s++) {
      double t = (s + 0.5) / samples;
      int index = cellY(a.y + t * (b.y - a.y)) * this.nx + cellX(a.x + t * (b.x - a.x));
      if (cells == null || !cells.get(index)) {
        outside++;
      }
    }
    return (double) outside / samples;
  }

  /** A copy with every corridor widened by {@code margin} cells in each direction. */
  CorridorMap dilated(int margin) {
    CorridorMap result =
        new CorridorMap(this.originX, this.originY, this.cell, this.nx, this.ny, this.layers);
    for (Map.Entry<Integer, BitSet[]> entry : this.nets.entrySet()) {
      BitSet[] source = entry.getValue();
      BitSet[] target = new BitSet[this.layers];
      for (int layer = 0; layer < this.layers; layer++) {
        if (source[layer] == null) {
          continue;
        }
        BitSet cells = new BitSet(this.nx * this.ny);
        for (int i = source[layer].nextSetBit(0); i >= 0; i = source[layer].nextSetBit(i + 1)) {
          int cx = i % this.nx;
          int cy = i / this.nx;
          for (int y = Math.max(0, cy - margin); y <= Math.min(this.ny - 1, cy + margin); y++) {
            for (int x = Math.max(0, cx - margin); x <= Math.min(this.nx - 1, cx + margin); x++) {
              cells.set(y * this.nx + x);
            }
          }
        }
        target[layer] = cells;
      }
      result.nets.put(entry.getKey(), target);
    }
    return result;
  }

  /** Writes the map as text: a grid line, then one line per net and layer with its cells. */
  void write(Path path) throws IOException {
    try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      out.write("corridors 1\n");
      out.write(
          "grid "
              + this.originX
              + " "
              + this.originY
              + " "
              + this.cell
              + " "
              + this.nx
              + " "
              + this.ny
              + " "
              + this.layers
              + "\n");
      for (Map.Entry<Integer, BitSet[]> entry : this.nets.entrySet()) {
        BitSet[] perLayer = entry.getValue();
        for (int layer = 0; layer < this.layers; layer++) {
          BitSet cells = perLayer[layer];
          if (cells == null || cells.isEmpty()) {
            continue;
          }
          StringBuilder line = new StringBuilder();
          line.append("net ").append(entry.getKey()).append(' ').append(layer);
          for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
            line.append(' ').append(i);
          }
          out.write(line.append('\n').toString());
        }
      }
    }
  }

  /** Reads a map written by {@link #write(Path)}. */
  static CorridorMap read(Path path) throws IOException {
    try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String header = in.readLine();
      if (header == null || !header.startsWith("corridors ")) {
        throw new IOException("not a corridor file: " + path);
      }
      String[] grid = in.readLine().split(" ");
      CorridorMap map =
          new CorridorMap(
              Double.parseDouble(grid[1]),
              Double.parseDouble(grid[2]),
              Double.parseDouble(grid[3]),
              Integer.parseInt(grid[4]),
              Integer.parseInt(grid[5]),
              Integer.parseInt(grid[6]));
      String line;
      while ((line = in.readLine()) != null) {
        String[] parts = line.split(" ");
        if (parts.length < 3 || !"net".equals(parts[0])) {
          continue;
        }
        BitSet cells = map.layerCells(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        for (int i = 3; i < parts.length; i++) {
          cells.set(Integer.parseInt(parts[i]));
        }
      }
      return map;
    }
  }
}
