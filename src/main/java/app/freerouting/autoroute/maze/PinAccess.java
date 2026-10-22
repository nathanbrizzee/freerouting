package app.freerouting.autoroute.maze;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetClass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Exits held for the pins that still need one. For every single-layer pin of a net with something
 * to connect to, one short stub that leaves the pad without a clearance violation is chosen: the
 * free direction nearest the net's closest other pin, at the net's trace width, else at the pin's
 * neck-down width. Until the pin has a trace or via on it, a maze move of another net that passes
 * within a trace pitch of the stub costs {@code 1 + factor * count} times as much, so other nets
 * route around the front of an unconnected pad instead of closing it in (pin access planning, as
 * VLSI routers plan access points per pin, here as a soft cost).
 *
 * <p>Deterministic: the stubs depend only on the board when the router starts, and which pins still
 * need theirs is read at the start of each pass.
 */
public final class PinAccess {

  /** Cell side in trace pitches, as in {@link RipHistory}. */
  static final int CELL_PITCHES = 4;

  private static final int[][] DIRECTIONS = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  /** One held exit: a stub from the pad centre, kept clear by {@code radius}. */
  record Exit(Pin pin, int net, int layer, FloatPoint from, FloatPoint to, double radius) {}

  private final List<Exit> exits;
  private final boolean[] held;
  private final int[] stamp;
  private final int pinsWithoutExit;
  private final int layerCount;
  private final int cols;
  private final int rows;
  private final double cell;
  private final double originX;
  private final double originY;
  private final int[][] cells;
  private int query;
  private int heldCount;

  PinAccess(List<Exit> exits, int pinsWithoutExit, IntBox box, int layerCount, double cell) {
    this.exits = exits;
    this.held = new boolean[exits.size()];
    this.stamp = new int[exits.size()];
    this.pinsWithoutExit = pinsWithoutExit;
    this.layerCount = layerCount;
    this.cell = Math.max(1.0, cell);
    this.originX = box.ll.x;
    this.originY = box.ll.y;
    this.cols = Math.max(1, (int) Math.ceil(box.width() / this.cell) + 1);
    this.rows = Math.max(1, (int) Math.ceil(box.height() / this.cell) + 1);
    List<List<Integer>> lists = new ArrayList<>();
    for (int i = 0; i < layerCount * this.cols * this.rows; i++) {
      lists.add(null);
    }
    for (int e = 0; e < exits.size(); e++) {
      Exit exit = exits.get(e);
      double reach = exit.radius + this.cell;
      int c0 = col(Math.min(exit.from.x, exit.to.x) - reach);
      int c1 = col(Math.max(exit.from.x, exit.to.x) + reach);
      int r0 = row(Math.min(exit.from.y, exit.to.y) - reach);
      int r1 = row(Math.max(exit.from.y, exit.to.y) + reach);
      for (int r = r0; r <= r1; r++) {
        for (int c = c0; c <= c1; c++) {
          int index = (exit.layer * this.rows + r) * this.cols + c;
          if (lists.get(index) == null) {
            lists.set(index, new ArrayList<>());
          }
          lists.get(index).add(e);
        }
      }
      this.held[e] = true;
    }
    this.heldCount = exits.size();
    this.cells = new int[lists.size()][];
    for (int i = 0; i < lists.size(); i++) {
      List<Integer> list = lists.get(i);
      this.cells[i] = list == null ? null : list.stream().mapToInt(Integer::intValue).toArray();
    }
  }

  /** The exits of board's pins, cells {@link #CELL_PITCHES} default trace pitches wide. */
  public static PinAccess forBoard(RoutingBoard board) {
    NetClass defaultClass = board.rules.getDefaultNetClass();
    int defaultHalfWidth = Math.max(1, defaultClass.getTraceHalfWidth(0));
    int defaultClearance =
        board.rules.clearanceMatrix.getValue(
            defaultClass.getTraceClearanceClass(), defaultClass.getTraceClearanceClass(), 0, false);
    double pitch = 2.0 * defaultHalfWidth + Math.max(0, defaultClearance);

    List<Pin> pins = new ArrayList<>(board.getSmdPins());
    pins.sort(Comparator.comparingInt(Item::getId));
    List<Exit> exits = new ArrayList<>();
    int withoutExit = 0;
    for (Pin pin : pins) {
      if (pin.netCount() != 1) {
        continue;
      }
      int net = pin.getNetNumber(0);
      Pin nearest = nearestOtherPin(board, pin, net);
      if (nearest == null) {
        continue;
      }
      Exit exit = chooseExit(board, pin, net, nearest);
      if (exit == null) {
        withoutExit++;
      } else {
        exits.add(exit);
      }
    }
    return new PinAccess(
        exits, withoutExit, board.getBoundingBox(), board.getLayerCount(), CELL_PITCHES * pitch);
  }

  private static Pin nearestOtherPin(RoutingBoard board, Pin pin, int net) {
    FloatPoint centre = pin.getCenter().toFloat();
    Pin best = null;
    double bestDistance = Double.MAX_VALUE;
    for (Item item : board.getConnectableItems(net)) {
      if (!(item instanceof Pin other) || other == pin) {
        continue;
      }
      double distance = centre.distance(other.getCenter().toFloat());
      if (distance < bestDistance
          || distance == bestDistance && best != null && other.getId() < best.getId()) {
        best = other;
        bestDistance = distance;
      }
    }
    return best;
  }

