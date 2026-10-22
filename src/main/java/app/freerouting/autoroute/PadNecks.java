package app.freerouting.autoroute;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.ObstacleArea;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Necks below the pad width for pins the design closes in at it. A trace narrows to a pin's
 * neck-down width near the pin (in the maze search's pin rooms and the insert's last segments), and
 * that width is the pad's narrow side, so a pin whose neighbours sit closer than the clearance to a
 * pad-wide stub could never be left. For such pins, the widest neck in 10% steps that leaves the
 * pad clear of the design (pads, fixed copper, keepouts, the board edge) in one of eight directions
 * becomes the pin's neck, never below the floor: the board's minimum trace width when one is set,
 * else half the net class width. Only the stretch near the pin is necked; the rest of the
 * connection keeps its width.
 *
 * <p>Deterministic: the necks depend only on the board when the routing stage starts.
 */
public final class PadNecks {

  private static final int[][] DIRECTIONS = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private PadNecks() {}

  /** One pin given a neck below its pad width, in millimetres. */
  public record Neck(
      String pin, String net, double classWidthMm, double padWidthMm, double neckMm) {
    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "%s (%s) %.3f -> %.3f mm (pad %.3f mm)",
          pin,
          net,
          classWidthMm,
          neckMm,
          padWidthMm);
    }
  }

  /**
   * Gives the pins of board that the design closes in at their pad width a narrower neck, and
   * returns them sorted by pin name. Pins needing a neck below the floor keep their pad width.
   */
  public static List<Neck> plan(BasicBoard board) {
    return plan(board, board.getSmdPins());
  }

  /** As {@link #plan(BasicBoard)}, for the SMD pins among candidates only. */
  public static List<Neck> plan(BasicBoard board, Collection<Pin> candidates) {
    List<Neck> result = new ArrayList<>();
    List<Pin> pins = new ArrayList<>();
    for (Pin pin : candidates) {
      if (pin.firstLayer() == pin.lastLayer()) {
        pins.add(pin);
      }
    }
    pins.sort(Comparator.comparingInt(Item::getId));
    double toMm =
        Unit.scale(1.0, board.communication.unit, Unit.MM)
            / (board.communication.resolution > 0 ? board.communication.resolution : 1);
    for (Pin pin : pins) {
      pin.setAccessNeckHalfWidth(0);
      if (pin.netCount() != 1 || board.getConnectableItems(pin.getNetNumber(0)).size() < 2) {
        continue;
      }
      Net net = board.rules.nets.get(pin.getNetNumber(0));
      if (net == null || net.getNetClass() == null) {
        continue;
      }
      NetClass netClass = net.getNetClass();
      int layer = pin.firstLayer();
      TileShape pad = pin.getTileShapeOnLayer(layer);
      if (pad == null) {
        continue;
      }
      int full = Math.max(1, netClass.getTraceHalfWidth(layer));
      int start = Math.min(full, pin.getTraceNeckdownHalfwidth(layer));
      int clearanceClass = netClass.getTraceClearanceClass();
      int clearance =
          board.rules.clearanceMatrix.getValue(clearanceClass, clearanceClass, layer, false);
      IntBox box = pad.boundingBox();
      int[] nets = {pin.getNetNumber(0)};
      if (leaves(board, pin, layer, start, clearance, clearanceClass, nets, box)) {
        continue;
      }
      int floor =
          board.rules.getMinAllowedTraceHalfWidth() > 0
              ? board.rules.getMinAllowedTraceHalfWidth()
              : Math.max(1, full / 2);
      for (int halfWidth = (int) (start * 0.9);
          halfWidth >= floor && halfWidth > 0;
          halfWidth = (int) (halfWidth * 0.9)) {
        if (leaves(board, pin, layer, halfWidth, clearance, clearanceClass, nets, box)) {
          pin.setAccessNeckHalfWidth(halfWidth);
          Component component = board.components.get(pin.getComponentId());
          String name = (component != null ? component.name : "pin") + "-" + pin.name();
          result.add(
              new Neck(
                  name,
                  net.name,
                  2 * full * toMm,
                  pin.getMinWidth(layer) * toMm,
                  2 * halfWidth * toMm));
          break;
        }
      }
    }
    result.sort(Comparator.comparing(Neck::pin));
    return result;
  }

  /** One log line naming at most limit of the planned necks. */
  public static String describe(List<Neck> necks, int limit) {
    StringBuilder line =
        new StringBuilder(
            "Pad necks: "
                + necks.size()
                + " pin(s) at open connections that the design closes in at their pad width may"
                + " neck narrower near the pad: ");
    for (int i = 0; i < Math.min(limit, necks.size()); i++) {
      line.append(i > 0 ? "; " : "").append(necks.get(i));
    }
    if (necks.size() > limit) {
      line.append("; and ").append(necks.size() - limit).append(" more");
    }
    return line.append('.').toString();
  }

  /**
   * The necks the routed board uses: for every pin with a planned neck, the narrowest trace on it
   * that is narrower than the pad, for review against the current its net carries.
   */
  public static List<String> used(BasicBoard board) {
    List<String> result = new ArrayList<>();
    double toMm =
        Unit.scale(1.0, board.communication.unit, Unit.MM)
            / (board.communication.resolution > 0 ? board.communication.resolution : 1);
    List<Pin> pins = new ArrayList<>(board.getSmdPins());
    pins.sort(Comparator.comparingInt(Item::getId));
    for (Pin pin : pins) {
      if (pin.getAccessNeckHalfWidth() <= 0 || pin.netCount() != 1) {
        continue;
      }
      int layer = pin.firstLayer();
      int narrowest = Integer.MAX_VALUE;
      for (Item contact : pin.getNormalContacts()) {
        if (contact instanceof Trace trace) {
          narrowest = Math.min(narrowest, trace.getHalfWidth());
        }
      }
      if (2.0 * narrowest >= pin.getMinWidth(layer)) {
        continue;
      }
      Net net = board.rules.nets.get(pin.getNetNumber(0));
      Component component = board.components.get(pin.getComponentId());
      result.add(
          String.format(
              Locale.ROOT,
              "%s-%s (%s) %.3f mm trace on a %.3f mm pad, class width %.3f mm",
              component != null ? component.name : "pin",
              pin.name(),
              net != null ? net.name : "?",
              2 * narrowest * toMm,
              pin.getMinWidth(layer) * toMm,
              net != null ? 2 * net.getNetClass().getTraceHalfWidth(layer) * toMm : 0));
    }
    result.sort(String::compareTo);
    return result;
  }

  /** Whether a stub of halfWidth leaves pin in some direction with only routes in its way. */
  static boolean leaves(
      BasicBoard board,
      Pin pin,
      int layer,
      int halfWidth,
      int clearance,
      int clearanceClass,
      int[] nets,
      IntBox pad) {
    for (int[] direction : DIRECTIONS) {
      Polyline stub = stub(pin, direction, pad, halfWidth, clearance);
      if (board.checkPolylineTrace(stub, layer, halfWidth, nets, clearanceClass)
          || !designInTheWay(board, stub, layer, halfWidth, clearanceClass, nets)) {
        return true;
      }
    }
    return false;
  }

  private static boolean designInTheWay(
      BasicBoard board, Polyline stub, int layer, int halfWidth, int clearanceClass, int[] nets) {
    PolylineTrace trace =
        new PolylineTrace(
            stub, layer, halfWidth, nets, clearanceClass, 0, 0, FixedState.UNFIXED, board);
    Set<Pin> contactPins = trace.touchingPinsAtEndCorners();
    for (int i = 0; i < trace.tileShapeCount(); i++) {
      TileShape shape = trace.getTileShape(i);
      if (!shape.isContainedIn(board.getBoundingBox())) {
        return true;
      }
      Set<Item> overlaps =
          board.overlappingItemsWithClearance(shape, layer, new int[0], clearanceClass);
      for (Item item : overlaps) {
        if (contactPins.contains(item) || !item.isTraceObstacle(nets[0])) {
          continue;
        }
        if (item instanceof Pin
            || item.isUserFixed()
            || (item instanceof ObstacleArea && !(item instanceof ConductionArea))) {
          return true;
        }
      }
    }
    return false;
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
}
