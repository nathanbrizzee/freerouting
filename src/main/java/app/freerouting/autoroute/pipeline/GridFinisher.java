package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ComponentOutline;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.ViaObstacleArea;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.searchtree.SearchTreeObject;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.library.Padstack;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.ViaInfo;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Closes open connections left after the maze passes with an A* search on a fine grid, one
 * connection at a time ({@code autorouter.grid_finisher}, roadmap section 5.3).
 *
 * <p>For each airline (a gap between two connected groups of a net) it lays a grid over a window
 * around the gap, about half a trace pitch per cell, marks the cells where the net's trace would
 * come closer than the clearance to another net's copper, and the via sites where a via would, and
 * searches from the copper of one group to the copper of the other, changing layer by any via of
 * the net's rule. The path goes in as plain traces and vias inside a board snapshot. It is kept
 * only if the two groups are then connected and no new item has a clearance violation; otherwise
 * the snapshot is undone. The grid is conservative (a cell is free only if a trace through its
 * whole width is), so it may miss a gap the exact geometry would allow, but it never keeps a
 * violation.
 *
 * <p>Deterministic: the airline order, the grid and the search depend only on the board.
 */
final class GridFinisher {

  /** Cells per layer in a window; a larger window gets coarser cells. */
  static final int MAX_CELLS_PER_LAYER = 250_000;

  /** States one connection's search may expand. */
  static final int MAX_EXPANSIONS = 2_000_000;

  /** Default for {@code autorouter.grid_finisher_max_steps}: states the whole stage may expand. */
  static final long DEFAULT_MAX_STEPS = 50_000_000L;

  /** Window margin around the gap, as a share of its length, and at least this many mm. */
  private static final double MARGIN_SHARE = 0.3;

  private static final double MIN_MARGIN_MM = 2.0;
  private static final double MAX_MARGIN_MM = 20.0;

  /** A via costs as much as this many mm of trace. */
  private static final double VIA_COST_MM = 3.0;

  private static final int[][] DIRECTIONS = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private GridFinisher() {}

  record Summary(int open, int tried, int closed, long expansions) {}

  /**
   * Tries every open connection once, until the stage's step budget ({@code
   * autorouter.grid_finisher_max_steps}) is spent or a stop is requested. A board that cannot be
   * finished keeps its open connections; nothing is retried. Returns what it did.
   */
  static Summary run(RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    long budget =
        settings.autorouter != null && settings.autorouter.gridFinisherMaxSteps != null
            ? settings.autorouter.gridFinisherMaxSteps
            : DEFAULT_MAX_STEPS;
    final long started = System.currentTimeMillis();
    AirLine[] airlines = new DesignRulesChecker(board, null).getAllAirlines();
    List<AirLine> sorted = new ArrayList<>();
    for (AirLine airline : airlines) {
      if (airline != null && airline.net != null) {
        sorted.add(airline);
      }
    }
    sorted.sort(
        (a, b) -> {
          int byNet = Integer.compare(a.net.netNumber, b.net.netNumber);
          if (byNet != 0) {
            return byNet;
          }
          int byX = Double.compare(a.fromCorner.x, b.fromCorner.x);
          return byX != 0 ? byX : Double.compare(a.fromCorner.y, b.fromCorner.y);
        });
    int tried = 0;
    int closed = 0;
    long expansions = 0;
    for (AirLine airline : sorted) {
      if ((thread != null && thread.isStopRequested()) || expansions >= budget) {
        break;
      }
      int net = airline.net.netNumber;
      if (airline.fromItem.getConnectedSet(net).contains(airline.toItem)) {
        continue; // closed by an earlier connection of the same net
      }
      tried++;
      Attempt attempt =
          new Attempt(
              board, settings, airline, thread, Math.min(MAX_EXPANSIONS, budget - expansions));
      if (attempt.route()) {
        closed++;
      }
      FRLogger.debug(
          "Grid finisher: net "
              + airline.net.name
              + " at "
              + airline.fromCorner
              + ": "
              + attempt.outcome
              + " after "
              + attempt.expansions
              + " states.");
      expansions += attempt.expansions;
    }
    if (closed > 0) {
      board.clearTransientAutorouteState();
    }
    FRLogger.info(
        "Grid finisher: "
            + sorted.size()
            + " open connection(s), "
            + tried
            + " tried, "
            + closed
            + " closed, "
            + expansions
            + " grid states expanded of "
            + budget
            + ", in "
            + (System.currentTimeMillis() - started) / 1000.0
            + " s.");
    return new Summary(sorted.size(), tried, closed, expansions);
  }

