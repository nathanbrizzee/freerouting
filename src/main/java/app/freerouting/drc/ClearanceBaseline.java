package app.freerouting.drc;

import app.freerouting.board.model.items.Item;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatLine;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.Line;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.TileShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable input-board violations for conservative result validation. An exemption requires the
 * same item geometry, nets, layer, and clearance measurements; item IDs may change on SES import.
 * Changed pre-existing copper is deliberately rechecked rather than exempted by a count allowance.
 * One change is accepted: a trace that lies along a baseline trace of the same nets, layer and
 * width, which is what a T-join onto pre-existing copper leaves when it splits the trace. Its
 * violation must have the same layer and required clearance, and an actual clearance no smaller
 * than the baseline violation's.
 *
 * <p>Ported from freerouting.net (Erik Gavriluk, Experimental Television Center).
 */
public final class ClearanceBaseline {

  private final Entry[] entries;

  public ClearanceBaseline(Collection<ClearanceViolation> violations) {
    this.entries = capture(violations);
  }

  public int countNewOrChanged(Collection<ClearanceViolation> violations) {
    if (this.entries.length == 0) {
      return violations.size();
    }
    return getNewOrChanged(violations).size();
  }

  /** Violations requiring repair, excluding only exact unchanged input exemptions. */
  public List<ClearanceViolation> getNewOrChanged(Collection<ClearanceViolation> violations) {
    List<ClearanceViolation> result = new ArrayList<>();
    if (this.entries.length == 0) {
      result.addAll(violations);
      return result;
    }
    ClearanceViolation[] currentViolations = violations.toArray(new ClearanceViolation[0]);
    Entry[] currentEntries = capture(violations);
    boolean[] used = new boolean[this.entries.length];
    for (int n = 0; n < currentEntries.length; n++) {
      Entry current = currentEntries[n];
      int match = -1;
      for (int i = 0; i < this.entries.length; i++) {
        if (!used[i] && this.entries[i].matches(current, false)) {
          match = i;
          break;
        }
      }
      if (match >= 0) {
        used[match] = true;
        continue;
      }
      // A piece of a split baseline trace; the pieces share the baseline entry.
      boolean piece = false;
      for (Entry entry : this.entries) {
        if (entry.matches(current, true)) {
          piece = true;
          break;
        }
      }
      if (!piece) {
        result.add(currentViolations[n]);
      }
    }
    return result;
  }

  private static Entry[] capture(Collection<ClearanceViolation> violations) {
    Map<Item, ItemGeometry> cache = new HashMap<>();
    Entry[] result = new Entry[violations.size()];
    int idx = 0;
    for (ClearanceViolation v : violations) {
      ItemGeometry first =
          v.firstItem != null ? cache.computeIfAbsent(v.firstItem, ItemGeometry::new) : null;
      ItemGeometry second =
          v.secondItem != null ? cache.computeIfAbsent(v.secondItem, ItemGeometry::new) : null;
      result[idx++] = new Entry(first, second, v.layer, v.expectedClearance, v.actualClearance);
    }
    return result;
  }

  private static final class Entry {
    private final ItemGeometry first;
    private final ItemGeometry second;
    private final int layer;
    private final double expected;
    private final double actual;

    Entry(ItemGeometry first, ItemGeometry second, int layer, double expected, double actual) {
      this.first = first;
      this.second = second;
      this.layer = layer;
      this.expected = expected;
      this.actual = actual;
    }

    boolean matches(Entry other, boolean allowTracePieces) {
      if (other == null) {
        return false;
      }
      if (this.layer != other.layer || Double.compare(this.expected, other.expected) != 0) {
        return false;
      }
      // A piece of a split trace is part of the old copper, so it is at least as far from the
      // other item as the whole trace was; it may not come closer.
      boolean sameMeasure =
          allowTracePieces
              ? other.actual >= this.actual
              : Double.compare(this.actual, other.actual) == 0;
      if (!sameMeasure) {
        return false;
      }
      return (matchesItems(this.first, other.first, allowTracePieces)
              && matchesItems(this.second, other.second, allowTracePieces))
          || (matchesItems(this.first, other.second, allowTracePieces)
              && matchesItems(this.second, other.first, allowTracePieces));
    }

