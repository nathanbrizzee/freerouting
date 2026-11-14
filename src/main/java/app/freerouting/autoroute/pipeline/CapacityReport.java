package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ComponentOutline;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.ViaObstacleArea;
import app.freerouting.board.model.structure.Component;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.rules.NetClass;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Estimates before routing whether the open connections fit in the free routing area, and where
 * they do not: a congestion map (rectangular uniform wire density, RUDY).
 *
 * <p>The board is cut into square tiles. A tile's capacity is its area on the signal layers that
 * copper the router cannot move (pads, keepouts, planes, input-fixed copper) leaves free. Each open
 * connection needs its length (the octilinear distance between its ends) times its net's track
 * pitch (trace width plus clearance), spread evenly over its bounding box. A tile whose demand
 * exceeds its capacity cannot hold every connection that would cross it on a shortest path, so some
 * must detour or stay open; maze routers rarely use more than about 70 percent of the free area.
 *
 * <p>Density misses a wall with a gap: the wall fills only part of its tiles. So each line across
 * the board at a tile boundary is also checked as a cut: the connections whose ends lie on both
 * sides must cross it, and their pitches are set against the free length along it on all signal
 * layers. A cut above 100 percent forces connections around it or leaves them open. The estimate
 * ignores detours and vias, so it errs low on demand; it is a report and changes no routing.
 *
 * <p>Deterministic: the result depends only on the board and the settings.
 */
final class CapacityReport {

  /** Tiles along the board's longer side, at most. */
  static final int MAX_TILES_ALONG = 40;

  /** A tile is at least this many default track pitches wide. */
  static final int MIN_TILE_PITCHES = 20;

  /** Area samples along a tile's side, per layer. */
  static final int SAMPLES_PER_TILE = 8;

  /** Use of the free area above which a tile is called tight. */
  static final double TIGHT = 0.7;

  /** Regions named in the report, at most. */
  static final int MAX_REGIONS = 5;

  private final RoutingBoard board;
  private int cols;
  private int rows;
  private double tile;
  private double originX;
  private double originY;
  private int signalLayers;
  private double[] free;
  private double[] demand;
  private int connections;
  private final List<boolean[]> takenByLayer = new ArrayList<>();
  private double[][] ends;
  private double[] endPitch;
  private int scols;
  private int srows;
  private double step;
  private double trackLength;
  private double meanPitch;

  private CapacityReport(RoutingBoard board) {
    this.board = board;
  }

  /** Measures the board as it is now. Returns null when it has no area or no signal layer. */
  static CapacityReport measure(RoutingBoard board, RouterSettings settings) {
    IntBox box = board.getBoundingBox();
    if (box == null || box.isEmpty()) {
      return null;
    }
    CapacityReport report = new CapacityReport(board);
    List<Integer> layers = new ArrayList<>();
    for (int layer = 0; layer < board.getLayerCount(); layer++) {
      if (board.layerStructure.layers[layer].isSignal) {
        layers.add(layer);
      }
    }
    if (layers.isEmpty()) {
      return null;
    }
    report.signalLayers = layers.size();
    AirLine[] airlines = new DesignRulesChecker(board, null).getAllAirlines();
    Map<NetClass, Double> pitchOfClass = new HashMap<>();
    double[] pitches = new double[airlines.length];
    double pitchSum = 0;
    double smallestPitch = Double.MAX_VALUE;
    for (int i = 0; i < airlines.length; i++) {
      AirLine airline = airlines[i];
      NetClass netClass = airline.net.getNetClass();
      Double pitch = netClass != null ? pitchOfClass.get(netClass) : null;
      if (pitch == null) {
        pitch = pitch(board, settings, airline.net.netNumber);
        if (netClass != null) {
          pitchOfClass.put(netClass, pitch);
        }
      }
      pitches[i] = pitch;
      pitchSum += pitch;
      smallestPitch = Math.min(smallestPitch, pitch);
    }
    if (airlines.length == 0) {
      smallestPitch = 2.0 * Math.max(1, board.rules.getMinAllowedTraceHalfWidth());
    }
    double width = box.ur.x - box.ll.x;
    double height = box.ur.y - box.ll.y;
    report.tile =
        Math.max(Math.max(width, height) / MAX_TILES_ALONG, MIN_TILE_PITCHES * smallestPitch);
    report.cols = Math.max(1, (int) Math.ceil(width / report.tile));
    report.rows = Math.max(1, (int) Math.ceil(height / report.tile));
    report.originX = box.ll.x;
    report.originY = box.ll.y;
    report.measureFreeArea(layers);
    report.connections = airlines.length;
    report.meanPitch = airlines.length > 0 ? pitchSum / airlines.length : 0;
    report.demand = new double[report.cols * report.rows];
    report.ends = new double[airlines.length][];
    report.endPitch = pitches;
    for (int i = 0; i < airlines.length; i++) {
      FloatPoint a = airlines[i].fromCorner;
      FloatPoint b = airlines[i].toCorner;
      report.ends[i] = new double[] {a.x, a.y, b.x, b.y};
      report.spread(airlines[i].fromCorner, airlines[i].toCorner, pitches[i]);
    }
    return report;
  }