  /** One connection: its grid, search and insertion. */
  private static final class Attempt {
    private final RoutingBoard board;
    private final int net;

    /** The two ends, ordered by position so the search does not depend on the airline's way. */
    private final Item fromItem;

    private final Item toItem;
    private final FloatPoint fromCorner;
    private final FloatPoint toCorner;
    private final AutorouteControl ctrl;
    private final List<Integer> layers = new ArrayList<>();
    private final List<ViaInfo> vias = new ArrayList<>();
    private int originX;
    private int originY;
    private double cell;
    private int cols;
    private int rows;

    /** Per routable layer: cells a trace may not cross. */
    private boolean[][] blocked;

    /** Per routable layer: cells of the start and goal groups' copper. */
    private boolean[][] start;

    private boolean[][] goal;

    /** Per routable layer: the copper item each start or goal cell lies in. */
    private Item[][] startOwner;

    private Item[][] goalOwner;

    /** Per via of the rule: sites where it may go, or null before first use. */
    private boolean[][] viaFree;

    private final StoppableThread thread;

    /** Search state: cost and parent per (layer, cell, arrived by via), and the open list. */
    private double[] cost;

    private int[] parent;
    private boolean[] closed;
    private PriorityQueue<double[]> open;
    private int goalCol0;
    private int goalCol1;
    private int goalRow0;
    private int goalRow1;
    private final long maxExpansions;
    long expansions;
    String outcome = "not tried";

    Attempt(
        RoutingBoard board,
        RouterSettings settings,
        AirLine airline,
        StoppableThread thread,
        long maxExpansions) {
      this.board = board;
      this.thread = thread;
      this.maxExpansions = maxExpansions;
      this.net = airline.net.netNumber;
      FloatPoint a = airline.fromCorner;
      FloatPoint b = airline.toCorner;
      boolean swap = a.x > b.x || (a.x == b.x && a.y > b.y);
      this.fromItem = swap ? airline.toItem : airline.fromItem;
      this.toItem = swap ? airline.fromItem : airline.toItem;
      this.fromCorner = swap ? b : a;
      this.toCorner = swap ? a : b;
      this.ctrl = new AutorouteControl(board, net, settings);
      for (int layer = 0; layer < ctrl.layerCount; layer++) {
        if (ctrl.layerActive[layer]) {
          layers.add(layer);
        }
      }
      if (ctrl.viaRule != null && ctrl.viasAllowed) {
        for (int i = 0; i < ctrl.viaRule.viaCount(); i++) {
          vias.add(ctrl.viaRule.getVia(i));
        }
      }
    }

    boolean route() {
      if (layers.isEmpty() || !buildWindow()) {
        return false;
      }
      paintObstacles();
      paintEnds();
      int[] path = search();
      if (path == null) {
        outcome = "no path";
        return false;
      }
      return insert(path);
    }

    private double unitsPerMm() {
      return board.communication.getResolution(app.freerouting.board.model.structure.Unit.MM);
    }

