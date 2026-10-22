package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.library.Padstack;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.ViaInfo;
import app.freerouting.rules.ViaRule;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Escape planning for pin grids (BGAs). A component whose SMD pads sit on a square lattice gets a
 * via site reserved for every ball in one of the four diagonal gaps next to it, assigned by a
 * matching so that no two balls share a gap and inner balls choose first. Balls from ring 1 inward,
 * and balls on plane nets in any ring, get the copper up front: a stub from the pad to the gap and
 * the smallest via of the net's rule that fits there. Outer-ring balls keep their reservation and
 * route on the surface. The escapes are ordinary unfixed copper; the fanout passes skip the escaped
 * pins and {@code protect_escapes} keeps the others from ripping them.
 *
 * <p>Deterministic: the lattice, the matching order and the sites depend only on the board.
 */
final class BgaEscapePlanner {

  /** A pad may sit this far off its lattice point, as a fraction of the pitch. */
  private static final double FIT_TOLERANCE = 0.15;

  /** Smallest lattice, in pads per side, that counts as a grid. */
  private static final int MIN_SIDE = 4;

  /** Share of the lattice positions that must hold a pad. */
  private static final double MIN_OCCUPANCY = 0.5;

  /** Share of the component's pads that must sit on the lattice. */
  private static final double MIN_FIT_SHARE = 0.9;

  private BgaEscapePlanner() {}

  /** One ball of a grid: its pad, lattice cell and ring (0 = outer). */
  record Ball(Pin pin, int col, int row, int ring) {}

  /** A detected pin grid. The origin is the centre of the cell (0, 0) in board units. */
  record Grid(
      int componentId,
      String name,
      int layer,
      double pitch,
      double originX,
      double originY,
      int cols,
      int rows,
      List<Ball> balls) {

    double centreX() {
      return originX + (cols - 1) * pitch / 2;
    }

    double centreY() {
      return originY + (rows - 1) * pitch / 2;
    }

    /** Centre of the diagonal gap below and to the left of cell (col, row). */
    double gapX(int gapCol) {
      return originX + (gapCol + 0.5) * pitch;
    }

    double gapY(int gapRow) {
      return originY + (gapRow + 0.5) * pitch;
    }
  }

  record Summary(
      int grids,
      int balls,
      int needVia,
      int placed,
      int noRoom,
      int noVia,
      Map<Integer, Integer> byRing) {}

  static Summary run(RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    long started = System.currentTimeMillis();
    List<Grid> grids = detectGrids(board);
    int balls = 0;
    int needVia = 0;
    int placed = 0;
    int noRoom = 0;
    int noVia = 0;
    Map<Integer, Integer> byRing = new TreeMap<>();
    for (Grid grid : grids) {
      if (thread != null && thread.isStopRequested()) {
        break;
      }
      int[] counts = plan(board, settings, grid, byRing);
      balls += grid.balls().size();
      needVia += counts[0];
      placed += counts[1];
      noRoom += counts[2];
      noVia += counts[3];
    }
    FRLogger.info(
        "BGA escapes: "
            + grids.size()
            + " pin grid(s), "
            + balls
            + " balls, "
            + needVia
            + " need a via, "
            + placed
            + " placed by ring "
            + byRing
            + ", "
            + noRoom
            + " without room, "
            + noVia
            + " without a usable via, in "
            + (System.currentTimeMillis() - started) / 1000.0
            + " s; the rest go to the fanout passes.");
    return new Summary(grids.size(), balls, needVia, placed, noRoom, noVia, byRing);
  }

  /** Finds every component whose single-layer pads form a square lattice. */
  static List<Grid> detectGrids(RoutingBoard board) {
    Map<Integer, List<Pin>> byComponent = new TreeMap<>();
    for (Pin pin : board.getSmdPins()) {
      if (pin.firstLayer() == pin.lastLayer()) {
        byComponent.computeIfAbsent(pin.getComponentId(), c -> new ArrayList<>()).add(pin);
      }
    }
    List<Grid> grids = new ArrayList<>();
    for (Map.Entry<Integer, List<Pin>> entry : byComponent.entrySet()) {
      List<Pin> pins = entry.getValue();
      pins.sort(Comparator.comparingInt(pin -> pin.pinIndex));
      Grid grid = fit(board, entry.getKey(), pins);
      if (grid != null) {
        grids.add(grid);
      }
    }
    return grids;
  }

