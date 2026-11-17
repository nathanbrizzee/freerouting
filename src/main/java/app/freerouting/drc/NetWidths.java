package app.freerouting.drc;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetClass;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which nets may be routed narrower than their net class along a connection, and the traces of a
 * routed board that are.
 *
 * <p>A neck at a pin, the short stretch into a pad narrower than the trace, is normal practice on
 * any net. A trace narrower than its class along the run is another thing: on a signal net it is a
 * way through a dense region, on a net whose class was made wide for current it is a defect no
 * clearance check sees. So a net whose class is wider than the signal width never gets a run neck:
 * its connection stays open instead. The signal width is {@code router.neck_max_class_width_um}
 * when set, else the width of the net class most nets belong to (on a tie, the narrower class).
 */
public final class NetWidths {

  private NetWidths() {}

  /** The widest trace half width of a net class over the layers it routes on, 0 when none. */
  static int classHalfWidth(NetClass netClass) {
    int result = 0;
    for (int layer = 0; layer < netClass.layerCount(); layer++) {
      if (netClass.isActiveRoutingLayer(layer)) {
        result = Math.max(result, netClass.getTraceHalfWidth(layer));
      }
    }
    return result;
  }

  /**
   * The signal half width in board units: {@code maxClassWidthUm} when it is above 0, else the
   * class half width of the net class most nets belong to.
   */
  public static int signalHalfWidth(BasicBoard board, Double maxClassWidthUm) {
    if (maxClassWidthUm != null && maxClassWidthUm > 0) {
      int resolution = Math.max(1, board.communication.resolution);
      return (int)
          Math.round(
              Unit.scale(maxClassWidthUm * resolution, Unit.UM, board.communication.unit) / 2);
    }
    Map<NetClass, Integer> netsPerClass = new HashMap<>();
    for (int netNo = 1; netNo <= board.rules.nets.maxNetNumber(); netNo++) {
      Net net = board.rules.nets.get(netNo);
      if (net != null && net.getNetClass() != null) {
        netsPerClass.merge(net.getNetClass(), 1, Integer::sum);
      }
    }
    int bestCount = 0;
    int result = classHalfWidth(board.rules.getDefaultNetClass());
    for (Map.Entry<NetClass, Integer> entry : netsPerClass.entrySet()) {
      int halfWidth = classHalfWidth(entry.getKey());
      int count = entry.getValue();
      if (count > bestCount || (count == bestCount && halfWidth < result)) {
        bestCount = count;
        result = halfWidth;
      }
    }
    return result;
  }

  /** Whether the class of the net is wider than the signal half width. */
  public static boolean isWide(BasicBoard board, int netNo, int signalHalfWidth) {
    Net net = board.rules.nets.get(netNo);
    return net != null
        && net.getNetClass() != null
        && classHalfWidth(net.getNetClass()) > signalHalfWidth;
  }

  /** Whether a connection of the net may be routed narrower than its class along the run. */
  public static boolean mayNeckRun(BasicBoard board, int netNo, Double maxClassWidthUm) {
    return !isWide(board, netNo, signalHalfWidth(board, maxClassWidthUm));
  }

  /**
   * The items of {@code dests} no farther than {@code stubMaxUm} (edge to edge, by bounding box)
   * from some item of {@code starts}: the ends a wide net's stub may reach. Empty when the limit is
   * not above 0.
   */
  public static Set<Item> withinStub(
      BasicBoard board, Set<Item> starts, Set<Item> dests, Double stubMaxUm) {
    Set<Item> result = new LinkedHashSet<>();
    if (stubMaxUm == null || stubMaxUm <= 0 || starts == null || dests == null) {
      return result;
    }
    double limit = stubLimit(board, stubMaxUm);
    List<TileShape> startShapes = new ArrayList<>();
    for (Item start : starts) {
      for (int k = 0; k < start.tileShapeCount(); k++) {
        TileShape shape = start.getTileShape(k);
        if (shape != null) {
          startShapes.add(shape);
        }
      }
    }
    for (Item dest : dests) {
      boolean near = false;
      for (int k = 0; k < dest.tileShapeCount() && !near; k++) {
        TileShape destShape = dest.getTileShape(k);
        for (int m = 0; destShape != null && m < startShapes.size() && !near; m++) {
          near = shapeDistance(startShapes.get(m), destShape) <= limit;
        }
      }
      if (near) {
        result.add(dest);
      }
    }
    return result;
  }