  /** The net's track pitch: its narrowest trace on an active layer plus its clearance there. */
  private static double pitch(RoutingBoard board, RouterSettings settings, int net) {
    AutorouteControl ctrl = new AutorouteControl(board, net, settings);
    double best = Double.MAX_VALUE;
    for (int layer = 0; layer < ctrl.layerCount; layer++) {
      if (!ctrl.layerActive[layer]) {
        continue;
      }
      double pitch =
          2.0 * ctrl.traceHalfWidth[layer]
              + board.clearanceValue(
                  ctrl.traceClearanceClassIndex, ctrl.traceClearanceClassIndex, layer);
      best = Math.min(best, pitch);
    }
    return best == Double.MAX_VALUE ? 1.0 : Math.max(1.0, best);
  }

  /**
   * Samples each tile on every signal layer and adds up the area that no immovable copper covers.
   */
  private void measureFreeArea(List<Integer> layers) {
    scols = cols * SAMPLES_PER_TILE;
    srows = rows * SAMPLES_PER_TILE;
    step = tile / SAMPLES_PER_TILE;
    double sampleArea = step * step;
    free = new double[cols * rows];
    IntBox box = board.getBoundingBox();
    for (int layer : layers) {
      boolean[] taken = new boolean[scols * srows];
      takenByLayer.add(taken);
      for (Item item : board.getItems()) {
        if (item instanceof ComponentOutline
            || item instanceof ViaObstacleArea
            || !UnreachableGroups.isImmovable(item)
            || !item.isOnLayer(layer)) {
          continue;
        }
        for (int i = 0; i < item.tileShapeCount(); i++) {
          if (item.shapeLayer(i) != layer) {
            continue;
          }
          TileShape shape = item.getTileShape(i);
          IntBox shapeBox = shape.boundingBox();
          int c0 = Math.max(0, (int) Math.floor((shapeBox.ll.x - originX) / step));
          int c1 = Math.min(scols - 1, (int) Math.floor((shapeBox.ur.x - originX) / step));
          int r0 = Math.max(0, (int) Math.floor((shapeBox.ll.y - originY) / step));
          int r1 = Math.min(srows - 1, (int) Math.floor((shapeBox.ur.y - originY) / step));
          for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
              int index = r * scols + c;
              if (!taken[index]
                  && shape.contains(
                      new FloatPoint(originX + (c + 0.5) * step, originY + (r + 0.5) * step))) {
                taken[index] = true;
              }
            }
          }
        }
      }
      for (int r = 0; r < srows; r++) {
        double y = originY + (r + 0.5) * step;
        for (int c = 0; c < scols; c++) {
          double x = originX + (c + 0.5) * step;
          if (x > box.ur.x || y > box.ur.y) {
            taken[r * scols + c] = true;
          } else if (!taken[r * scols + c]) {
            free[(r / SAMPLES_PER_TILE) * cols + c / SAMPLES_PER_TILE] += sampleArea;
          }
        }
      }
    }
  }

  /** Spreads one connection's track area evenly over its bounding box, at least a tile wide. */
  private void spread(FloatPoint from, FloatPoint to, double pitch) {
    double dx = Math.abs(to.x - from.x);
    double dy = Math.abs(to.y - from.y);
    double length = Math.max(dx, dy) + (Math.sqrt(2) - 1) * Math.min(dx, dy);
    trackLength += length;
    double area = length * pitch;
    if (area <= 0) {
      return;
    }
    double x0 = Math.min(from.x, to.x);
    double x1 = Math.max(from.x, to.x);
    double y0 = Math.min(from.y, to.y);
    double y1 = Math.max(from.y, to.y);
    if (x1 - x0 < tile) {
      double mid = (x0 + x1) / 2;
      x0 = mid - tile / 2;
      x1 = mid + tile / 2;
    }
    if (y1 - y0 < tile) {
      double mid = (y0 + y1) / 2;
      y0 = mid - tile / 2;
      y1 = mid + tile / 2;
    }
    double boxArea = (x1 - x0) * (y1 - y0);
    int c0 = Math.max(0, (int) Math.floor((x0 - originX) / tile));
    int c1 = Math.min(cols - 1, (int) Math.floor((x1 - originX) / tile));
    int r0 = Math.max(0, (int) Math.floor((y0 - originY) / tile));
    int r1 = Math.min(rows - 1, (int) Math.floor((y1 - originY) / tile));
    for (int r = r0; r <= r1; r++) {
      double ty0 = originY + r * tile;
      double oy = Math.min(y1, ty0 + tile) - Math.max(y0, ty0);
      for (int c = c0; c <= c1; c++) {
        double tx0 = originX + c * tile;
        double ox = Math.min(x1, tx0 + tile) - Math.max(x0, tx0);
        if (ox > 0 && oy > 0) {
          demand[r * cols + c] += area * ox * oy / boxArea;
        }
      }
    }
  }

  /** The use of a tile's free area; a tile with no free area and some demand counts as full. */
  double use(int index) {
    if (free[index] <= 0) {
      return demand[index] > 0 ? Double.POSITIVE_INFINITY : 0;
    }
    return demand[index] / free[index];
  }

  /** The demand of the whole board over its free area. */
  double overallUse() {
    double d = 0;
    double f = 0;
    for (int i = 0; i < free.length; i++) {
      d += demand[i];
      f += free[i];
    }
    return f > 0 ? d / f : 0;
  }

  /** Tiles whose demand exceeds their free area. */
  int overTiles() {
    int count = 0;
    for (int i = 0; i < free.length; i++) {
      if (use(i) > 1.0) {
        count++;
      }
    }
    return count;
  }

  /** Tiles above {@link #TIGHT} use, the over-full ones included. */
  int tightTiles() {
    int count = 0;
    for (int i = 0; i < free.length; i++) {
      if (use(i) > TIGHT) {
        count++;
      }
    }
    return count;
  }

  /** A connected group of over-full tiles. */
  record Region(int tiles, double peakUse, double centreX, double centreY, List<String> parts) {}

  /** The over-full regions (eight-joined tiles above 100 percent), the highest peak first. */
  List<Region> regions() {
    boolean[] seen = new boolean[free.length];
    List<Region> result = new ArrayList<>();
    for (int start = 0; start < free.length; start++) {
      if (seen[start] || use(start) <= 1.0) {
        continue;
      }
      List<Integer> members = new ArrayList<>();
      List<Integer> stack = new ArrayList<>();
      stack.add(start);
      seen[start] = true;
      while (!stack.isEmpty()) {
        int index = stack.remove(stack.size() - 1);
        members.add(index);
        int c = index % cols;
        int r = index / cols;
        for (int dr = -1; dr <= 1; dr++) {
          for (int dc = -1; dc <= 1; dc++) {
            int nc = c + dc;
            int nr = r + dr;
            if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) {
              continue;
            }
            int next = nr * cols + nc;
            if (!seen[next] && use(next) > 1.0) {
              seen[next] = true;
              stack.add(next);
            }
          }
        }
      }
      double peak = 0;
      double sx = 0;
      double sy = 0;
      boolean[] member = new boolean[free.length];
      for (int index : members) {
        peak = Math.max(peak, use(index));
        sx += originX + (index % cols + 0.5) * tile;
        sy += originY + (index / cols + 0.5) * tile;
        member[index] = true;
      }
      TreeSet<String> parts = new TreeSet<>();
      for (Component component : board.components.getAll()) {
        Point location = component.getLocation();
        if (location == null) {
          continue;
        }
        FloatPoint p = location.toFloat();
        int c = (int) Math.floor((p.x - originX) / tile);
        int r = (int) Math.floor((p.y - originY) / tile);
        if (c >= 0 && r >= 0 && c < cols && r < rows && member[r * cols + c]) {
          parts.add(component.name);
        }
      }
      result.add(
          new Region(
              members.size(), peak, sx / members.size(), sy / members.size(), List.copyOf(parts)));
    }
    result.sort(
        (a, b) ->
            a.peakUse() != b.peakUse()
                ? Double.compare(b.peakUse(), a.peakUse())
                : Integer.compare(b.tiles(), a.tiles()));
    return result;
  }

  /**
   * A line across the whole board at a tile boundary: the connections whose ends lie on both sides
   * must cross it, each taking its pitch of the free length along the line on the signal layers.
   */
  record Cut(boolean vertical, double at, int crossing, double tracksFit, double use) {}

  /** The cuts at every inner tile boundary, the highest use first. */
  List<Cut> cuts() {
    List<Cut> result = new ArrayList<>();
    for (int c = 1; c < cols; c++) {
      result.add(cut(true, c));
    }
    for (int r = 1; r < rows; r++) {
      result.add(cut(false, r));
    }
    result.sort(
        (a, b) ->
            a.use() != b.use()
                ? Double.compare(b.use(), a.use())
                : Integer.compare(b.crossing(), a.crossing()));
    return result;
  }

  private Cut cut(boolean vertical, int boundary) {
    double at = (vertical ? originX : originY) + boundary * tile;
    int sample = boundary * SAMPLES_PER_TILE;
    double freeLength = 0;
    for (boolean[] taken : takenByLayer) {
      int along = vertical ? srows : scols;
      for (int k = 0; k < along; k++) {
        int index = vertical ? k * scols + sample : sample * scols + k;
        if (!taken[index]) {
          freeLength += step;
        }
      }
    }
    int crossing = 0;
    double need = 0;
    for (int i = 0; i < ends.length; i++) {
      double a = vertical ? ends[i][0] : ends[i][1];
      double b = vertical ? ends[i][2] : ends[i][3];
      if (Math.min(a, b) < at && Math.max(a, b) > at) {
        crossing++;
        need += endPitch[i];
      }
    }
    double use = freeLength > 0 ? need / freeLength : (need > 0 ? Double.POSITIVE_INFINITY : 0);
    return new Cut(vertical, at, crossing, freeLength / Math.max(1.0, meanPitch), use);
  }

  /** One log line: the overall use, the over-full and tight tiles, and the worst regions. */
  String describe() {
    StringBuilder text = new StringBuilder();
    text.append(
        String.format(
            Locale.ROOT,
            "Capacity before routing: %d open connection(s) need about %s of the free routing area"
                + " on %d signal layer(s) (%.0f mm of track at %.3f mm mean pitch, before detours"
                + " and vias); %d of %d tiles of %.1f mm are over capacity, %d above %d%%.",
            connections,
            percent(overallUse()),
            signalLayers,
            PairRouting.toMm(board, trackLength),
            PairRouting.toMm(board, meanPitch),
            overTiles(),
            cols * rows,
            PairRouting.toMm(board, tile),
            tightTiles(),
            Math.round(TIGHT * 100)));
    if (overallUse() > TIGHT) {
      text.append(
          " The board as a whole is tight: expect connections to stay open unless area or layers"
              + " are added.");
    }
    List<Cut> cuts = cuts();
    int overCuts = 0;
    for (Cut cut : cuts) {
      if (cut.use() > 1.0) {
        overCuts++;
      }
    }
    for (int i = 0; i < cuts.size() && (i == 0 || (i < 3 && cuts.get(i).use() > 1.0)); i++) {
      Cut cut = cuts.get(i);
      text.append(i == 0 ? (overCuts > 0 ? " Over-full cuts: " : " Fullest cut: ") : "; ")
          .append(
              String.format(
                  Locale.ROOT,
                  "%s = %.1f mm, %d connection(s) must cross where about %.0f track(s) fit (%s)",
                  cut.vertical() ? "x" : "y",
                  PairRouting.toMm(board, cut.at()),
                  cut.crossing(),
                  cut.tracksFit(),
                  percent(cut.use())));
    }
    if (!cuts.isEmpty()) {
      text.append(overCuts > 3 ? "; " + (overCuts - 3) + " more over-full cut(s)." : ".");
    }
    List<Region> regions = regions();
    for (int i = 0; i < Math.min(MAX_REGIONS, regions.size()); i++) {
      Region region = regions.get(i);
      text.append(i == 0 ? " Over capacity: " : "; ")
          .append(
              String.format(
                  Locale.ROOT,
                  "around (%.1f, %.1f) mm, %d tile(s), up to %s",
                  PairRouting.toMm(board, region.centreX()),
                  PairRouting.toMm(board, region.centreY()),
                  region.tiles(),
                  percent(region.peakUse())));
      if (!region.parts().isEmpty()) {
        List<String> parts = region.parts();
        text.append(" (")
            .append(String.join(" ", parts.subList(0, Math.min(6, parts.size()))))
            .append(parts.size() > 6 ? " ..." : "")
            .append(')');
      }
    }
    if (regions.size() > MAX_REGIONS) {
      text.append("; ").append(regions.size() - MAX_REGIONS).append(" more region(s)");
    }
    if (!regions.isEmpty()) {
      text.append('.');
    }
    return text.toString();
  }

  private static String percent(double use) {
    return Double.isInfinite(use)
        ? "all of none (no free area)"
        : String.format(Locale.ROOT, "%.0f%%", use * 100);
  }
}