  private static Grid fit(RoutingBoard board, int componentId, List<Pin> componentPins) {
    // The layer with most pads is the grid's layer.
    Map<Integer, Integer> perLayer = new TreeMap<>();
    for (Pin pin : componentPins) {
      perLayer.merge(pin.firstLayer(), 1, Integer::sum);
    }
    int layer = -1;
    int most = 0;
    for (Map.Entry<Integer, Integer> entry : perLayer.entrySet()) {
      if (entry.getValue() > most) {
        most = entry.getValue();
        layer = entry.getKey();
      }
    }
    List<Pin> pins = new ArrayList<>();
    for (Pin pin : componentPins) {
      if (pin.firstLayer() == layer) {
        pins.add(pin);
      }
    }
    int n = pins.size();
    if (n < MIN_SIDE * MIN_SIDE * MIN_OCCUPANCY) {
      return null;
    }
    double[] xs = new double[n];
    double[] ys = new double[n];
    for (int i = 0; i < n; i++) {
      FloatPoint centre = pins.get(i).getCenter().toFloat();
      xs[i] = centre.x;
      ys[i] = centre.y;
    }
    // The pitch is the median distance to the nearest other pad.
    double[] nearest = new double[n];
    for (int i = 0; i < n; i++) {
      double best = Double.MAX_VALUE;
      for (int j = 0; j < n; j++) {
        if (i != j) {
          double dx = xs[i] - xs[j];
          double dy = ys[i] - ys[j];
          best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
        }
      }
      nearest[i] = best;
    }
    double[] sorted = nearest.clone();
    Arrays.sort(sorted);
    double pitch = sorted[n / 2];
    if (pitch <= 0) {
      return null;
    }
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    for (int i = 0; i < n; i++) {
      minX = Math.min(minX, xs[i]);
      minY = Math.min(minY, ys[i]);
    }
    int[] cols = new int[n];
    int[] rows = new int[n];
    boolean[] fits = new boolean[n];
    int fitted = 0;
    int maxCol = 0;
    int maxRow = 0;
    for (int i = 0; i < n; i++) {
      double fx = (xs[i] - minX) / pitch;
      double fy = (ys[i] - minY) / pitch;
      int col = (int) Math.round(fx);
      int row = (int) Math.round(fy);
      if (Math.abs(fx - col) <= FIT_TOLERANCE && Math.abs(fy - row) <= FIT_TOLERANCE) {
        fits[i] = true;
        cols[i] = col;
        rows[i] = row;
        maxCol = Math.max(maxCol, col);
        maxRow = Math.max(maxRow, row);
        fitted++;
      }
    }
    int colCount = maxCol + 1;
    int rowCount = maxRow + 1;
    if (fitted < MIN_FIT_SHARE * n
        || colCount < MIN_SIDE
        || rowCount < MIN_SIDE
        || fitted < MIN_OCCUPANCY * colCount * rowCount) {
      return null;
    }
    Set<Long> occupied = new HashSet<>();
    List<Ball> balls = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (!fits[i] || !occupied.add(cellKey(cols[i], rows[i]))) {
        continue;
      }
      int ring =
          Math.min(
              Math.min(cols[i], colCount - 1 - cols[i]), Math.min(rows[i], rowCount - 1 - rows[i]));
      balls.add(new Ball(pins.get(i), cols[i], rows[i], ring));
    }
    app.freerouting.board.model.structure.Component component = board.components.get(componentId);
    String name = component != null ? component.name : "component " + componentId;
    return new Grid(componentId, name, layer, pitch, minX, minY, colCount, rowCount, balls);
  }

  private static long cellKey(int col, int row) {
    // Gap and cell indices start at -1, so shift them into the positive range.
    return ((long) (col + 2) << 32) | (row + 2);
  }

  /**
   * Reserves a gap for every ball with a net and inserts the dog-bones. Returns {need, placed,
   * noRoom, noVia}.
   */
  private static int[] plan(
      RoutingBoard board, RouterSettings settings, Grid grid, Map<Integer, Integer> byRing) {
    List<Ball> candidates = new ArrayList<>();
    for (Ball ball : grid.balls()) {
      Pin pin = ball.pin();
      if (pin.netCount() == 1 && !pin.hasIgnoredNets()) {
        candidates.add(ball);
      }
    }
    // Inner balls choose first: they have no border gaps to fall back on.
    candidates.sort(
        Comparator.<Ball>comparingInt(ball -> -ball.ring())
            .thenComparingInt(ball -> ball.pin().pinIndex));
    List<long[]> gapOptions = new ArrayList<>();
    for (Ball ball : candidates) {
      gapOptions.add(gapsAwayFromCentreFirst(grid, ball));
    }
    Map<Long, Integer> gapOwner = new HashMap<>();
    long[] assigned = new long[candidates.size()];
    Arrays.fill(assigned, -1);
    for (int i = 0; i < candidates.size(); i++) {
      augment(i, gapOptions, gapOwner, assigned, new HashSet<>());
    }

    int need = 0;
    int placed = 0;
    int noRoom = 0;
    int noVia = 0;
    double unitsPerMm = board.communication.getResolution(Unit.UM) * 1000.0;
    Map<Integer, Integer> gridByRing = new TreeMap<>();
    Map<Integer, Integer> gridByLayer = new TreeMap<>();
    int[] targetLayer =
        candidates.isEmpty()
            ? new int[0]
            : assignLayers(
                board,
                grid,
                candidates,
                new AutorouteControl(board, candidates.get(0).pin().getNetNumber(0), settings));
    for (int i = 0; i < candidates.size(); i++) {
      Ball ball = candidates.get(i);
      Pin pin = ball.pin();
      int net = pin.getNetNumber(0);
      if (!PlannedFanout.needsEscape(pin, net, grid.layer())) {
        continue;
      }
      Set<Integer> planeLayers = planeLayers(board, net);
      if (ball.ring() == 0 && planeLayers.isEmpty()) {
        continue; // keeps its reservation, routes on the surface
      }
      need++;
      if (assigned[i] < 0) {
        noRoom++;
        continue;
      }
      AutorouteControl ctrl = new AutorouteControl(board, net, settings);
      List<ViaInfo> vias = viasSmallestFirst(ctrl.viaRule, grid.layer());
      if (vias.isEmpty()) {
        noVia++;
        continue;
      }
      preferVia(vias, grid.layer(), planeLayers.isEmpty() ? targetLayer[i] : -1, planeLayers);
      // The matched gap first; if copper already there refuses it, any free gap of the ball.
      List<Long> gaps = new ArrayList<>();
      gaps.add(assigned[i]);
      for (long gap : gapOptions.get(i)) {
        if (gap != assigned[i] && !gapOwner.containsKey(gap)) {
          gaps.add(gap);
        }
      }
      boolean inserted = false;
      for (long gap : gaps) {
        int gapCol = (int) (gap >> 32) - 2;
        int gapRow = (int) (gap & 0xffffffffL) - 2;
        IntPoint site =
            new IntPoint((int) Math.round(grid.gapX(gapCol)), (int) Math.round(grid.gapY(gapRow)));
        for (ViaInfo via : vias) {
          if (PlannedFanout.tryInsert(
              board,
              pin,
              net,
              grid.layer(),
              via,
              site,
              new int[] {net},
              ctrl.traceHalfWidth[grid.layer()],
              ctrl.traceClearanceClassIndex)) {
            inserted = true;
            int farLayer =
                via.getPadstack().fromLayer() == grid.layer()
                    ? via.getPadstack().toLayer()
                    : via.getPadstack().fromLayer();
            gridByLayer.merge(farLayer, 1, Integer::sum);
            break;
          }
        }
        if (inserted) {
          gapOwner.put(gap, i);
          break;
        }
      }
      if (inserted) {
        placed++;
        gridByRing.merge(ball.ring(), 1, Integer::sum);
        byRing.merge(ball.ring(), 1, Integer::sum);
      } else {
        noRoom++;
      }
    }
    FRLogger.info(
        "BGA plan "
            + grid.name()
            + ": "
            + grid.cols()
            + " x "
            + grid.rows()
            + " at "
            + String.format("%.3f", grid.pitch() / unitsPerMm)
            + " mm, "
            + grid.balls().size()
            + " balls, "
            + need
            + " need a via, "
            + placed
            + " placed by ring "
            + gridByRing
            + " and by via end layer "
            + gridByLayer
            + ", "
            + noRoom
            + " without room, "
            + noVia
            + " without a usable via.");
    return new int[] {need, placed, noRoom, noVia};
  }

  /**
   * The inner layer each ball should escape on when the rule offers blind or buried vias, or -1 for
   * no preference (the smallest via). Rings are filled from the outside in, ring 1 on the layer
   * nearest the pads, and a ring moves one layer deeper when the layer's channels are full: a
   * channel between two dog-bone vias carries {@code floor((pitch - via - clearance) / (width +
   * clearance))} traces, and the part has one channel per gap column along its perimeter.
   */
  static int[] assignLayers(
      RoutingBoard board, Grid grid, List<Ball> candidates, AutorouteControl ctrl) {
    int[] target = new int[candidates.size()];
    Arrays.fill(target, -1);
    int padLayer = grid.layer();
    int layerCount = board.getLayerCount();
    List<ViaInfo> vias = viasSmallestFirst(ctrl.viaRule, padLayer);
    boolean partialSpan = false;
    for (ViaInfo via : vias) {
      if (via.getPadstack().toLayer() - via.getPadstack().fromLayer() < layerCount - 1) {
        partialSpan = true;
      }
    }
    if (!partialSpan) {
      return target;
    }
    int direction = padLayer == 0 ? 1 : -1;
    int channels = 2 * (grid.cols() - 1) + 2 * (grid.rows() - 1);
    List<Integer> layers = new ArrayList<>();
    List<Integer> capacity = new ArrayList<>();
    for (int layer = padLayer + direction; layer >= 0 && layer < layerCount; layer += direction) {
      if (!ctrl.layerActive[layer]) {
        continue;
      }
      ViaInfo via = shortestViaTo(vias, padLayer, layer);
      if (via == null) {
        continue;
      }
      int clearance =
          board.rules.clearanceMatrix.getValue(
              ctrl.traceClearanceClassIndex, via.getClearanceClassIndex(), layer, false);
      double width = 2.0 * ctrl.traceHalfWidth[layer];
      int perChannel =
          (int) Math.floor((grid.pitch() - diameter(via, layer) - clearance) / (width + clearance));
      if (perChannel <= 0) {
        continue;
      }
      layers.add(layer);
      capacity.add(channels * perChannel);
    }
    if (layers.isEmpty()) {
      return target;
    }
    Integer[] order = new Integer[candidates.size()];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    Arrays.sort(
        order,
        Comparator.<Integer>comparingInt(i -> candidates.get(i).ring())
            .thenComparingInt(i -> candidates.get(i).pin().pinIndex));
    int layerIndex = 0;
    int left = capacity.get(0);
    for (int i : order) {
      if (candidates.get(i).ring() == 0) {
        continue;
      }
      while (left == 0 && layerIndex < layers.size() - 1) {
        layerIndex++;
        left = capacity.get(layerIndex);
      }
      if (left == 0) {
        break;
      }
      target[i] = layers.get(layerIndex);
      left--;
    }
    return target;
  }

  /** The via of the list covering both layers with the shortest span, or null. */
  static ViaInfo shortestViaTo(List<ViaInfo> vias, int padLayer, int layer) {
    ViaInfo best = null;
    int bestSpan = Integer.MAX_VALUE;
    for (ViaInfo via : vias) {
      Padstack padstack = via.getPadstack();
      if (padstack.fromLayer() <= Math.min(padLayer, layer)
          && padstack.toLayer() >= Math.max(padLayer, layer)) {
        int span = padstack.toLayer() - padstack.fromLayer();
        if (span < bestSpan) {
          bestSpan = span;
          best = via;
        }
      }
    }
    return best;
  }

  /**
   * Moves the via to try first to the front: the shortest via to the target layer, or for a plane
   * net the shortest via that reaches one of its plane layers.
   */
  static void preferVia(
      List<ViaInfo> vias, int padLayer, int targetLayer, Set<Integer> planeLayers) {
    ViaInfo preferred = null;
    if (!planeLayers.isEmpty()) {
      int bestSpan = Integer.MAX_VALUE;
      for (int layer : planeLayers) {
        ViaInfo via = shortestViaTo(vias, padLayer, layer);
        if (via != null) {
          int span = via.getPadstack().toLayer() - via.getPadstack().fromLayer();
          if (span < bestSpan) {
            bestSpan = span;
            preferred = via;
          }
        }
      }
    } else if (targetLayer >= 0) {
      preferred = shortestViaTo(vias, padLayer, targetLayer);
    }
    if (preferred != null && vias.remove(preferred)) {
      vias.add(0, preferred);
    }
  }

  /** The four diagonal gaps around a ball, the one pointing away from the part's centre first. */
  private static long[] gapsAwayFromCentreFirst(Grid grid, Ball ball) {
    double ballX = grid.originX() + ball.col() * grid.pitch();
    double ballY = grid.originY() + ball.row() * grid.pitch();
    double outX = ballX - grid.centreX();
    double outY = ballY - grid.centreY();
    int[][] gaps = {
      {ball.col(), ball.row()},
      {ball.col() - 1, ball.row()},
      {ball.col(), ball.row() - 1},
      {ball.col() - 1, ball.row() - 1}
    };
    double[] score = new double[4];
    for (int i = 0; i < 4; i++) {
      double dx = grid.gapX(gaps[i][0]) - ballX;
      double dy = grid.gapY(gaps[i][1]) - ballY;
      score[i] = -(dx * outX + dy * outY); // most outward first
    }
    Integer[] order = {0, 1, 2, 3};
    Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> score[i]).thenComparingInt(i -> i));
    long[] result = new long[4];
    for (int i = 0; i < 4; i++) {
      result[i] = cellKey(gaps[order[i]][0], gaps[order[i]][1]);
    }
    return result;
  }

  /** Kuhn's augmenting path: gives ball {@code i} a gap, moving earlier balls if that frees one. */
  private static boolean augment(
      int i,
      List<long[]> options,
      Map<Long, Integer> gapOwner,
      long[] assigned,
      Set<Long> visited) {
    for (long gap : options.get(i)) {
      if (!visited.add(gap)) {
        continue;
      }
      Integer owner = gapOwner.get(gap);
      if (owner == null || augment(owner, options, gapOwner, assigned, visited)) {
        gapOwner.put(gap, i);
        assigned[i] = gap;
        return true;
      }
    }
    return false;
  }

  /** Vias of the rule that reach {@code layer} and another layer, smallest diameter first. */
  static List<ViaInfo> viasSmallestFirst(ViaRule rule, int layer) {
    List<ViaInfo> result = new ArrayList<>();
    if (rule == null) {
      return result;
    }
    for (int i = 0; i < rule.viaCount(); i++) {
      ViaInfo info = rule.getVia(i);
      Padstack padstack = info.getPadstack();
      if (padstack.fromLayer() <= layer
          && layer <= padstack.toLayer()
          && padstack.fromLayer() != padstack.toLayer()) {
        result.add(info);
      }
    }
    result.sort(
        Comparator.<ViaInfo>comparingDouble(info -> diameter(info, layer))
            .thenComparingInt(info -> info.getPadstack().toLayer() - info.getPadstack().fromLayer())
            .thenComparing(ViaInfo::getName));
    return result;
  }

  private static double diameter(ViaInfo info, int layer) {
    return info.getPadstack().getShape(layer) != null
        ? info.getPadstack().getShape(layer).boundingBox().width()
        : 0;
  }

  /** The layers on which the net has a copper area (plane) to connect to; empty for a signal. */
  static Set<Integer> planeLayers(RoutingBoard board, int net) {
    Set<Integer> layers = new java.util.TreeSet<>();
    for (Item item : board.getConnectableItems(net)) {
      if (item instanceof ConductionArea area) {
        layers.add(area.getLayer());
      }
    }
    return layers;
  }
}
