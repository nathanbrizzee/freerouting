package app.freerouting.autoroute.maze;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.rules.NetClass;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where connections have fought over space: a coarse grid per layer that counts the items ripped
 * there in earlier passes. The maze search makes a move through those cells, and ripping an item in
 * them, cost more by {@code 1 + factor * history}, so a connection that has another way takes it
 * and the pair stops ripping each other back and forth (negotiated congestion's history cost, with
 * rip-ups as the overuse). The rips of a pass count from the next pass on.
 *
 * <p>Deterministic: the grid and the counts depend only on the board and the rips.
 */
public final class RipHistory {

  /** Cell side in trace pitches (trace width plus clearance of the default net class). */
  static final int CELL_PITCHES = 4;

  private final int layerCount;
  private final int cols;
  private final int rows;
  private final double cell;
  private final double originX;
  private final double originY;
  private final float[] history;
  private final float[] pending;
  private int cellsHit;
  private double maxValue;

  RipHistory(IntBox box, int layerCount, double cell) {
    this.layerCount = layerCount;
    this.cell = Math.max(1.0, cell);
    this.originX = box.ll.x;
    this.originY = box.ll.y;
    this.cols = Math.max(1, (int) Math.ceil(box.width() / this.cell) + 1);
    this.rows = Math.max(1, (int) Math.ceil(box.height() / this.cell) + 1);
    this.history = new float[layerCount * cols * rows];
    this.pending = new float[history.length];
  }

  /** A history for {@code board}, its cells {@link #CELL_PITCHES} default trace pitches wide. */
  public static RipHistory forBoard(RoutingBoard board) {
    NetClass netClass = board.rules.getDefaultNetClass();
    int halfWidth = Math.max(1, netClass.getTraceHalfWidth(0));
    int clearance =
        board.rules.clearanceMatrix.getValue(
            netClass.getTraceClearanceClass(), netClass.getTraceClearanceClass(), 0, false);
    double pitch = 2.0 * halfWidth + Math.max(0, clearance);
    return new RipHistory(board.getBoundingBox(), board.getLayerCount(), CELL_PITCHES * pitch);
  }

  /** Counts {@code item}, ripped in this pass, in the cells it covers; it counts from next pass. */
  public void recordRipped(Item item) {
    for (int index : cells(item)) {
      this.pending[index] += 1;
    }
  }

  /** Adds this pass's rips to the history. */
  public void endPass() {
    for (int i = 0; i < this.history.length; i++) {
      if (this.pending[i] > 0) {
        if (this.history[i] == 0) {
          this.cellsHit++;
        }
        this.history[i] += this.pending[i];
        this.maxValue = Math.max(this.maxValue, this.history[i]);
        this.pending[i] = 0;
      }
    }
  }

  /** Mean history along a move on {@code layer}, sampled every half cell. */
  public double along(int layer, FloatPoint from, FloatPoint to) {
    int samples = Math.max(1, (int) Math.ceil(2 * from.distance(to) / this.cell));
    double sum = 0;
    for (int s = 0; s <= samples; s++) {
      double t = (double) s / samples;
      sum += at(layer, from.x + t * (to.x - from.x), from.y + t * (to.y - from.y));
    }
    return sum / (samples + 1);
  }

  /** The highest history of the cells {@code item} covers. */
  public double over(Item item) {
    double result = 0;
    for (int index : cells(item)) {
      result = Math.max(result, this.history[index]);
    }
    return result;
  }

  /** One phrase for the pass log. */
  public String describe() {
    return String.format(
        Locale.US,
        "rip history in %d of %d cells, at most %.0f",
        this.cellsHit,
        this.history.length,
        this.maxValue);
  }

  private double at(int layer, double x, double y) {
    int index = index(layer, x, y);
    return index < 0 ? 0 : this.history[index];
  }

  private int index(int layer, double x, double y) {
    if (layer < 0 || layer >= this.layerCount) {
      return -1;
    }
    int col = (int) Math.floor((x - this.originX) / this.cell);
    int row = (int) Math.floor((y - this.originY) / this.cell);
    if (col < 0 || row < 0 || col >= this.cols || row >= this.rows) {
      return -1;
    }
    return (layer * this.rows + row) * this.cols + col;
  }

  /** The cells under a trace's segments, a drill item's centre, or another item's box. */
  private Set<Integer> cells(Item item) {
    Set<Integer> result = new HashSet<>();
    for (int layer = item.firstLayer(); layer <= item.lastLayer(); layer++) {
      if (item instanceof PolylineTrace trace) {
        FloatPoint[] corners = trace.polyline().cornerApproxArr();
        for (int i = 0; i + 1 < corners.length; i++) {
          addSegment(result, layer, corners[i], corners[i + 1]);
        }
      } else if (item instanceof DrillItem drill) {
        FloatPoint centre = drill.getCenter().toFloat();
        addIndex(result, index(layer, centre.x, centre.y));
      } else {
        IntBox box = item.boundingBox();
        addSegment(result, layer, box.ll.toFloat(), box.ur.toFloat());
      }
    }
    return result;
  }

  private void addSegment(Set<Integer> result, int layer, FloatPoint a, FloatPoint b) {
    double length = a.distance(b);
    int samples = Math.max(1, (int) Math.ceil(2 * length / this.cell));
    for (int s = 0; s <= samples; s++) {
      double t = (double) s / samples;
      addIndex(result, index(layer, a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)));
    }
  }

  private static void addIndex(Set<Integer> result, int index) {
    if (index >= 0) {
      result.add(index);
    }
  }
}
