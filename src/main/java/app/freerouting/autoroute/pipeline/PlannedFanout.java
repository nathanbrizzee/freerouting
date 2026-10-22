package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.library.Padstack;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.geometry.planar.Vector;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.ViaInfo;
import app.freerouting.rules.ViaRule;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planned fanout: before any routing, every SMD pin that still needs a connection gets an escape
 * via on a short stub, so no pin is enclosed by copper before it can change layer. The via goes as
 * close to the pad as the rules allow, outward along the pad first, then to either side of the pad,
 * then behind it under the part, then diagonally; the stub grows only when no closer site fits.
 * Pins of the densest components choose first. The escapes are ordinary unfixed copper, so the
 * autorouter rips them up as it needs to, and unused ones are removed with the other dangling vias.
 *
 * <p>Deterministic: the order and the candidate sites depend only on the board.
 */
final class PlannedFanout {

  /** Close reach: the via may sit up to this many via diameters beyond the pad edge. */
  private static final int CLOSE_REACH_VIA_DIAMETERS = 2;

  private PlannedFanout() {}

  record Summary(int candidates, int escaped, int noRoom, int noVia, Map<String, Integer> byDir) {}

  static Summary run(RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    long started = System.currentTimeMillis();
    Map<Integer, Integer> smdPinsPerComponent = new HashMap<>();
    Map<Integer, double[]> centroidSums = new HashMap<>();
    List<Pin> pins = new ArrayList<>();
    for (Pin pin : board.getSmdPins()) {
      int component = pin.getComponentId();
      smdPinsPerComponent.merge(component, 1, Integer::sum);
      double[] sum = centroidSums.computeIfAbsent(component, c -> new double[3]);
      sum[0] += pin.getCenter().toFloat().x;
      sum[1] += pin.getCenter().toFloat().y;
      sum[2]++;
      if (pin.netCount() == 1 && !pin.hasIgnoredNets() && pin.firstLayer() == pin.lastLayer()) {
        pins.add(pin);
      }
    }
    pins.sort(
        Comparator.<Pin>comparingInt(pin -> -smdPinsPerComponent.get(pin.getComponentId()))
            .thenComparingInt(Pin::getComponentId)
            .thenComparingInt(pin -> pin.pinIndex));
    double unitsPerMm = board.communication.getResolution(Unit.UM) * 1000.0;
    double maxEscape =
        unitsPerMm
            * (settings.fanout != null && settings.fanout.maxEscapeLengthMm != null
                ? settings.fanout.maxEscapeLengthMm
                : 3.0);

    int candidates = 0;
    int escaped = 0;
    int noRoom = 0;
    int noVia = 0;
    Map<String, Integer> byDir = new HashMap<>();
    for (Pin pin : pins) {
      if (thread != null && thread.isStopRequested()) {
        break;
      }
      int net = pin.getNetNumber(0);
      int layer = pin.firstLayer();
      if (!needsEscape(pin, net, layer)) {
        continue;
      }
      candidates++;
      AutorouteControl ctrl = new AutorouteControl(board, net, settings);
      ViaInfo via = escapeVia(ctrl.viaRule, layer);
      if (via == null) {
        noVia++;
        continue;
      }
      double[] sum = centroidSums.get(pin.getComponentId());
      String placed =
          place(board, pin, net, layer, via, ctrl, sum[0] / sum[2], sum[1] / sum[2], maxEscape);
      if (placed == null) {
        noRoom++;
      } else {
        escaped++;
        byDir.merge(placed, 1, Integer::sum);
      }
    }
    FRLogger.info(
        "Planned fanout: "
            + escaped
            + " of "
            + candidates
            + " SMD pins escaped to a via "
            + byDir
            + ", "
            + noRoom
            + " without room, "
            + noVia
            + " without a usable via, in "
            + (System.currentTimeMillis() - started) / 1000.0
            + " s; the rest go to the fanout passes.");
    return new Summary(candidates, escaped, noRoom, noVia, byDir);
  }

  /**
   * A single-layer pin whose copper is all on its layer and that still has a connection to make.
   */
  static boolean needsEscape(Pin pin, int net, int layer) {
    for (Item item : pin.getConnectedSet(net)) {
      if (item.firstLayer() != layer || item.lastLayer() != layer) {
        return false;
      }
    }
    return !pin.getUnconnectedSet(net).isEmpty();
  }

  /** The first via of the rule that reaches the pin's layer and at least one other layer. */
  private static ViaInfo escapeVia(ViaRule rule, int layer) {
    if (rule == null) {
      return null;
    }
    for (int i = 0; i < rule.viaCount(); i++) {
      ViaInfo info = rule.getVia(i);
      Padstack padstack = info.getPadstack();
      if (padstack.fromLayer() <= layer
          && layer <= padstack.toLayer()
          && padstack.fromLayer() != padstack.toLayer()) {
        return info;
      }
    }
    return null;
  }