    private boolean buildWindow() {
      FloatPoint a = fromCorner;
      FloatPoint b = toCorner;
      double length = a.distance(b);
      double mm = unitsPerMm();
      double margin =
          Math.min(MAX_MARGIN_MM * mm, Math.max(MIN_MARGIN_MM * mm, MARGIN_SHARE * length));
      IntBox window =
          new IntBox(
              (int) Math.floor(Math.min(a.x, b.x) - margin),
              (int) Math.floor(Math.min(a.y, b.y) - margin),
              (int) Math.ceil(Math.max(a.x, b.x) + margin),
              (int) Math.ceil(Math.max(a.y, b.y) + margin));
      IntBox boardBox = board.getBoundingBox();
      if (boardBox != null) {
        window = window.intersection(boardBox);
      }
      if (window.isEmpty()) {
        return false;
      }
      // Half a trace pitch, so a channel one trace wide shows as at least one free cell.
      int smallestPitch = Integer.MAX_VALUE;
      for (int layer : layers) {
        int pitch =
            2 * ctrl.traceHalfWidth[layer]
                + board.clearanceValue(
                    ctrl.traceClearanceClassIndex, ctrl.traceClearanceClassIndex, layer);
        smallestPitch = Math.min(smallestPitch, pitch);
      }
      double width = window.ur.x - window.ll.x;
      double height = window.ur.y - window.ll.y;
      cell = Math.max(1.0, smallestPitch / 2.0);
      cell = Math.max(cell, Math.sqrt(width * height / MAX_CELLS_PER_LAYER));
      cols = (int) Math.ceil(width / cell) + 1;
      rows = (int) Math.ceil(height / cell) + 1;
      originX = window.ll.x;
      originY = window.ll.y;
      int n = layers.size();
      blocked = new boolean[n][cols * rows];
      start = new boolean[n][cols * rows];
      goal = new boolean[n][cols * rows];
      startOwner = new Item[n][cols * rows];
      goalOwner = new Item[n][cols * rows];
      viaFree = new boolean[vias.size()][];
      return true;
    }

    private double cx(int col) {
      return originX + col * cell;
    }

    private double cy(int row) {
      return originY + row * cell;
    }

    private IntBox windowBox() {
      return new IntBox(
          originX, originY, (int) Math.ceil(cx(cols - 1)), (int) Math.ceil(cy(rows - 1)));
    }

    /** Whether the item blocks this net's traces, or with {@code forVia} its vias. */
    private boolean isObstacle(Item item, boolean forVia) {
      if (item instanceof ComponentOutline || item.containsNet(net)) {
        return false;
      }
      if (item instanceof ViaObstacleArea) {
        return forVia;
      }
      if (item instanceof ConductionArea area) {
        return forVia ? !area.containsNet(net) : area.isTraceObstacle(net);
      }
      return forVia || item.isTraceObstacle(net);
    }

    private void paintObstacles() {
      // A trace between two free cell centres stays clear of anything past half a diagonal.
      double halfDiagonal = cell * Math.sqrt(0.5);
      for (int li = 0; li < layers.size(); li++) {
        int layer = layers.get(li);
        int halfWidth = ctrl.traceHalfWidth[layer];
        for (SearchTreeObject object : board.overlappingObjects(grownWindow(), layer)) {
          if (!(object instanceof Item item) || !isObstacle(item, false)) {
            continue;
          }
          double need =
              board.clearanceValue(ctrl.traceClearanceClassIndex, item.clearanceClassIndex(), layer)
                  + halfWidth
                  + halfDiagonal;
          paint(item, layer, need, blocked[li]);
        }
      }
    }

    /** The window grown by the largest reach of an obstacle into it. */
    private IntBox grownWindow() {
      return windowBox().offset(unitsPerMm() * 3);
    }

    /** Marks the cells closer than {@code need} to the item's shapes on the layer. */
    private void paint(Item item, int layer, double need, boolean[] mask) {
      for (int i = 0; i < item.tileShapeCount(); i++) {
        if (item.shapeLayer(i) != layer) {
          continue;
        }
        TileShape shape = item.getTileShape(i);
        IntBox box = shape.boundingBox();
        int c0 = Math.max(0, (int) Math.floor((box.ll.x - need - originX) / cell));
        int c1 = Math.min(cols - 1, (int) Math.ceil((box.ur.x + need - originX) / cell));
        int r0 = Math.max(0, (int) Math.floor((box.ll.y - need - originY) / cell));
        int r1 = Math.min(rows - 1, (int) Math.ceil((box.ur.y + need - originY) / cell));
        for (int r = r0; r <= r1; r++) {
          for (int c = c0; c <= c1; c++) {
            int index = r * cols + c;
            if (!mask[index] && shape.distance(new FloatPoint(cx(c), cy(r))) < need) {
              mask[index] = true;
            }
          }
        }
      }
    }

    private void paintEnds() {
      Set<Item> fromGroup = fromItem.getConnectedSet(net);
      Set<Item> toGroup = toItem.getConnectedSet(net);
      for (int li = 0; li < layers.size(); li++) {
        int layer = layers.get(li);
        markInside(fromGroup, layer, start[li], startOwner[li]);
        markInside(toGroup, layer, goal[li], goalOwner[li]);
      }
    }

