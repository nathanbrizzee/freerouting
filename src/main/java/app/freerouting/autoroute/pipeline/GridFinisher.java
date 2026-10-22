package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.path.Connection;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ComponentOutline;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.items.ViaObstacleArea;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.searchtree.SearchTreeObject;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.library.Padstack;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.drc.NetIncompletes;
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
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

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
 * <p>With {@code autorouter.grid_finisher_ripup}, a connection walled in by other nets' movable
 * copper may lift those connections and route them again (see {@link #lift}).
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

  /** Connections one open connection may lift ({@code autorouter.grid_finisher_ripup}). */
  static final int MAX_LIFTED_CONNECTIONS = 4;

  /** Extra cost of a cell inside another net's movable copper, in cells of trace. */
  private static final double LIFT_CELL_COST = 10.0;

  private static final int[][] DIRECTIONS = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private GridFinisher() {}

  record Summary(int open, int tried, int closed, long expansions, int lifted) {}

  /** The outcome of lifting other copper for one connection. */
  private record Lift(boolean closed, long expansions, String outcome) {}

  /**
   * Tries every open connection once, until the stage's step budget ({@code
   * autorouter.grid_finisher_max_steps}) is spent or a stop is requested. A board that cannot be
   * finished keeps its open connections; nothing is retried. Returns what it did.
   */
  static Summary run(RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    final long budget =
        settings.autorouter != null && settings.autorouter.gridFinisherMaxSteps != null
            ? settings.autorouter.gridFinisherMaxSteps
            : DEFAULT_MAX_STEPS;
    final boolean lift =
        settings.autorouter != null && Boolean.TRUE.equals(settings.autorouter.gridFinisherRipup);
    final long started = System.currentTimeMillis();
    List<AirLine> sorted = new ArrayList<>();
    for (AirLine airline : new DesignRulesChecker(board, null).getAllAirlines()) {
      if (airline != null && airline.net != null) {
        sorted.add(airline);
      }
    }
    sortAirlines(sorted);
    if (lift) {
      // Connection.get caches on the items; the maze passes may have left stale entries.
      board.clearTransientAutorouteState();
    }
    int tried = 0;
    int closed = 0;
    int lifted = 0;
    long expansions = 0;
    java.util.Map<String, Integer> outcomes = new java.util.TreeMap<>();
    for (AirLine airline : sorted) {
      if ((thread != null && thread.isStopRequested()) || expansions >= budget) {
        break;
      }
      if (!airline.fromItem.isOnTheBoard() || !airline.toItem.isOnTheBoard()) {
        continue; // its copper was lifted and routed again for another connection
      }
      int net = airline.net.netNumber;
      if (airline.fromItem.getConnectedSet(net).contains(airline.toItem)) {
        continue; // closed by an earlier connection of the same net
      }
      tried++;
      Attempt attempt =
          new Attempt(
              board,
              settings,
              airline,
              thread,
              Math.min(MAX_EXPANSIONS, budget - expansions),
              false);
      boolean done = attempt.route();
      String outcome = attempt.outcome;
      long used = attempt.expansions;
      if (!done
          && lift
          && expansions + used < budget
          && (outcome.equals("no path") || outcome.equals("end copper boxed in"))) {
        Lift result = lift(board, settings, airline, thread, budget - expansions - used);
        used += result.expansions();
        outcome = result.outcome();
        done = result.closed();
        if (done) {
          lifted++;
        }
      }
      if (done) {
        closed++;
      }
      FRLogger.debug(
          "Grid finisher: net "
              + airline.net.name
              + " at "
              + airline.fromCorner
              + ": "
              + outcome
              + " after "
              + used
              + " states.");
      expansions += used;
      outcomes.merge(outcome.split(" at ")[0], 1, Integer::sum);
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
            + " closed"
            + (lift ? " (" + lifted + " by lifting other copper) " : " ")
            + outcomes
            + ", "
            + expansions
            + " grid states expanded of "
            + budget
            + ", in "
            + (System.currentTimeMillis() - started) / 1000.0
            + " s.");
    return new Summary(sorted.size(), tried, closed, expansions, lifted);
  }

  /** Orders airlines by net, then by the position of their first end, for a deterministic run. */
  private static void sortAirlines(List<AirLine> airlines) {
    airlines.sort(
        (a, b) -> {
          int byNet = Integer.compare(a.net.netNumber, b.net.netNumber);
          if (byNet != 0) {
            return byNet;
          }
          int byX = Double.compare(a.fromCorner.x, b.fromCorner.x);
          return byX != 0 ? byX : Double.compare(a.fromCorner.y, b.fromCorner.y);
        });
  }

  /** The net's open connections, in run order. */
  private static List<AirLine> netAirlines(RoutingBoard board, int net) {
    List<AirLine> result =
        new ArrayList<>(
            new NetIncompletes(net, board.getConnectableItems(net), board).getIncompletes());
    sortAirlines(result);
    return result;
  }

  private static int openCount(RoutingBoard board, Collection<Integer> nets) {
    int result = 0;
    for (int net : nets) {
      result += new NetIncompletes(net, board.getConnectableItems(net), board).count();
    }
    return result;
  }

  /**
   * Lifts the other nets' movable connections that wall in a connection, routes it, and routes the
   * lifted ones again, all inside one board snapshot ({@code autorouter.grid_finisher_ripup},
   * window search and repair). The path to lift for comes from a search that may cross other nets'
   * unfixed traces and vias at a cost per cell, so it crosses as few as it can; every connection it
   * crosses is lifted whole, up to {@link #MAX_LIFTED_CONNECTIONS}. The result is kept only if the
   * nets involved then have fewer open connections and no new item has a clearance violation, so a
   * lift never trades one open connection for another.
   */
  private static Lift lift(
      RoutingBoard board,
      RouterSettings settings,
      AirLine airline,
      StoppableThread thread,
      long budget) {
    final int net = airline.net.netNumber;
    Attempt probe =
        new Attempt(board, settings, airline, thread, Math.min(MAX_EXPANSIONS, budget), true);
    List<Item> crossed = probe.findLifts();
    long used = probe.expansions;
    if (crossed == null) {
      return new Lift(false, used, "lift: " + probe.outcome);
    }
    Set<Item> lifted = new TreeSet<>(Comparator.comparingInt(Item::getId));
    int connections = 0;
    for (Item item : crossed) {
      if (lifted.contains(item)) {
        continue; // part of a connection already lifted
      }
      connections++;
      Connection connection = Connection.get(item);
      if (connection != null) {
        lifted.addAll(connection.itemList);
      } else {
        // A via of more than one connection: lift it with every trace connection it ends.
        lifted.add(item);
        for (Item contact : item.getNormalContacts()) {
          Connection ended = contact instanceof Trace ? Connection.get(contact) : null;
          if (ended != null) {
            lifted.addAll(ended.itemList);
          }
        }
      }
    }
    for (Item item : lifted) {
      item.getAutorouteInfo().setPrecalculatedConnection(null);
    }
    if (connections > MAX_LIFTED_CONNECTIONS) {
      return new Lift(false, used, "too many connections to lift");
    }
    Set<Integer> nets = new TreeSet<>();
    nets.add(net);
    for (Item item : lifted) {
      if (!probe.isLiftable(item)) {
        return new Lift(false, used, "fixed copper in a connection to lift");
      }
      for (int i = 0; i < item.netCount(); i++) {
        nets.add(item.getNetNumber(i));
      }
    }
    final int before = openCount(board, nets);
    board.generateSnapshot();
    boolean ok = board.removeItems(new ArrayList<>(lifted));
    String outcome = "lifted copper could not be removed";
    if (ok) {
      Attempt main =
          new Attempt(
              board, settings, airline, thread, Math.min(MAX_EXPANSIONS, budget - used), false);
      ok = main.route();
      used += main.expansions;
      outcome = "lift: " + main.outcome;
    }
    if (ok) {
      for (int other : nets) {
        if (other == net) {
          continue;
        }
        for (AirLine gap : netAirlines(board, other)) {
          if (used >= budget || (thread != null && thread.isStopRequested())) {
            break;
          }
          if (gap.fromItem.getConnectedSet(other).contains(gap.toItem)) {
            continue;
          }
          Attempt again =
              new Attempt(
                  board, settings, gap, thread, Math.min(MAX_EXPANSIONS, budget - used), false);
          // It must now go round the new path, which may leave its own window.
          again.include = probe.windowBox();
          again.route();
          used += again.expansions;
        }
      }
      ok = openCount(board, nets) < before;
      outcome = ok ? "closed after lifting" : "lifted copper not routed again";
    }
    if (ok) {
      board.popSnapshot();
    } else {
      board.undo(null);
    }
    return new Lift(ok, used, outcome);
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

    /** Whether other nets' movable copper is crossed at a cost instead of blocking. */
    private final boolean lift;

    /** In lift mode, per routable layer: cells inside other nets' movable copper. */
    private boolean[][] soft;

    /** In lift mode: the movable items painted into {@link #soft}. */
    private final List<Blocker> blockers = new ArrayList<>();

    private record Blocker(Item item, int li, double need) {}

    /** A box the window must also cover, or null. */
    private IntBox include;

    Attempt(
        RoutingBoard board,
        RouterSettings settings,
        AirLine airline,
        StoppableThread thread,
        long maxExpansions,
        boolean lift) {
      this.board = board;
      this.thread = thread;
      this.maxExpansions = maxExpansions;
      this.lift = lift;
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
        outcome = "no window";
        return false;
      }
      paintObstacles();
      paintEnds();
      if (!hasFreeEnd(start) || !hasFreeEnd(goal)) {
        outcome = hasAny(start) && hasAny(goal) ? "end copper boxed in" : "no end copper in window";
        return false;
      }
      int[] path = search();
      if (path == null) {
        outcome = "no path";
        return false;
      }
      return insert(path);
    }

    /**
     * In lift mode: the other nets' movable items the cheapest path crosses, ordered by id, or null
     * with the outcome set when even lifting them all would leave no path.
     */
    List<Item> findLifts() {
      if (layers.isEmpty() || !buildWindow()) {
        outcome = "no window";
        return null;
      }
      paintObstacles();
      paintEnds();
      if (!hasFreeEnd(start) || !hasFreeEnd(goal)) {
        outcome = "end copper boxed in by fixed copper";
        return null;
      }
      int[] path = search();
      if (path == null) {
        outcome = "no path past fixed copper";
        return null;
      }
      int cells = cols * rows;
      Set<Item> crossed = new TreeSet<>(Comparator.comparingInt(Item::getId));
      for (int state : path) {
        int li = state / 2 / cells;
        int i = (state / 2) % cells;
        if (!soft[li][i] || start[li][i] || goal[li][i]) {
          continue;
        }
        FloatPoint centre = new FloatPoint(cx(i % cols), cy(i / cols));
        for (Blocker blocker : blockers) {
          if (blocker.li() == li
              && !crossed.contains(blocker.item())
              && distance(blocker.item(), layers.get(li), centre) < blocker.need()) {
            crossed.add(blocker.item());
          }
        }
      }
      return new ArrayList<>(crossed);
    }

    private static double distance(Item item, int layer, FloatPoint point) {
      double result = Double.POSITIVE_INFINITY;
      for (int i = 0; i < item.tileShapeCount(); i++) {
        if (item.shapeLayer(i) == layer) {
          result = Math.min(result, item.getTileShape(i).distance(point));
        }
      }
      return result;
    }

    /** Another net's trace or via that the router may rip up: not fixed by the user. */
    boolean isLiftable(Item item) {
      return (item instanceof Trace || item instanceof Via)
          && item.netCount() > 0
          && !item.containsNet(net)
          && !item.isUserFixed()
          && !item.isDeletionForbidden();
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
      if (include != null) {
        window = window.union(include);
      }
      IntBox boardBox = board.getBoundingBox();
      if (boardBox != null) {
        window = window.intersection(boardBox);
      }
      if (window.isEmpty()) {
        return false;
      }
      // A third of a trace pitch, so a channel one trace wide shows as free cells. A trace between
      // two free cell centres may still graze an obstacle corner; the exact check then rejects it.
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
      cell = Math.max(1.0, smallestPitch / 3.0);
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
      soft = lift ? new boolean[n][cols * rows] : null;
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
      for (int li = 0; li < layers.size(); li++) {
        int layer = layers.get(li);
        int halfWidth = ctrl.traceHalfWidth[layer];
        for (SearchTreeObject object : board.overlappingObjects(grownWindow(), layer)) {
          if (!(object instanceof Item item) || !isObstacle(item, false)) {
            continue;
          }
          double need =
              board.clearanceValue(ctrl.traceClearanceClassIndex, item.clearanceClassIndex(), layer)
                  + halfWidth;
          if (lift && isLiftable(item)) {
            paint(item, layer, need, soft[li]);
            blockers.add(new Blocker(item, li, need));
          } else {
            paint(item, layer, need, blocked[li]);
          }
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
        // Only cells inside the copper: cells beside a thin trace needed stubs that then failed the
        // clearance check more often than they closed a connection (L6, M4).
        double reach = 0;
        for (int i = 0; i < item.tileShapeCount(); i++) {
          if (item.shapeLayer(i) != layer) {
            continue;
          }
          TileShape shape = item.getTileShape(i);
          IntBox box = shape.boundingBox();
          int c0 = Math.max(0, (int) Math.ceil((box.ll.x - reach - originX) / cell));
          int c1 = Math.min(cols - 1, (int) Math.floor((box.ur.x + reach - originX) / cell));
          int r0 = Math.max(0, (int) Math.ceil((box.ll.y - reach - originY) / cell));
          int r1 = Math.min(rows - 1, (int) Math.floor((box.ur.y + reach - originY) / cell));
          for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
              if (shape.distance(new FloatPoint(cx(c), cy(r))) <= reach) {
                mark(r * cols + c, item, mask, owner);
              }
            }
          }
        }
        // A pad or via smaller than a cell still gets the cell at its centre.
        if (item instanceof DrillItem drill && item.isOnLayer(layer)) {
          FloatPoint centre = drill.getCenter().toFloat();
          int c = (int) Math.round((centre.x - originX) / cell);
          int r = (int) Math.round((centre.y - originY) / cell);
          if (c >= 0 && r >= 0 && c < cols && r < rows) {
            mark(r * cols + c, item, mask, owner);
          }
        }
      }
    }

    private static void mark(int index, Item item, boolean[] mask, Item[] owner) {
      mask[index] = true;
      if (owner[index] == null || (owner[index] instanceof Trace && item instanceof DrillItem)) {
        owner[index] = item;
      }
    }

    /** An SMD pad of the net blocks a via whose rule does not allow it in a pad. */
    private boolean blocksViaInPad(Item item, ViaInfo via) {
      return !via.attachSmdAllowed()
          && item instanceof Pin pin
          && pin.containsNet(net)
          && pin.firstLayer() == pin.lastLayer();
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
          if (!(object instanceof Item item)
              || !(isObstacle(item, true) || blocksViaInPad(item, via))) {
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

    private static boolean hasAny(boolean[][] mask) {
      for (boolean[] layer : mask) {
        for (boolean cell : layer) {
          if (cell) {
            return true;
          }
        }
      }
      return false;
    }

    /** Whether some end cell has a neighbour the trace may enter. */
    private boolean hasFreeEnd(boolean[][] ends) {
      for (int li = 0; li < ends.length; li++) {
        for (int i = 0; i < ends[li].length; i++) {
          if (!ends[li][i]) {
            continue;
          }
          int c = i % cols;
          int r = i / cols;
          for (int[] d : DIRECTIONS) {
            int nc = c + d[0];
            int nr = r + d[1];
            if (nc >= 0 && nr >= 0 && nc < cols && nr < rows && !blocked[li][nr * cols + nc]) {
              return true;
            }
          }
        }
      }
      return false;
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
          relax(state, (li * cells + ni) * 2, cost[state] + step + liftCost(li, ni), ni);
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
            relax(state, (lj * cells + i) * 2 + 1, cost[state] + viaCost + liftCost(lj, i), i);
          }
        }
      }
      return null;
    }

    /** In lift mode, the extra cost of entering a cell inside movable copper. */
    private double liftCost(int li, int i) {
      return soft != null && soft[li][i] && !start[li][i] && !goal[li][i] ? LIFT_CELL_COST : 0;
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
        Item violating = newViolation(maxIdBefore);
        if (violating != null) {
          ok = false;
          outcome = "clearance violation at " + violating;
        }
      }
      if (ok) {
        board.popSnapshot();
      } else {
        board.undo(null);
      }
      return ok;
    }

    /**
     * A new item of the net that has a clearance violation, or null. The check of an item is not
     * symmetric (another net's pin may see a violation the new via does not), so the items near
     * each new one are asked as well.
     */
    private Item newViolation(int maxIdBefore) {
      for (Item item : board.getConnectableItems(net)) {
        if (item.getId() <= maxIdBefore) {
          continue;
        }
        if (!item.clearanceViolations().isEmpty()) {
          return item;
        }
        IntBox near = item.boundingBox().offset(unitsPerMm());
        for (SearchTreeObject object : board.overlappingObjects(near, -1)) {
          if (!(object instanceof Item other) || other == item) {
            continue;
          }
          for (app.freerouting.drc.ClearanceViolation violation : other.clearanceViolations()) {
            if (violation.firstItem == item || violation.secondItem == item) {
              return item;
            }
          }
        }
      }
      return null;
    }

    /** Joins the path's end to a trace it lies on, which it touches only between corners. */
    private boolean joinTrace(Item owner, int state) {
      if (!(owner instanceof Trace trace)) {
        return true;
      }
      int i = (state / 2) % (cols * rows);
      IntPoint point = new FloatPoint(cx(i % cols), cy(i / cols)).round();
      int layer = trace.getLayer();
      if (trace instanceof PolylineTrace polylineTrace
          && polylineTrace.polyline().contains(point)) {
        // On the trace but between its corners: a trace connects only at a corner, so split it.
        board.splitTraces(point, layer, net);
        return true;
      }
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