  /**
   * Tries the candidate sites in order and keeps the first via plus stub that inserts without a
   * clearance violation. Returns the direction used, or null.
   */
  private static String place(
      RoutingBoard board,
      Pin pin,
      int net,
      int layer,
      ViaInfo via,
      AutorouteControl ctrl,
      double centroidX,
      double centroidY,
      double maxEscape) {
    TileShape padShape = pin.getTileShapeOnLayer(layer);
    if (padShape == null) {
      return null;
    }
    IntBox pad = padShape.boundingBox();
    Point center = pin.getCenter();
    double cx = center.toFloat().x;
    double cy = center.toFloat().y;
    double viaRadius =
        via.getPadstack().getShape(layer) != null
            ? via.getPadstack().getShape(layer).boundingBox().width() / 2.0
            : 0;
    double step = Math.max(1.0, viaRadius / 4.0);
    boolean wide = pad.width() >= pad.height();
    // Long axis of the pad, pointing away from the part's centre.
    int outX = wide ? (cx >= centroidX ? 1 : -1) : 0;
    int outY = wide ? 0 : (cy >= centroidY ? 1 : -1);
    int sideX = wide ? 0 : (cx >= centroidX ? 1 : -1);
    int sideY = wide ? (cy >= centroidY ? 1 : -1) : 0;
    int[][] dirs = {
      {outX, outY},
      {sideX, sideY},
      {-sideX, -sideY},
      {-outX, -outY},
      {outX + sideX, outY + sideY},
      {outX - sideX, outY - sideY},
      {-outX + sideX, -outY + sideY},
      {-outX - sideX, -outY - sideY}
    };
    String[] names = {
      "outward", "side", "side", "behind", "diagonal", "diagonal", "diagonal", "diagonal"
    };
    int[] nets = {net};
    int halfWidth = ctrl.traceHalfWidth[layer];
    int traceClass = ctrl.traceClearanceClassIndex;
    double closeReach = 2 * viaRadius * CLOSE_REACH_VIA_DIAMETERS;
    for (int phase = 0; phase < 2; phase++) {
      for (int d = 0; d < dirs.length; d++) {
        int dx = dirs[d][0];
        int dy = dirs[d][1];
        double length = Math.sqrt(dx * dx + dy * dy);
        // Distance from the pin centre at which the via just clears the pad edge.
        double edge =
            Math.max(dx != 0 ? pad.width() / 2.0 : 0, dy != 0 ? pad.height() / 2.0 : 0) * length
                + viaRadius;
        double from = phase == 0 ? edge : edge + closeReach + step;
        double to = phase == 0 ? edge + closeReach : Math.max(maxEscape, edge + closeReach);
        for (double r = from; r <= to; r += step) {
          IntPoint site =
              new IntPoint(
                  (int) Math.round(cx + dx / length * r), (int) Math.round(cy + dy / length * r));
          if (tryInsert(board, pin, net, layer, via, site, nets, halfWidth, traceClass)) {
            return names[d];
          }
        }
      }
    }
    return null;
  }

  /**
   * Inserts a stub from the pin centre to {@code site} and the via there when both pass the
   * clearance check and the strict DRC; otherwise leaves the board as it was. Also used by the BGA
   * planner.
   */
  static boolean tryInsert(
      RoutingBoard board,
      Pin pin,
      int net,
      int layer,
      ViaInfo via,
      IntPoint site,
      int[] nets,
      int halfWidth,
      int traceClass) {
    Padstack padstack = via.getPadstack();
    Vector offset = site.differenceBy(Point.ZERO);
    // Without via-in-pad the via keeps clearance from its own pad too.
    Set<Pin> contact = via.attachSmdAllowed() ? Set.of(pin) : Set.of();
    for (int l = padstack.fromLayer(); l <= padstack.toLayer(); l++) {
      if (padstack.getShape(l) == null) {
        continue;
      }
      TileShape shape = padstack.getShape(l).translateBy(offset).boundingOctagon();
      if (!board.checkTraceShape(shape, l, nets, via.getClearanceClassIndex(), contact)) {
        return false;
      }
    }
    Polyline stub = new Polyline(new Point[] {pin.getCenter(), site});
    if (!board.checkPolylineTrace(stub, layer, halfWidth, nets, traceClass)) {
      return false;
    }
    int maxIdBefore = board.communication.idGenerator.maxGeneratedId();
    PolylineTrace trace =
        board.insertTraceWithoutCleaning(
            stub, layer, halfWidth, nets, traceClass, FixedState.UNFIXED);
    Via inserted =
        board.insertVia(
            padstack,
            site,
            nets,
            via.getClearanceClassIndex(),
            FixedState.UNFIXED,
            via.attachSmdAllowed());
    if (trace == null || inserted == null) {
      List<Item> added = new ArrayList<>();
      if (trace != null) {
        added.add(trace);
      }
      if (inserted != null) {
        added.add(inserted);
      }
      board.removeItems(added);
      return false;
    }
    return BatchAutorouter.enforceStrictDrc(board, net, maxIdBefore) == null;
  }
}