    /**
     * Marks the cells inside the group's copper on the layer and the item each lies in, a pin or
     * via before a trace, since a trace connects only at a drill item's centre or a trace's corner.
     */
    private void markInside(Collection<Item> group, int layer, boolean[] mask, Item[] owner) {
      IntBox window = windowBox();
      for (Item item : group) {
        if (!item.boundingBox().intersects(window) || item instanceof ConductionArea) {
          continue;
        }
        for (int i = 0; i < item.tileShapeCount(); i++) {
          if (item.shapeLayer(i) != layer) {
            continue;
          }
          TileShape shape = item.getTileShape(i);
          IntBox box = shape.boundingBox();
          int c0 = Math.max(0, (int) Math.ceil((box.ll.x - originX) / cell));
          int c1 = Math.min(cols - 1, (int) Math.floor((box.ur.x - originX) / cell));
          int r0 = Math.max(0, (int) Math.ceil((box.ll.y - originY) / cell));
          int r1 = Math.min(rows - 1, (int) Math.floor((box.ur.y - originY) / cell));
          for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
              int index = r * cols + c;
              if (shape.contains(new FloatPoint(cx(c), cy(r)))) {
                mask[index] = true;
                if (owner[index] == null
                    || (owner[index] instanceof Trace && item instanceof DrillItem)) {
                  owner[index] = item;
                }
              }
            }
          }
        }
      }
    }

    /** Sites where via {@code v} keeps its clearance on every layer it spans. */
    private boolean[] viaSites(int v) {
      if (viaFree[v] != null) {
        return viaFree[v];
      }
      ViaInfo via = vias.get(v);
      Padstack padstack = via.getPadstack();
      boolean[] taken = new boolean[cols * rows];
      for (int layer = padstack.fromLayer(); layer <= padstack.toLayer(); layer++) {
        if (padstack.getShape(layer) == null) {
          continue;
        }
        double radius = 0.5 * padstack.getShape(layer).maxWidth();
        for (SearchTreeObject object : board.overlappingObjects(grownWindow(), layer)) {
          if (!(object instanceof Item item) || !isObstacle(item, true)) {
            continue;
          }
          double need =
              board.clearanceValue(via.getClearanceClassIndex(), item.clearanceClassIndex(), layer)
                  + radius;
          paint(item, layer, need, taken);
        }
      }
      boolean[] free = new boolean[cols * rows];
      for (int i = 0; i < free.length; i++) {
        free[i] = !taken[i];
      }
      viaFree[v] = free;
      return free;
    }

    private int layerIndex(int layer) {
      return layers.indexOf(layer);
    }

    /**
     * A* over (layer, cell, arrived by via). Returns the path as states from start to goal, or
     * null.
     */
    private int[] search() {
      int n = layers.size();
      int cells = cols * rows;
      int states = n * cells * 2;
      cost = new double[states];
      Arrays.fill(cost, Double.POSITIVE_INFINITY);
      parent = new int[states];
      closed = new boolean[states];
      double mm = unitsPerMm();
      final double viaCost = VIA_COST_MM * mm / cell;
      double[][] stepCost = new double[n][3]; // horizontal, vertical, diagonal per layer
      for (int li = 0; li < n; li++) {
        AutorouteControl.ExpansionCostFactor factor = ctrl.traceCosts[layers.get(li)];
        double h = factor.horizontal();
        double v = factor.vertical();
        double min = Math.min(h, v);
        stepCost[li][0] = h / min;
        stepCost[li][1] = v / min;
        stepCost[li][2] = Math.sqrt(2) * (h + v) / (2 * min);
      }
      int gc0 = cols;
      int gc1 = -1;
      int gr0 = rows;
      int gr1 = -1;
      for (int li = 0; li < n; li++) {
        for (int i = 0; i < cells; i++) {
          if (goal[li][i]) {
            gc0 = Math.min(gc0, i % cols);
            gc1 = Math.max(gc1, i % cols);
            gr0 = Math.min(gr0, i / cols);
            gr1 = Math.max(gr1, i / cols);
          }
        }
      }
      if (gc1 < 0) {
        return null;
      }
      goalCol0 = gc0;
      goalCol1 = gc1;
      goalRow0 = gr0;
      goalRow1 = gr1;
      open =
          new PriorityQueue<>(
              (p, q) -> {
                int byF = Double.compare(p[0], q[0]);
                return byF != 0 ? byF : Double.compare(p[1], q[1]);
              });
      for (int li = 0; li < n; li++) {
        for (int i = 0; i < cells; i++) {
          if (start[li][i]) {
            int state = (li * cells + i) * 2;
            cost[state] = 0;
            parent[state] = -1;
            open.add(new double[] {heuristic(i), state});
          }
        }
      }
      while (!open.isEmpty()) {
        double[] top = open.poll();
        int state = (int) top[1];
        if (closed[state]) {
          continue;
        }
        closed[state] = true;
        if (++expansions > maxExpansions
            || ((expansions & 0xFFFF) == 0 && thread != null && thread.isStopRequested())) {
          return null;
        }
        int li = state / 2 / cells;
        int i = (state / 2) % cells;
        boolean byVia = (state & 1) == 1;
        if (goal[li][i] && !start[li][i]) {
          return trace(parent, state);
        }
        int c = i % cols;
        int r = i / cols;
        for (int d = 0; d < DIRECTIONS.length; d++) {
          int nc = c + DIRECTIONS[d][0];
          int nr = r + DIRECTIONS[d][1];
          if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) {
            continue;
          }
          int ni = nr * cols + nc;
          if (blocked[li][ni] && !start[li][ni] && !goal[li][ni]) {
            continue;
          }
          if (d >= 4 && blocked[li][r * cols + nc] && blocked[li][nr * cols + c] && !goal[li][ni]) {
            continue; // no cutting between two blocked cells
          }
          double step = d < 2 ? stepCost[li][0] : d < 4 ? stepCost[li][1] : stepCost[li][2];
          relax(state, (li * cells + ni) * 2, cost[state] + step, ni);
        }
        if (byVia) {
          continue; // two vias in a row would stack at one point
        }
        for (int v = 0; v < vias.size(); v++) {
          Padstack padstack = vias.get(v).getPadstack();
          int layer = layers.get(li);
          if (layer < padstack.fromLayer() || layer > padstack.toLayer()) {
            continue;
          }
          if (start[li][i] || goal[li][i]) {
            continue; // a via on the end copper would not connect at its centre
          }
          if (!viaSites(v)[i]) {
            continue;
          }
          for (int lj = 0; lj < n; lj++) {
            int other = layers.get(lj);
            if (lj == li || other < padstack.fromLayer() || other > padstack.toLayer()) {
              continue;
            }
            if (blocked[lj][i] && !goal[lj][i] && !start[lj][i]) {
              continue;
            }
            relax(state, (lj * cells + i) * 2 + 1, cost[state] + viaCost, i);
          }
        }
      }
      return null;
    }

    private void relax(int from, int to, double newCost, int cellIndex) {
      if (closed[to] || newCost >= cost[to]) {
        return;
      }
      cost[to] = newCost;
      parent[to] = from;
      open.add(new double[] {newCost + heuristic(cellIndex), to});
    }

    /** Octile distance in cells to the goal cells' bounding box, the cheapest step cost each. */
    private double heuristic(int i) {
      int c = i % cols;
      int r = i / cols;
      int dc = c < goalCol0 ? goalCol0 - c : c > goalCol1 ? c - goalCol1 : 0;
      int dr = r < goalRow0 ? goalRow0 - r : r > goalRow1 ? r - goalRow1 : 0;
      int lo = Math.min(dc, dr);
      int hi = Math.max(dc, dr);
      return (hi - lo) + Math.sqrt(2) * lo;
    }

    private int[] trace(int[] parent, int last) {
      List<Integer> reversed = new ArrayList<>();
      for (int s = last; s >= 0; s = parent[s]) {
        reversed.add(s);
      }
      int[] path = new int[reversed.size()];
      for (int k = 0; k < path.length; k++) {
        path[k] = reversed.get(path.length - 1 - k);
      }
      return path;
    }

    private boolean insert(int[] path) {
      int cells = cols * rows;
      final int maxIdBefore = board.communication.idGenerator.maxGeneratedId();
      board.generateSnapshot();
      boolean ok = true;
      List<Point> run = new ArrayList<>();
      int firstLi = path[0] / 2 / cells;
      int firstCell = (path[0] / 2) % cells;
      int lastLi = path[path.length - 1] / 2 / cells;
      int lastCell = (path[path.length - 1] / 2) % cells;
      Item startItem = startOwner[firstLi][firstCell];
      Item goalItem = goalOwner[lastLi][lastCell];
      int runLayer = layers.get(firstLi);
      if (startItem instanceof DrillItem drill) {
        run.add(drill.getCenter());
      }
      for (int k = 0; k < path.length && ok; k++) {
        int state = path[k];
        int li = state / 2 / cells;
        int i = (state / 2) % cells;
        IntPoint point = new FloatPoint(cx(i % cols), cy(i / cols)).round();
        if ((state & 1) == 1) {
          // Arrived by via: finish the run on the old layer, then drill.
          ok = insertRun(run, runLayer);
          run.clear();
          ok = ok && insertVia(point, runLayer, layers.get(li));
          runLayer = layers.get(li);
        }
        if (run.isEmpty() || !run.get(run.size() - 1).equals(point)) {
          run.add(point);
        }
      }
      if (goalItem instanceof DrillItem drill
          && !run.get(run.size() - 1).equals(drill.getCenter())) {
        run.add(drill.getCenter());
      }
      ok = ok && insertRun(run, runLayer);
      ok = ok && joinTrace(startItem, path[0]);
      ok = ok && joinTrace(goalItem, path[path.length - 1]);
      outcome = ok ? "closed" : "insert failed";
      if (ok && !fromItem.getConnectedSet(net).contains(toItem)) {
        ok = false;
        outcome = "path does not connect";
      }
      if (ok) {
        for (Item item : board.getConnectableItems(net)) {
          if (item.getId() > maxIdBefore && !item.clearanceViolations().isEmpty()) {
            ok = false;
            outcome = "clearance violation at " + item;
            break;
          }
        }
      }
      if (ok) {
        board.popSnapshot();
      } else {
        board.undo(null);
      }
      return ok;
    }

    /** Joins the path's end to a trace it lies on, which it touches only between corners. */
    private boolean joinTrace(Item owner, int state) {
      if (!(owner instanceof Trace trace)) {
        return true;
      }
      int i = (state / 2) % (cols * rows);
      IntPoint point = new FloatPoint(cx(i % cols), cy(i / cols)).round();
      int layer = trace.getLayer();
      return board.connectToTrace(
          point, trace, ctrl.traceHalfWidth[layer], ctrl.traceClearanceClassIndex);
    }

    private boolean insertRun(List<Point> run, int layer) {
      if (run.size() < 2) {
        return true; // a via straight on the start or goal copper
      }
      Polyline polyline = new Polyline(run.toArray(new Point[0]));
      if (polyline.cornerCount() < 2) {
        return true;
      }
      return board.insertTraceWithoutCleaning(
              polyline,
              layer,
              ctrl.traceHalfWidth[layer],
              new int[] {net},
              ctrl.traceClearanceClassIndex,
              FixedState.UNFIXED)
          != null;
    }

    private boolean insertVia(IntPoint at, int fromLayer, int toLayer) {
      int lo = Math.min(fromLayer, toLayer);
      int hi = Math.max(fromLayer, toLayer);
      // The shortest via of the rule that spans both layers and fits here.
      ViaInfo best = null;
      int site =
          (int) Math.round((at.y - originY) / cell) * cols
              + (int) Math.round((at.x - originX) / cell);
      for (int v = 0; v < vias.size(); v++) {
        Padstack padstack = vias.get(v).getPadstack();
        if (padstack.fromLayer() <= lo
            && padstack.toLayer() >= hi
            && viaSites(v)[site]
            && (best == null
                || padstack.toLayer() - padstack.fromLayer()
                    < best.getPadstack().toLayer() - best.getPadstack().fromLayer())) {
          best = vias.get(v);
        }
      }
      if (best == null) {
        return false;
      }
      return board.insertVia(
              best.getPadstack(),
              at,
              new int[] {net},
              best.getClearanceClassIndex(),
              FixedState.UNFIXED,
              best.attachSmdAllowed())
          != null;
    }
  }
}