  /**
   * The free stub of pin closest in direction to toward, at the net's trace width or else at the
   * pin's neck-down width; null when no stub fits.
   */
  static Exit chooseExit(RoutingBoard board, Pin pin, int net, Pin toward) {
    Net rulesNet = board.rules.nets.get(net);
    if (rulesNet == null || rulesNet.getNetClass() == null) {
      return null;
    }
    NetClass netClass = rulesNet.getNetClass();
    int layer = pin.firstLayer();
    if (pin.getTileShapeOnLayer(layer) == null) {
      return null;
    }
    int clearanceClass = netClass.getTraceClearanceClass();
    int clearance =
        board.rules.clearanceMatrix.getValue(clearanceClass, clearanceClass, layer, false);
    int full = Math.max(1, netClass.getTraceHalfWidth(layer));
    int neck = pin.getTraceNeckdownHalfwidth(layer);
    neck = neck > 0 ? Math.min(full, neck) : full;
    FloatPoint centre = pin.getCenter().toFloat();
    FloatPoint target = toward.getCenter().toFloat();
    double dx = target.x - centre.x;
    double dy = target.y - centre.y;
    IntBox pad = pin.getTileShapeOnLayer(layer).boundingBox();
    for (int halfWidth : neck < full ? new int[] {full, neck} : new int[] {full}) {
      Polyline best = null;
      double bestScore = -Double.MAX_VALUE;
      for (int[] direction : DIRECTIONS) {
        double length = Math.hypot(direction[0], direction[1]);
        double score = (direction[0] * dx + direction[1] * dy) / length;
        if (score <= bestScore) {
          continue;
        }
        Polyline stub = stub(pin, direction, pad, halfWidth, clearance);
        if (board.checkPolylineTrace(stub, layer, halfWidth, new int[] {net}, clearanceClass)) {
          best = stub;
          bestScore = score;
        }
      }
      if (best != null) {
        return new Exit(
            pin,
            net,
            layer,
            best.firstCorner().toFloat(),
            best.lastCorner().toFloat(),
            2.0 * halfWidth + clearance);
      }
    }
    return null;
  }

  /** A stub from the pad centre to just beyond the pad edge in direction. */
  private static Polyline stub(Pin pin, int[] direction, IntBox pad, int halfWidth, int clearance) {
    double length = Math.hypot(direction[0], direction[1]);
    double extent =
        Math.max(
                direction[0] != 0 ? pad.width() / 2.0 : 0,
                direction[1] != 0 ? pad.height() / 2.0 : 0)
            * length;
    double reach = extent + 2.0 * halfWidth + clearance;
    Point centre = pin.getCenter();
    FloatPoint c = centre.toFloat();
    IntPoint end =
        new IntPoint(
            (int) Math.round(c.x + direction[0] / length * reach),
            (int) Math.round(c.y + direction[1] / length * reach));
    return new Polyline(new Point[] {centre, end});
  }

  /** Holds the exits of the pins with nothing on them yet; called at the start of each pass. */
  public void refresh() {
    this.heldCount = 0;
    for (int e = 0; e < this.exits.size(); e++) {
      this.held[e] = this.exits.get(e).pin.getNormalContacts().isEmpty();
      if (this.held[e]) {
        this.heldCount++;
      }
    }
  }

  /** How many held exits of other nets than net a move on layer passes, sampled every half cell. */
  public int crossed(int layer, int net, FloatPoint from, FloatPoint to) {
    if (layer < 0 || layer >= this.layerCount || this.heldCount == 0) {
      return 0;
    }
    this.query++;
    int result = 0;
    int samples = Math.max(1, (int) Math.ceil(2 * from.distance(to) / this.cell));
    int lastIndex = -1;
    for (int s = 0; s <= samples; s++) {
      double t = (double) s / samples;
      int c = col(from.x + t * (to.x - from.x));
      int r = row(from.y + t * (to.y - from.y));
      int index = (layer * this.rows + r) * this.cols + c;
      if (index == lastIndex || this.cells[index] == null) {
        lastIndex = index;
        continue;
      }
      lastIndex = index;
      for (int e : this.cells[index]) {
        if (this.stamp[e] == this.query) {
          continue;
        }
        this.stamp[e] = this.query;
        Exit exit = this.exits.get(e);
        if (this.held[e]
            && exit.net != net
            && segmentDistance(from, to, exit.from, exit.to) < exit.radius) {
          result++;
        }
      }
    }
    return result;
  }

  /** One phrase for the pass log. */
  public String describe() {
    return String.format(
        Locale.US,
        "pin access: %d of %d exits held, %d pins without an exit",
        this.heldCount,
        this.exits.size(),
        this.pinsWithoutExit);
  }

  private int col(double x) {
    return Math.clamp((long) Math.floor((x - this.originX) / this.cell), 0, this.cols - 1);
  }

  private int row(double y) {
    return Math.clamp((long) Math.floor((y - this.originY) / this.cell), 0, this.rows - 1);
  }

  /** The distance between segments ab and cd. */
  static double segmentDistance(FloatPoint a, FloatPoint b, FloatPoint c, FloatPoint d) {
    if (segmentsCross(a, b, c, d)) {
      return 0;
    }
    return Math.min(
        Math.min(pointDistance(a, c, d), pointDistance(b, c, d)),
        Math.min(pointDistance(c, a, b), pointDistance(d, a, b)));
  }

  private static boolean segmentsCross(FloatPoint a, FloatPoint b, FloatPoint c, FloatPoint d) {
    double d1 = cross(c, d, a);
    double d2 = cross(c, d, b);
    double d3 = cross(a, b, c);
    double d4 = cross(a, b, d);
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
  }

  private static double cross(FloatPoint o, FloatPoint p, FloatPoint q) {
    return (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x);
  }

  /** The distance from p to segment ab. */
  private static double pointDistance(FloatPoint p, FloatPoint a, FloatPoint b) {
    double dx = b.x - a.x;
    double dy = b.y - a.y;
    double lengthSquared = dx * dx + dy * dy;
    double t = lengthSquared == 0 ? 0 : ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
  }
}