    private static boolean matchesItems(ItemGeometry a, ItemGeometry b, boolean allowTracePieces) {
      if (a == null) {
        return b == null;
      }
      return a.matches(b) || (allowTracePieces && a.containsTrace(b));
    }
  }

  private static final class ItemGeometry {
    private final String type;
    private final int[] nets;
    private final ShapeGeometry[] shapes;
    private final TraceGeometry trace;

    ItemGeometry(Item item) {
      this.type = item.getClass().getName();
      this.nets = new int[item.netCount()];
      for (int i = 0; i < this.nets.length; i++) {
        this.nets[i] = item.getNetNumber(i);
      }
      Arrays.sort(this.nets);
      this.shapes = new ShapeGeometry[item.tileShapeCount()];
      for (int i = 0; i < this.shapes.length; i++) {
        this.shapes[i] = new ShapeGeometry(item.shapeLayer(i), item.getTileShape(i));
      }
      this.trace =
          item instanceof PolylineTrace polylineTrace ? new TraceGeometry(polylineTrace) : null;
    }

    /** Whether {@code other} is a trace lying along this baseline trace. */
    boolean containsTrace(ItemGeometry other) {
      if (other == null || this.trace == null || other.trace == null) {
        return false;
      }
      return this.type.equals(other.type)
          && Arrays.equals(this.nets, other.nets)
          && this.trace.contains(other.trace);
    }

    boolean matches(ItemGeometry other) {
      if (other == null) {
        return false;
      }
      if (!this.type.equals(other.type) || !Arrays.equals(this.nets, other.nets)) {
        return false;
      }
      if (this.shapes.length != other.shapes.length) {
        return false;
      }
      for (int i = 0; i < this.shapes.length; i++) {
        if (!this.shapes[i].matches(other.shapes[i])) {
          return false;
        }
      }
      return true;
    }
  }

  private static final class TraceGeometry {
    /** Allowed distance of a piece's corners from the baseline centre line, in board units. */
    private static final double TOLERANCE = 2.0;

    private final int layer;
    private final int halfWidth;
    private final FloatPoint[] corners;

    TraceGeometry(PolylineTrace trace) {
      this.layer = trace.getLayer();
      this.halfWidth = trace.getHalfWidth();
      Polyline polyline = trace.polyline();
      this.corners = new FloatPoint[polyline.cornerCount()];
      for (int i = 0; i < this.corners.length; i++) {
        this.corners[i] = polyline.cornerApprox(i);
      }
    }

    /** Every corner and segment midpoint of {@code piece} lies on this centre line. */
    boolean contains(TraceGeometry piece) {
      if (this.layer != piece.layer
          || this.halfWidth != piece.halfWidth
          || this.corners.length < 2
          || piece.corners.length < 2) {
        return false;
      }
      for (int i = 0; i < piece.corners.length; i++) {
        if (!onCentreLine(piece.corners[i])) {
          return false;
        }
        if (i > 0 && !onCentreLine(piece.corners[i - 1].middlePoint(piece.corners[i]))) {
          return false;
        }
      }
      return true;
    }

    private boolean onCentreLine(FloatPoint point) {
      for (int i = 1; i < this.corners.length; i++) {
        if (new FloatLine(this.corners[i - 1], this.corners[i]).segmentDistance(point)
            <= TOLERANCE) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class ShapeGeometry {
    private final int layer;
    private final Point[] corners;

    ShapeGeometry(int layer, TileShape shape) {
      this.layer = layer;
      if (shape == null) {
        this.corners = null;
      } else {
        int count = shape.borderLineCount();
        this.corners = new Point[count * 2];
        for (int i = 0; i < count; i++) {
          Line border = shape.borderLine(i);
          this.corners[i * 2] = border.a;
          this.corners[i * 2 + 1] = border.b;
        }
        Arrays.sort(this.corners, Point::compareXY);
      }
    }

    boolean matches(ShapeGeometry other) {
      if (other == null || this.layer != other.layer) {
        return false;
      }
      if (this.corners == null) {
        return other.corners == null;
      }
      if (other.corners == null || this.corners.length != other.corners.length) {
        return false;
      }
      for (int i = 0; i < this.corners.length; i++) {
        if (!this.corners[i].equals(other.corners[i])) {
          return false;
        }
      }
      return true;
    }
  }
}
