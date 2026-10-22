package app.freerouting.autoroute.maze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The rip history counts ripped items from the next pass on, and a search that pays for it rips
 * elsewhere than where earlier passes fought.
 */
class RipHistoryTest {

  /**
   * One layer. Net A runs from (2 mm, 10 mm) to (18 mm, 10 mm) past a keepout from x = 6 to 14 mm
   * and y = 7 to 13 mm. The corridor above it is closed by net B's trace between two pads at x = 10
   * mm, the one below by net C's, so A must rip one of them.
   */
  private static final String DSN =
      "(pcb t (resolution um 10) (unit um)\n"
          + "  (structure (layer top (type signal))\n"
          + "    (boundary (rect pcb 0 0 20000 20000)) (via V)"
          + " (keepout \"\" (rect top 6000 7000 14000 13000))"
          + " (rule (width 250) (clearance 200)))\n"
          + "  (placement (component P (place A1 2000 10000 front 0)"
          + " (place A2 18000 10000 front 0)\n"
          + "    (place B1 10000 13600 front 0) (place B2 10000 19300 front 0)\n"
          + "    (place C1 10000 700 front 0) (place C2 10000 6400 front 0)))\n"
          + "  (library (image P (pin S 1 0 0))\n"
          + "    (padstack S (shape (circle top 600)) (attach off))\n"
          + "    (padstack V (shape (circle top 600)) (attach off)))\n"
          + "  (network (net A (pins A1-1 A2-1)) (net B (pins B1-1 B2-1))"
          + " (net C (pins C1-1 C2-1)))\n"
          + "  (wiring (wire (path top 250 10000 13600 10000 19300) (net B))"
          + " (wire (path top 250 10000 700 10000 6400) (net C))))\n";

  private static RoutingBoard board() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "t");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private static int net(RoutingBoard board, String name) {
    return board.rules.nets.get(name, 1).netNumber;
  }

  private static PolylineTrace trace(RoutingBoard board, String net) {
    for (Item item : board.getConnectableItems(net(board, net))) {
      if (item instanceof PolylineTrace trace) {
        return trace;
      }
    }
    throw new AssertionError("no trace on net " + net);
  }

  @Test
  void countsRipsFromTheNextPassOn() {
    RoutingBoard board = board();
    RipHistory history = RipHistory.forBoard(board);
    PolylineTrace b = trace(board, "B");
    history.recordRipped(b);
    assertEquals(0, history.over(b), "not before the pass ends");
    history.endPass();
    assertEquals(1, history.over(b));
    assertEquals(0, history.over(trace(board, "C")));

    // A move across B's trace passes one hot cell among cold ones.
    double across = history.along(0, new FloatPoint(60000, 160000), new FloatPoint(140000, 160000));
    assertTrue(across > 0 && across < 1, "mean along the move: " + across);

    history.recordRipped(b);
    history.endPass();
    assertEquals(2, history.over(b), "rips add up");
  }

  @Test
  void searchPayingForTheHistoryRipsTheOtherNet() {
    String first = ripped(0, null);
    String other = "B".equals(first) ? "C" : "B";
    assertEquals(first, ripped(0, first), "the history costs nothing at factor 0");
    assertEquals(other, ripped(1, first), "with history it rips the other net");
  }

  /**
   * Routes net A with one earlier rip of {@code earlier}'s trace (none when null) at {@code factor}
   * and returns the net it ripped.
   */
  private static String ripped(double factor, String earlier) {
    RoutingBoard board = board();
    RipHistory history = RipHistory.forBoard(board);
    if (earlier != null) {
      history.recordRipped(trace(board, earlier));
    }
    history.endPass();
    int a = net(board, "A");
    List<Pin> pins = new ArrayList<>();
    for (Pin pin : board.getPins()) {
      if (pin.containsNet(a)) {
        pins.add(pin);
      }
    }
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    AutorouteControl ctrl = new AutorouteControl(board, a, settings);
    ctrl.ripupAllowed = true;
    ctrl.ripupCosts = 100;
    ctrl.ripHistory = history;
    ctrl.ripHistoryFactor = factor;
    AutorouteEngine engine =
        board.initAutoroute(a, ctrl.traceClearanceClassIndex, null, null, false);
    SortedSet<Item> rippedItems = new TreeSet<>();
    assertEquals(
        AutorouteAttemptState.ROUTED,
        engine.autorouteConnection(
                Set.of(pins.get(0)), Set.of(pins.get(1)), ctrl, rippedItems, null)
            .state);
    assertEquals(1, rippedItems.size(), "one trace in the way: " + rippedItems);
    return rippedItems.first().containsNet(net(board, "B")) ? "B" : "C";
  }
}
