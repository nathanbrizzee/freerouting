package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.settings.RouterSettings;
import org.junit.jupiter.api.Test;

/**
 * With {@code grid_balls_only}, {@code min_escape_by_pitch} and {@code protect_escapes} act on the
 * balls of detected pin grids and leave every other pin as without the gates.
 */
class EscapeGateScopeTest {

  private static String fannedOut(int grid, boolean gates, boolean gridBallsOnly) {
    RoutingBoard board = GridBoard.board(grid, 800);
    RouterSettings settings = GridBoard.settings(board);
    settings.fanout.minEscapeByPitch = gates;
    settings.fanout.protectEscapes = gates;
    settings.fanout.gridBallsOnly = gridBallsOnly;
    BatchFanout.fanoutBoard(board, settings, null);
    return board.getHash();
  }

  @Test
  void gridBallsKeepTheGates() {
    String everyPin = fannedOut(8, true, false);
    assertNotEquals(fannedOut(8, false, false), everyPin, "the gates change the 8 x 8 fanout");
    assertEquals(everyPin, fannedOut(8, true, true), "every pad of the 8 x 8 grid is a ball");
  }

  @Test
  void padsOutsideGridsFanOutAsWithoutTheGates() {
    // 3 x 3 is below the smallest grid the planner detects.
    String everyPin = fannedOut(3, true, false);
    String without = fannedOut(3, false, false);
    assertNotEquals(without, everyPin, "the gates change the 3 x 3 fanout");
    assertEquals(without, fannedOut(3, true, true));
  }
}
