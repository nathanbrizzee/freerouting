package app.freerouting.drc;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.ObstacleArea;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.structure.Component;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The crowded regions an unfinished board's open connections pass through, for a person or agent
 * deciding which parts to move: what a person sees on screen, as numbers. The board is cut into
 * cells; each cell's copper fill is the share of sample points on the signal layers that lie in a
 * trace, via, pin or keepout (not in pours, which other nets may cross). Windows of 3 x 3 cells
 * that open connections cross are ranked by fill times the number crossing, and the first are
 * reported, without overlap, with the parts whose pins lie in them.
 */
public final class Congestion {

  /** Cells along the longer board side. */
  static final int CELLS_ALONG = 48;

  /** Sample points per cell side on each layer. */
  static final int SAMPLES = 4;

  /** Window side in cells. */
  static final int WINDOW = 3;

  private Congestion() {}

  /** One crowded region, in millimetres. */
  public static final class Region {
    @SerializedName("x_mm")
    public double leftMm;

    @SerializedName("y_mm")
    public double bottomMm;

    @SerializedName("width_mm")
    public double widthMm;

    @SerializedName("height_mm")
    public double heightMm;

    /** Share of sample points on the signal layers covered by copper or keepouts, 0 to 1. */
    @SerializedName("copper_fill")
    public double copperFill;

    @SerializedName("pins")
    public int pins;

    /** Components with pins in the region, most pins first. */
    @SerializedName("components")
    public List<String> components;

    @SerializedName("open_connections_crossing")
    public int openCrossing;

    @Override
    public String toString() {
      int named = Math.min(5, components.size());
      String parts = pins + " pins";
      if (named > 0) {
        parts += " of " + String.join(", ", components.subList(0, named));
        if (components.size() > named) {
          parts += " and " + (components.size() - named) + " more";
        }
      }
      return String.format(
          Locale.ROOT,
          "(%.1f, %.1f) to (%.1f, %.1f) mm: copper fill %.0f%%, %s; %d open connection%s it",
          leftMm,
          bottomMm,
          leftMm + widthMm,
          bottomMm + heightMm,
          100 * copperFill,
          parts,
          openCrossing,
          openCrossing == 1 ? " crosses" : "s cross");
    }
  }

