package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.settings.RouterSettings;
import org.junit.jupiter.api.Test;

/**
 * The via span cost prices the layers a via drills beyond the shortest via of the rule, and is off
 * by default.
 */
class ViaSpanCostTest {

  @Test
  void isZeroByDefaultAndGrowsWithTheSpan() {
    RoutingBoard board = GridBoard.board(4, 1000);
    RouterSettings settings = GridBoard.settings(board);
    int net = board.getSmdPins().iterator().next().getNetNumber(0);

    AutorouteControl off = new AutorouteControl(board, net, settings);
    assertEquals(0, off.viaSpanCostFactor);
    assertEquals(0, off.viaSpanCost(0, 7));

    settings.autorouter.viaSpanCostFactor = 0.5;
    AutorouteControl on = new AutorouteControl(board, net, settings);
    assertTrue(on.minNormalViaCost > 0, "a via has a flat cost");
    assertEquals(0, on.viaSpanCost(0, 1), "two layers: the flat cost only");
    assertEquals(0.5 * on.minNormalViaCost, on.viaSpanCost(0, 2), 1e-9);
    assertEquals(3.0 * on.minNormalViaCost, on.viaSpanCost(1, 8), 1e-9);
  }

  @Test
  void throughViasPayNothingWithoutShorterVias() {
    RoutingBoard board = GridBoard.board(GridBoard.dsn(4, 1000, 4, 250, new int[0][]));
    RouterSettings settings = GridBoard.settings(board);
    settings.autorouter.viaSpanCostFactor = 0.5;
    int net = board.getSmdPins().iterator().next().getNetNumber(0);

    AutorouteControl ctrl = new AutorouteControl(board, net, settings);
    assertEquals(0, ctrl.viaSpanCost(0, 3), "the only via is the through via");
  }

  @Test
  void throughViasPayForTheLayersBeyondABlindVia() {
    RoutingBoard board = GridBoard.board(GridBoard.dsn(4, 1000, 4, 250, new int[][] {{0, 1}}));
    RouterSettings settings = GridBoard.settings(board);
    settings.autorouter.viaSpanCostFactor = 0.5;
    int net = board.getSmdPins().iterator().next().getNetNumber(0);

    AutorouteControl ctrl = new AutorouteControl(board, net, settings);
    assertEquals(0, ctrl.viaSpanCost(0, 1));
    assertEquals(1.0 * ctrl.minNormalViaCost, ctrl.viaSpanCost(0, 3), 1e-9);
  }
}
