package app.freerouting.autoroute.maze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Component;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** A pin's held exit is chosen toward its net, and a search of another net routes around it. */
class PinAccessTest {

  /**
   * One layer. A keepout from x = 6 to 14 mm and y = 5 to 12 mm leaves a short way above it and a
   * long one below. Pin A1 sits above it at (10, 13.5) mm with its net's other pin A2 below it, so
   * A1's exit points down, across the shortest way for net B from (2, 10) to (18, 10) mm, which
   * passes between the keepout and A1.
   */
  private static final String DSN =
      "(pcb t (resolution um 10) (unit um)\n"
          + "  (structure (layer top (type signal))\n"
          + "    (boundary (rect pcb 0 0 20000 20000)) (via V)"
          + " (keepout \"\" (rect top 6000 5000 14000 12000))"
          + " (rule (width 250) (clearance 200)))\n"
          + "  (placement (component P (place A1 10000 13500 front 0)"
          + " (place A2 10000 3000 front 0)\n"
          + "    (place B1 2000 10000 front 0) (place B2 18000 10000 front 0)))\n"
          + "  (library (image P (pin S 1 0 0))\n"
          + "    (padstack S (shape (rect top -300 -300 300 300)) (attach off))\n"
          + "    (padstack V (shape (circle top 600)) (attach off)))\n"
          + "  (network (net A (pins A1-1 A2-1)) (net B (pins B1-1 B2-1))))\n";

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

  private static Pin pin(RoutingBoard board, String component) {
    for (Pin pin : board.getPins()) {
      Component owner = board.components.get(pin.getComponentId());
      if (owner != null && owner.name.equals(component)) {
        return pin;
      }
    }
    throw new AssertionError("no pin on " + component);
  }

  @Test
  void theExitPointsTowardTheNetAndHoldsOnlyAgainstOtherNets() {
    RoutingBoard board = board();
    int a = net(board, "A");
    PinAccess.Exit exit = PinAccess.chooseExit(board, pin(board, "A1"), a, pin(board, "A2"));
    assertNotNull(exit);
    assertEquals(exit.from().x, exit.to().x, 1, "straight down");
    assertTrue(exit.to().y < exit.from().y, "toward A2");

    PinAccess access = PinAccess.forBoard(board);
    access.refresh();
    FloatPoint left = new FloatPoint(60000, 126000);
    FloatPoint right = new FloatPoint(140000, 126000);
    assertEquals(1, access.crossed(0, net(board, "B"), left, right));
    assertEquals(0, access.crossed(0, a, left, right), "its own net may pass");
    FloatPoint high = new FloatPoint(60000, 150000);
    assertEquals(0, access.crossed(0, net(board, "B"), high, new FloatPoint(140000, 150000)));
    assertTrue(access.describe().startsWith("pin access: 4 of 4 exits held"), access.describe());
  }

  @Test
  void searchPayingForHeldExitsRoutesAroundThem() {
    RoutingBoard board = board();
    PinAccess.Exit exit =
        PinAccess.chooseExit(board, pin(board, "A1"), net(board, "A"), pin(board, "A2"));
    assertTrue(closestApproach(0, exit) < exit.radius(), "without the cost B passes the exit");
    assertTrue(closestApproach(1, exit) >= exit.radius(), "with it B keeps clear");
  }

  /** Routes net B at factor and returns how close its trace comes to the held exit. */
  private static double closestApproach(double factor, PinAccess.Exit exit) {
    RoutingBoard board = board();
    int b = net(board, "B");
    PinAccess access = PinAccess.forBoard(board);
    access.refresh();
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    AutorouteControl ctrl = new AutorouteControl(board, b, settings);
    ctrl.pinAccess = factor > 0 ? access : null;
    ctrl.pinAccessFactor = factor;
    AutorouteEngine engine =
        board.initAutoroute(b, ctrl.traceClearanceClassIndex, null, null, false);
    SortedSet<Item> ripped = new TreeSet<>();
    assertEquals(
        AutorouteAttemptState.ROUTED,
        engine.autorouteConnection(
                Set.of(pin(board, "B1")), Set.of(pin(board, "B2")), ctrl, ripped, null)
            .state);
    double closest = Double.MAX_VALUE;
    for (Item item : board.getConnectableItems(b)) {
      if (item instanceof PolylineTrace trace) {
        FloatPoint[] corners = trace.polyline().cornerApproxArr();
        for (int i = 0; i + 1 < corners.length; i++) {
          closest =
              Math.min(
                  closest,
                  PinAccess.segmentDistance(corners[i], corners[i + 1], exit.from(), exit.to()));
        }
      }
    }
    return closest;
  }
}