  /** The distance between two convex shapes: 0 when they meet, else the nearest corner gap. */
  static double shapeDistance(TileShape a, TileShape b) {
    if (a.intersects(b)) {
      return 0;
    }
    double best = Double.MAX_VALUE;
    for (int i = 0; i < a.borderLineCount(); i++) {
      best = Math.min(best, b.distance(a.cornerApprox(i)));
    }
    for (int i = 0; i < b.borderLineCount(); i++) {
      best = Math.min(best, a.distance(b.cornerApprox(i)));
    }
    return best;
  }

  /** Whether narrowed copper of this length (board units) is no longer than a wide stub allows. */
  public static boolean isStub(BasicBoard board, double length, Double stubMaxUm) {
    return stubMaxUm != null && stubMaxUm > 0 && length <= 2 * stubLimit(board, stubMaxUm);
  }

  /** The stub limit in board units. */
  static double stubLimit(BasicBoard board, double stubMaxUm) {
    int resolution = Math.max(1, board.communication.resolution);
    return Unit.scale(stubMaxUm * resolution, Unit.UM, board.communication.unit);
  }

  /** A net with traces narrower than its class away from its pins, in millimetres. */
  public record Narrowed(
      @SerializedName("net") String net,
      @SerializedName("net_class") String netClass,
      @SerializedName("class_width_mm") double classWidthMm,
      @SerializedName("narrowest_mm") double narrowestMm,
      @SerializedName("length_mm") double lengthMm,
      @SerializedName("traces") int traces,
      @SerializedName("wide_class") boolean wide) {
    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "%s (class %s, %.3f mm) at %.3f mm over %.1f mm in %d trace(s)%s",
          net,
          netClass,
          classWidthMm,
          narrowestMm,
          lengthMm,
          traces,
          wide ? "; the class is wider than the signal width: check the current it carries" : "");
    }
  }

  /**
   * The nets of the board with traces narrower than their class, other than the stretch into pads
   * (a trace every point of which lies near a pin of the net), sorted by net name. Traces a user
   * fixed are left out: the router did not lay them.
   */
  public static List<Narrowed> narrowed(BasicBoard board, Double maxClassWidthUm) {
    return narrowed(board, maxClassWidthUm, null);
  }

  /**
   * As {@link #narrowed(BasicBoard, Double)}, also leaving out the traces of wide-class nets that
   * are no longer than a stub ({@code neck_stub_max_um}).
   */
  public static List<Narrowed> narrowed(
      BasicBoard board, Double maxClassWidthUm, Double stubMaxUm) {
    double stubLimit = stubMaxUm != null && stubMaxUm > 0 ? stubLimit(board, stubMaxUm) : 0;
    int signalHalfWidth = signalHalfWidth(board, maxClassWidthUm);
    double toMm =
        Unit.scale(1.0, board.communication.unit, Unit.MM)
            / Math.max(1, board.communication.resolution);
    Map<Integer, List<Pin>> pinsByNet = new HashMap<>();
    for (Pin pin : board.getPins()) {
      for (int i = 0; i < pin.netCount(); i++) {
        pinsByNet.computeIfAbsent(pin.getNetNumber(i), k -> new ArrayList<>()).add(pin);
      }
    }
    Map<Integer, double[]> byNet = new TreeMap<>();
    for (Trace trace : board.getTraces()) {
      if (trace.netCount() != 1 || trace.isUserFixed()) {
        continue;
      }
      int netNo = trace.getNetNumber(0);
      int layer = trace.getLayer();
      int classHalfWidth = board.rules.getTraceHalfWidth(netNo, layer);
      if (trace.getHalfWidth() >= classHalfWidth) {
        continue;
      }
      if (padEntry(board, trace, pinsByNet.getOrDefault(netNo, List.of()))) {
        continue;
      }
      if (stubLimit > 0
          && trace.getLength() <= stubLimit
          && isWide(board, netNo, signalHalfWidth)) {
        continue;
      }
      double[] sums =
          byNet.computeIfAbsent(netNo, k -> new double[] {Double.MAX_VALUE, 0, 0, classHalfWidth});
      sums[0] = Math.min(sums[0], trace.getHalfWidth());
      sums[1] += trace.getLength();
      sums[2]++;
      sums[3] = Math.max(sums[3], classHalfWidth);
    }
    List<Narrowed> result = new ArrayList<>();
    for (Map.Entry<Integer, double[]> entry : byNet.entrySet()) {
      Net net = board.rules.nets.get(entry.getKey());
      double[] sums = entry.getValue();
      result.add(
          new Narrowed(
              net != null ? net.name : "?",
              net != null && net.getNetClass() != null ? net.getNetClass().getName() : "?",
              2 * sums[3] * toMm,
              2 * sums[0] * toMm,
              sums[1] * toMm,
              (int) sums[2],
              isWide(board, entry.getKey(), signalHalfWidth)));
    }
    result.sort((a, b) -> a.net().compareTo(b.net()));
    return result;
  }

  /**
   * The open connections of nets whose class is wider than the signal width, one line per net name,
   * sorted: these are never narrowed along the run, so a connection that only a narrower trace
   * would close stays open.
   */
  public static List<String> wideOpen(
      BasicBoard board, List<OpenConnections.Entry> open, Double maxClassWidthUm) {
    if (open.isEmpty()) {
      return List.of();
    }
    int signalHalfWidth = signalHalfWidth(board, maxClassWidthUm);
    double toMm =
        Unit.scale(1.0, board.communication.unit, Unit.MM)
            / Math.max(1, board.communication.resolution);
    Map<String, Integer> openByNet = new TreeMap<>();
    for (OpenConnections.Entry entry : open) {
      if (entry.net != null) {
        openByNet.merge(entry.net, 1, Integer::sum);
      }
    }
    List<String> result = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : openByNet.entrySet()) {
      for (Net net : board.rules.nets.get(entry.getKey())) {
        if (isWide(board, net.netNumber, signalHalfWidth)) {
          result.add(
              String.format(
                  Locale.ROOT,
                  "%s (class %s, %.3f mm): %d open; classes wider than %.3f mm are not narrowed"
                      + " along the run",
                  entry.getKey(),
                  net.getNetClass().getName(),
                  2 * classHalfWidth(net.getNetClass()) * toMm,
                  entry.getValue(),
                  2 * signalHalfWidth * toMm));
          break;
        }
      }
    }
    return result;
  }

  /**
   * Whether the trace is the stretch into pads: every point of it lies within the neck-down
   * distance of a pin of the net on its layer, the zone in which the insert narrows a trace to
   * enter a pin. A short trace between two neighbouring pins of the net counts; a trace from one
   * pin to a distant one does not.
   */
  private static boolean padEntry(BasicBoard board, Trace trace, List<Pin> pins) {
    FloatPoint[] corners =
        trace instanceof PolylineTrace polylineTrace
            ? polylineTrace.polyline().cornerApproxArr()
            : new FloatPoint[] {trace.firstCorner().toFloat(), trace.lastCorner().toFloat()};
    int layer = trace.getLayer();
    List<FloatPoint> centers = new ArrayList<>();
    List<Double> zones = new ArrayList<>();
    double smallestZone = Double.MAX_VALUE;
    for (Pin pin : pins) {
      if (!pin.isOnLayer(layer)) {
        continue;
      }
      double clearance =
          board.rules.clearanceMatrix.getValue(
              trace.clearanceClassIndex(), pin.clearanceClassIndex(), layer, true);
      // A little beyond the insert's zone: its neck ends where the zone ends.
      double zone = 1.05 * 2 * (0.5 * pin.getMaxWidth(layer) + clearance);
      centers.add(pin.getCenter().toFloat());
      zones.add(zone);
      smallestZone = Math.min(smallestZone, zone);
    }
    if (centers.isEmpty()) {
      return false;
    }
    // Points along the trace no farther apart than a quarter of the smallest zone.
    double step = Math.max(1, smallestZone / 4);
    for (int i = 0; i < corners.length; i++) {
      FloatPoint from = corners[i];
      FloatPoint to = i + 1 < corners.length ? corners[i + 1] : from;
      int samples = Math.max(1, (int) Math.ceil(from.distance(to) / step));
      for (int k = 0; k <= samples; k++) {
        double t = (double) k / samples;
        FloatPoint p = new FloatPoint(from.x + t * (to.x - from.x), from.y + t * (to.y - from.y));
        boolean covered = false;
        for (int j = 0; j < centers.size() && !covered; j++) {
          covered = centers.get(j).distance(p) <= zones.get(j);
        }
        if (!covered) {
          return false;
        }
      }
    }
    return true;
  }
}
