package app.freerouting.autoroute;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.rules.Net;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Connections the design itself blocks. A pin is blocked when, on every layer it has copper, fixed
 * copper (a pin, a fixed trace or via, a conduction area) of another net or of no net either
 * overlaps it and comes within clearance of its centre, or stays clear of it but comes within
 * clearance of every corner of its pad for the narrowest trace the router uses: any trace leaving
 * it breaks clearance.
 *
 * <p>Ported from freerouting.net (Erik Gavriluk, Experimental Television Center).
 */
public final class DesignBlocks {

  private DesignBlocks() {}

  /**
   * Whether every copper layer of pin overlaps foreign fixed copper, or the rules check proved that
   * no practical trace can reach it.
   */
  public static boolean isBlocked(Pin pin) {
    return isRuleBlocked(pin) || (blockingItems(pin) != null && !blockingItems(pin).isEmpty());
  }

  /** Whether the rules check proved that no practical trace can reach pin. */
  public static boolean isRuleBlocked(Pin pin) {
    return pin.board != null
        && pin.board.ruleBlockedPins != null
        && pin.board.ruleBlockedPins.contains(pin.getId());
  }

  /**
   * The foreign fixed items pin overlaps, or null when some layer of the pin is free of them (or
   * the pin has no copper).
   */
  public static List<Item> blockingItems(Pin pin) {
    Set<Integer> pinLayers = new HashSet<>();
    for (int i = 0; i < pin.tileShapeCount(); i++) {
      pinLayers.add(pin.shapeLayer(i));
    }
    if (pinLayers.isEmpty()) {
      return null;
    }
    Set<Integer> blockedLayers = new HashSet<>();
    List<Item> blockers = new ArrayList<>();
    for (ClearanceViolation v : pin.clearanceViolations()) {
      Item other = (v.firstItem == pin) ? v.secondItem : v.firstItem;
      if (other.isRoutable()
          || !isCopper(other)
          || other.sharesNet(pin)
          || !(v.actualClearance > 0
              ? coversPad(pin, other, v.layer, v.expectedClearance)
              : coversCentre(pin, other, v.layer, v.expectedClearance))) {
        continue;
      }
      blockedLayers.add(v.layer);
      if (!blockers.contains(other)) {
        blockers.add(other);
      }
    }
    return blockedLayers.containsAll(pinLayers) ? blockers : null;
  }

  /** A connected set of pins only (no traces, vias or areas), every one of them blocked. */
  public static boolean isBlockedSet(Collection<Item> connectedSet) {
    if (connectedSet == null || connectedSet.isEmpty()) {
      return false;
    }
    for (Item item : connectedSet) {
      if (!(item instanceof Pin pin) || !isBlocked(pin)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the open connections of item's connected set on netNumber are all blocked by the
   * design: the set itself is blocked, or every other connected set of the net is.
   */
  public static boolean connectionsBlocked(BasicBoard board, Item item, int netNumber) {
    Set<Item> ownSet = item.getConnectedSet(netNumber);
    if (isBlockedSet(ownSet)) {
      return true;
    }
    Set<Item> seen = new HashSet<>(ownSet);
    boolean sawOtherSet = false;
    for (Item netItem : board.getConnectableItems(netNumber)) {
      if (seen.contains(netItem)) {
        continue;
      }
      Set<Item> otherSet = netItem.getConnectedSet(netNumber);
      seen.addAll(otherSet);
      if (!isBlockedSet(otherSet)) {
        return false;
      }
      sawOtherSet = true;
    }
    return sawOtherSet;
  }

  /** The airlines (open connections) with a blocked end. */
  public static List<AirLine> blockedAirlines(Collection<AirLine> airlines) {
    List<AirLine> result = new ArrayList<>();
    for (AirLine a : airlines) {
      if (a != null
          && a.net != null
          && (endBlocked(a.fromItem, a.net.netNumber) || endBlocked(a.toItem, a.net.netNumber))) {
        result.add(a);
      }
    }
    return result;
  }

  private static boolean endBlocked(Item item, int netNumber) {
    return item instanceof Pin pin
        && isBlocked(pin)
        && isBlockedSet(item.getConnectedSet(netNumber));
  }

  private static boolean coversCentre(Pin pin, Item other, int layer, double clearance) {
    FloatPoint centre = pin.getCenter().toFloat();
    int halfWidth = 0;
    if (pin.netCount() > 0
        && pin.board != null
        && pin.board.rules.nets.get(pin.getNetNumber(0)) != null) {
      Net net = pin.board.rules.nets.get(pin.getNetNumber(0));
      halfWidth = net.getNetClass().getTraceHalfWidth(layer);
    }
    for (int i = 0; i < other.tileShapeCount(); i++) {
      if (other.shapeLayer(i) == layer && other.getTileShape(i) != null) {
        TileShape shape = other.getTileShape(i);
        if (shape.distance(centre) < clearance + halfWidth) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether fixed copper that does not overlap pin still comes within clearance of every point of
   * its pad on layer, for the narrowest trace the router attaches with: a trace attaches inside the
   * pad, and the distance to a convex shape is largest at a corner of the pad.
   */
  private static boolean coversPad(Pin pin, Item other, int layer, double clearance) {
    double reach = clearance + narrowestTraceHalfWidth(pin, layer);
    boolean padSeen = false;
    for (int i = 0; i < pin.tileShapeCount(); i++) {
      if (pin.shapeLayer(i) != layer || pin.getTileShape(i) == null) {
        continue;
      }
      TileShape pad = pin.getTileShape(i);
      padSeen = true;
      for (int c = 0; c < pad.borderLineCount(); c++) {
        if (!within(other, layer, pad.cornerApprox(c), reach)) {
          return false;
        }
      }
    }
    return padSeen;
  }

  private static boolean within(Item other, int layer, FloatPoint point, double distance) {
    for (int i = 0; i < other.tileShapeCount(); i++) {
      if (other.shapeLayer(i) == layer
          && other.getTileShape(i) != null
          && other.getTileShape(i).distance(point) < distance) {
        return true;
      }
    }
    return false;
  }

  /**
   * The narrowest trace that can attach to pin on layer: the pin neck-down, half the net class
   * width (the insert's smallest micro neck-down), or the routing neck when one is set, never below
   * the board's minimum.
   */
  private static int narrowestTraceHalfWidth(Pin pin, int layer) {
    int result = pin.getTraceNeckdownHalfwidth(layer);
    if (pin.netCount() > 0
        && pin.board != null
        && pin.board.rules.nets.get(pin.getNetNumber(0)) != null) {
      int classHalfWidth =
          pin.board.rules.nets.get(pin.getNetNumber(0)).getNetClass().getTraceHalfWidth(layer);
      if (classHalfWidth > 0) {
        result = Math.min(result, Math.max(1, classHalfWidth / 2));
      }
    }
    if (pin.board != null && pin.board.neckHalfWidth > 0) {
      result = Math.min(result, pin.board.neckHalfWidth);
    }
    int floor = pin.board != null ? pin.board.rules.getMinAllowedTraceHalfWidth() : 0;
    return Math.max(result, floor);
  }

  private static boolean isCopper(Item item) {
    return item instanceof Pin
        || item instanceof Trace
        || item instanceof Via
        || item instanceof ConductionArea;
  }
}