  /** At most limit of the fullest regions the open connections cross; empty when none is open. */
  public static List<Region> regions(
      BasicBoard board, List<OpenConnections.Entry> open, int limit) {
    List<Region> result = new ArrayList<>();
    if (open.isEmpty() || limit <= 0) {
      return result;
    }
    IntBox box = board.getBoundingBox();
    double cell = Math.max(1.0, Math.max(box.width(), box.height()) / (double) CELLS_ALONG);
    int cols = Math.max(1, (int) Math.ceil(box.width() / cell));
    int rows = Math.max(1, (int) Math.ceil(box.height() / cell));
    double[][] fill = fill(board, box, cell, cols, rows);
    double toMm = OpenConnections.toMm(board);

    int windowCols = Math.min(WINDOW, cols);
    int windowRows = Math.min(WINDOW, rows);
    List<Region> candidates = new ArrayList<>();
    List<int[]> origins = new ArrayList<>();
    for (int r = 0; r + windowRows <= rows; r++) {
      for (int c = 0; c + windowCols <= cols; c++) {
        double x0 = (box.ll.x + c * cell) * toMm;
        double y0 = (box.ll.y + r * cell) * toMm;
        double x1 = x0 + windowCols * cell * toMm;
        double y1 = y0 + windowRows * cell * toMm;
        int crossing = 0;
        for (OpenConnections.Entry entry : open) {
          if (entry.fromMm != null
              && entry.toMm != null
              && segmentMeetsBox(entry.fromMm, entry.toMm, x0, y0, x1, y1)) {
            crossing++;
          }
        }
        if (crossing == 0) {
          continue;
        }
        double sum = 0;
        for (int dr = 0; dr < windowRows; dr++) {
          for (int dc = 0; dc < windowCols; dc++) {
            sum += fill[r + dr][c + dc];
          }
        }
        Region region = new Region();
        region.leftMm = x0;
        region.bottomMm = y0;
        region.widthMm = x1 - x0;
        region.heightMm = y1 - y0;
        region.copperFill = sum / (windowRows * windowCols);
        region.openCrossing = crossing;
        candidates.add(region);
        origins.add(new int[] {c, r});
      }
    }
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      order.add(i);
    }
    order.sort(
        Comparator.comparingDouble(
                (Integer i) -> -candidates.get(i).copperFill * candidates.get(i).openCrossing)
            .thenComparingInt(i -> origins.get(i)[1])
            .thenComparingInt(i -> origins.get(i)[0]));
    List<int[]> chosen = new ArrayList<>();
    for (int i : order) {
      if (result.size() >= limit) {
        break;
      }
      int[] w = origins.get(i);
      boolean overlaps = false;
      for (int[] other : chosen) {
        if (Math.abs(other[0] - w[0]) < windowCols && Math.abs(other[1] - w[1]) < windowRows) {
          overlaps = true;
          break;
        }
      }
      if (overlaps) {
        continue;
      }
      chosen.add(w);
      Region region = candidates.get(i);
      addPins(
          board,
          region,
          region.leftMm / toMm,
          region.bottomMm / toMm,
          (region.leftMm + region.widthMm) / toMm,
          (region.bottomMm + region.heightMm) / toMm);
      result.add(region);
    }
    return result;
  }

  /** Copper fill per cell, averaged over the signal layers. */
  private static double[][] fill(BasicBoard board, IntBox box, double cell, int cols, int rows) {
    List<Integer> layers = new ArrayList<>();
    for (int layer = 0; layer < board.getLayerCount(); layer++) {
      if (board.layerStructure.layers[layer].isSignal) {
        layers.add(layer);
      }
    }
    double[][] result = new double[rows][cols];
    if (layers.isEmpty()) {
      return result;
    }
    double step = cell / SAMPLES;
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        int covered = 0;
        int total = 0;
        for (int sy = 0; sy < SAMPLES; sy++) {
          for (int sx = 0; sx < SAMPLES; sx++) {
            IntPoint point =
                new IntPoint(
                    (int) Math.round(box.ll.x + c * cell + (sx + 0.5) * step),
                    (int) Math.round(box.ll.y + r * cell + (sy + 0.5) * step));
            for (int layer : layers) {
              total++;
              if (blocked(board, point, layer)) {
                covered++;
              }
            }
          }
        }
        result[r][c] = total == 0 ? 0 : (double) covered / total;
      }
    }
    return result;
  }

  private static boolean blocked(BasicBoard board, IntPoint point, int layer) {
    for (Item item : board.pickItems(point, layer, null)) {
      if (item instanceof Trace
          || item instanceof DrillItem
          || (item instanceof ObstacleArea && !(item instanceof ConductionArea))) {
        return true;
      }
    }
    return false;
  }

  private static void addPins(
      BasicBoard board, Region region, double x0, double y0, double x1, double y1) {
    Map<String, Integer> byComponent = new TreeMap<>();
    for (Pin pin : board.getPins()) {
      var centre = pin.getCenter().toFloat();
      if (centre.x < x0 || centre.x > x1 || centre.y < y0 || centre.y > y1) {
        continue;
      }
      region.pins++;
      Component component = board.components.get(pin.getComponentId());
      if (component != null) {
        byComponent.merge(component.name, 1, Integer::sum);
      }
    }
    List<String> names = new ArrayList<>(byComponent.keySet());
    names.sort(
        Comparator.comparingInt((String name) -> -byComponent.get(name))
            .thenComparing(name -> name));
    region.components = names;
  }

  /** Whether segment ab meets the box from (x0, y0) to (x1, y1). */
  static boolean segmentMeetsBox(
      double[] a, double[] b, double x0, double y0, double x1, double y1) {
    double t0 = 0;
    double t1 = 1;
    double dx = b[0] - a[0];
    double dy = b[1] - a[1];
    double[] p = {-dx, dx, -dy, dy};
    double[] q = {a[0] - x0, x1 - a[0], a[1] - y0, y1 - a[1]};
    for (int i = 0; i < 4; i++) {
      if (p[i] == 0) {
        if (q[i] < 0) {
          return false;
        }
      } else {
        double t = q[i] / p[i];
        if (p[i] < 0) {
          t0 = Math.max(t0, t);
        } else {
          t1 = Math.min(t1, t);
        }
      }
    }
    return t0 <= t1;
  }
}
