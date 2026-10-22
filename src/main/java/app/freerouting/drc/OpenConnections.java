package app.freerouting.drc;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.ObstacleArea;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Component;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetClass;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The connections a board still lacks, named so that a person or an agent can find them: pins by
 * component and pin name, other items by type and position in millimetres. Each pin end says
 * whether a trace can still leave it and, when not, what closes it in; {@link #hints} groups that
 * by component for the decision to move a part.
 */
public final class OpenConnections {

  /** Directions of the exit stubs tried from a pin centre. */
  private static final int[][] DIRECTIONS = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  /** Blockers a hint names before it says how many more there are. */
  private static final int HINT_NAMES = 5;

  private OpenConnections() {}

  /** One open connection of a net, between the two items its airline joins. */
  public static final class Entry {
    @SerializedName("net")
    public String net;

    @SerializedName("from")
    public String from;

    @SerializedName("to")
    public String to;

    /** The airline's end points in millimetres. */
    @SerializedName("from_mm")
    public double[] fromMm;

    @SerializedName("to_mm")
    public double[] toMm;

    @SerializedName("length_mm")
    public Double lengthMm;

    /** Whether a trace can leave the pin at each end; absent for ends that are not pins. */
    @SerializedName("from_exit")
    public Exit fromExit;

    @SerializedName("to_exit")
    public Exit toExit;

    transient String fromComponent;
    transient String toComponent;

    @Override
    public String toString() {
      return net + ": " + from + " - " + to;
    }
  }

  /**
   * Whether a trace of its net can leave a pin: {@code free} at the net's trace width in some
   * direction on some layer, {@code necked} only at the pin's neck-down width, {@code closed} not
   * at all. A direction is tried at the neck-down width, the narrowest the router attaches with.
   * {@code by_design} is true when the design (own pads, fixed copper, keepouts, the board edge) is
   * in the way of every direction, so moving routes or parts opens none; {@code closed_by} then
   * names what is in the way, otherwise what blocks the directions only routes or parts block.
   */
  public static final class Exit {
    @SerializedName("exit")
    public String exit;

    @SerializedName("by_design")
    public Boolean byDesign;

    @SerializedName("closed_by")
    public List<String> closedBy;
  }

  /** What the open ends on one component's pins say about its placement. */
  public static final class Hint {
    @SerializedName("component")
    public String component;

    @SerializedName("open_ends")
    public int openEnds;

    @SerializedName("closed_ends")
    public int closedEnds;

    /** Closed ends that the design alone closes in; moving parts does not open them. */
    @SerializedName("closed_by_design")
    public int closedByDesign;

    /** What closes in the other closed ends: routes and other parts. */
    @SerializedName("closed_by")
    public List<String> closedBy;

    @SerializedName("hint")
    public String hint;

    @Override
    public String toString() {
      return component
          + ": "
          + openEnds
          + " open end"
          + (openEnds == 1 ? "" : "s")
          + ", "
          + closedEnds
          + " with no exit; "
          + hint;
    }
  }

  /** The open connections of board, sorted by net name as the airlines are. */
  public static List<Entry> of(BasicBoard board) {
    AirLine[] airlines = new DesignRulesChecker(board, null).getAllAirlines();
    List<Entry> result = new ArrayList<>(airlines.length);
    double toMm = toMm(board);
    for (AirLine airline : airlines) {
      Entry entry = new Entry();
      entry.net = airline.net.name;
      entry.from = label(board, airline.fromItem, airline.fromCorner);
      entry.to = label(board, airline.toItem, airline.toCorner);
      if (airline.fromCorner != null && airline.toCorner != null) {
        entry.fromMm = mm(airline.fromCorner, toMm);
        entry.toMm = mm(airline.toCorner, toMm);
        entry.lengthMm = airline.fromCorner.distance(airline.toCorner) * toMm;
      }
      if (airline.fromItem instanceof Pin pin) {
        entry.fromExit = exit(board, pin, airline.net.netNumber);
        entry.fromComponent = componentName(board, pin);
      }
      if (airline.toItem instanceof Pin pin) {
        entry.toExit = exit(board, pin, airline.net.netNumber);
        entry.toComponent = componentName(board, pin);
      }
      result.add(entry);
    }
    return result;
  }

  /** One log line naming at most limit of the open connections. */
  public static String summary(List<Entry> open, int limit) {
    StringBuilder line = new StringBuilder("Open connections (" + open.size() + "): ");
    for (int i = 0; i < Math.min(limit, open.size()); i++) {
      if (i > 0) {
        line.append("; ");
      }
      line.append(open.get(i));
    }
    if (open.size() > limit) {
      line.append("; and ").append(open.size() - limit).append(" more");
    }
    return line.toString();
  }

  /**
   * One sentence on what holds the open connections: how many end at a pin the design closes in (no
   * route reaches it as placed), how many at a pin routes or nearby parts close in, and how many
   * have exits at both ends, so the space between their ends is what is full. Null when nothing is
   * open.
   */
  public static String verdict(List<Entry> open) {
    if (open.isEmpty()) {
      return null;
    }
    int design = 0;
    int closedIn = 0;
    for (Entry entry : open) {
      if (closedByDesign(entry.fromExit) || closedByDesign(entry.toExit)) {
        design++;
      } else if (closed(entry.fromExit) || closed(entry.toExit)) {
        closedIn++;
      }
    }
    int between = open.size() - design - closedIn;
    return String.format(
        Locale.ROOT,
        "%d connection%s open: %d at a pin the design closes in, so no route reaches it as"
            + " placed; %d at a pin that routes or nearby parts close in; %d whose ends can be"
            + " left (free pin exits, vias or traces), held by the space between them (see the"
            + " crowded regions).",
        open.size(),
        open.size() == 1 ? "" : "s",
        design,
        closedIn,
        between);
  }

  private static boolean closed(Exit exit) {
    return exit != null && "closed".equals(exit.exit);
  }

  private static boolean closedByDesign(Exit exit) {
    return closed(exit) && Boolean.TRUE.equals(exit.byDesign);
  }

  /**
   * One hint per component with open ends on its pins, most open ends first: whether its pins can
   * still be left, and if not, whether its own pads and fixed copper close them in (a design limit)
   * or other parts and routes do (moving a part may help).
   */
  public static List<Hint> hints(List<Entry> open) {
    Map<String, Hint> byComponent = new TreeMap<>();
    for (Entry entry : open) {
      addEnd(byComponent, entry.fromComponent, entry.fromExit);
      addEnd(byComponent, entry.toComponent, entry.toExit);
    }
    List<Hint> result = new ArrayList<>(byComponent.values());
    for (Hint hint : result) {
      hint.hint = advice(hint);
    }
    result.sort(
        Comparator.comparingInt((Hint hint) -> -hint.openEnds)
            .thenComparingInt(hint -> -hint.closedEnds)
            .thenComparing(hint -> hint.component));
    return result;
  }

  private static void addEnd(Map<String, Hint> byComponent, String component, Exit exit) {
    if (component == null || exit == null) {
      return;
    }
    Hint hint =
        byComponent.computeIfAbsent(
            component,
            name -> {
              Hint created = new Hint();
              created.component = name;
              created.closedBy = new ArrayList<>();
              return created;
            });
    hint.openEnds++;
    if (!"closed".equals(exit.exit)) {
      return;
    }
    hint.closedEnds++;
    if (Boolean.TRUE.equals(exit.byDesign)) {
      hint.closedByDesign++;
    } else {
      Set<String> merged = new TreeSet<>(hint.closedBy);
      merged.addAll(exit.closedBy);
      hint.closedBy = new ArrayList<>(merged);
    }
  }

  private static String advice(Hint hint) {
    if (hint.closedEnds == 0) {
      return "its pins can be left, so the space between the ends is what is full";
    }
    if (hint.closedByDesign == hint.closedEnds) {
      return "its own pads, fixed copper or keepouts leave no exit: a design limit (pad spacing,"
          + " clearance or neck width), not placement";
    }
    int named = Math.min(HINT_NAMES, hint.closedBy.size());
    String names = String.join(", ", hint.closedBy.subList(0, named));
    if (hint.closedBy.size() > named) {
      names += " and " + (hint.closedBy.size() - named) + " more";
    }
    String design =
        hint.closedByDesign > 0
            ? " (" + hint.closedByDesign + " more closed in by the design alone)"
            : "";
    return "closed in by "
        + names
        + design
        + ": moving it or the parts named, or fanning it out by hand, may open an exit";
  }

  /** Whether a trace of netNumber can leave pin, tried as short stubs in eight directions. */
  static Exit exit(BasicBoard board, Pin pin, int netNumber) {
    Net net = board.rules.nets.get(netNumber);
    if (net == null || net.getNetClass() == null) {
      return null;
    }
    NetClass netClass = net.getNetClass();
    int clearanceClass = netClass.getTraceClearanceClass();
    int[] nets = {netNumber};
    boolean tried = false;
    boolean necked = false;
    Set<String> movable = new TreeSet<>();
    Set<String> design = new TreeSet<>();
    for (int layer = pin.firstLayer(); layer <= pin.lastLayer(); layer++) {
      TileShape pad = pin.getTileShapeOnLayer(layer);
      if (pad == null || !board.layerStructure.layers[layer].isSignal) {
        continue;
      }
      tried = true;
      int full = Math.max(1, netClass.getTraceHalfWidth(layer));
      int neck = pin.getTraceNeckdownHalfwidth(layer);
      neck = neck > 0 ? Math.min(full, neck) : full;
      int clearance =
          board.rules.clearanceMatrix.getValue(clearanceClass, clearanceClass, layer, false);
      IntBox box = pad.boundingBox();
      for (int[] direction : DIRECTIONS) {
        Polyline fullStub = stub(pin, direction, box, full, clearance);
        if (board.checkPolylineTrace(fullStub, layer, full, nets, clearanceClass)) {
          Exit result = new Exit();
          result.exit = "free";
          return result;
        }
        Polyline neckStub = stub(pin, direction, box, neck, clearance);
        if (neck < full && board.checkPolylineTrace(neckStub, layer, neck, nets, clearanceClass)) {
          necked = true;
          continue;
        }
        Set<String> inTheWay = new TreeSet<>();
        blockers(board, pin, neckStub, layer, neck, nets, clearanceClass, inTheWay);
        if (inTheWay.stream().anyMatch(OpenConnections::isDesign)) {
          design.addAll(inTheWay);
        } else {
          movable.addAll(inTheWay);
        }
      }
    }
    if (!tried) {
      return null;
    }
    Exit result = new Exit();
    result.exit = necked ? "necked" : "closed";
    result.byDesign = movable.isEmpty();
    result.closedBy = new ArrayList<>(result.byDesign ? design : movable);
    return result;
  }

  /** Whether a blocker is part of the design rather than something routing or placement moves. */
  private static boolean isDesign(String what) {
    return what.equals("own pads")
        || what.startsWith("fixed copper")
        || what.equals("keepout")
        || what.equals("board edge");
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

  /** Adds a name for each item in the way of stub, as the insert check would find them. */
  private static void blockers(
      BasicBoard board,
      Pin pin,
      Polyline stub,
      int layer,
      int halfWidth,
      int[] nets,
      int clearanceClass,
      Set<String> result) {
    PolylineTrace trace =
        new PolylineTrace(
            stub, layer, halfWidth, nets, clearanceClass, 0, 0, FixedState.UNFIXED, board);
    Set<Pin> contactPins = trace.touchingPinsAtEndCorners();
    for (int i = 0; i < trace.tileShapeCount(); i++) {
      TileShape shape = trace.getTileShape(i);
      if (!shape.isContainedIn(board.getBoundingBox())) {
        result.add("board edge");
      }
      Set<Item> overlaps =
          board.overlappingItemsWithClearance(shape, layer, new int[0], clearanceClass);
      for (Item item : overlaps) {
        if (contactPins.contains(item) || !item.isTraceObstacle(nets[0])) {
          continue;
        }
        result.add(describe(board, item, pin));
      }
    }
  }

  private static String describe(BasicBoard board, Item item, Pin pin) {
    if (item instanceof Pin other) {
      String component = componentName(board, other);
      if (component == null) {
        return "pads";
      }
      return other.getComponentId() == pin.getComponentId() ? "own pads" : "pads of " + component;
    }
    String netName = null;
    if (item.netCount() > 0 && board.rules.nets.get(item.getNetNumber(0)) != null) {
      netName = board.rules.nets.get(item.getNetNumber(0)).name;
    }
    String ofNet = netName == null ? "" : " of net " + netName;
    if (item instanceof ConductionArea) {
      return "copper area" + ofNet;
    }
    if (item instanceof ObstacleArea) {
      return "keepout";
    }
    return (item.isUserFixed() ? "fixed copper" : "routes") + ofNet;
  }

  private static String componentName(BasicBoard board, Pin pin) {
    Component component = board.components.get(pin.getComponentId());
    return component == null ? null : component.name;
  }

  static double toMm(BasicBoard board) {
    return Unit.scale(1.0, board.communication.unit, Unit.MM)
        / (board.communication.resolution > 0 ? board.communication.resolution : 1);
  }

  private static double[] mm(FloatPoint point, double toMm) {
    return new double[] {point.x * toMm, point.y * toMm};
  }

  static String label(BasicBoard board, Item item, FloatPoint corner) {
    if (item instanceof Pin pin) {
      Component component = board.components.get(pin.getComponentId());
      if (component != null && pin.name() != null) {
        return component.name + "-" + pin.name();
      }
    }
    String type = item == null ? "item" : item.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    if (corner == null) {
      return type;
    }
    double toMm = toMm(board);
    return String.format(Locale.ROOT, "%s (%.3f, %.3f)", type, corner.x * toMm, corner.y * toMm);
  }
}
