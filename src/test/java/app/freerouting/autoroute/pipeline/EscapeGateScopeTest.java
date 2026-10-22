package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.settings.RouterSettings;
import org.junit.jupiter.api.Test;

/**
 * With {@code min_escape_by_pitch_grid_only} and {@code protect_escapes_grid_only}, the two escape
 * gates act on the balls of detected pin grids and leave every other pin as without the gate.
 */
class EscapeGateScopeTest {

  private static String fannedOut(
      int grid,
      boolean byPitch,
      boolean byPitchGridOnly,
      boolean protect,
      boolean protectGridOnly) {
    RoutingBoard board = GridBoard.board(grid, 800);
    RouterSettings settings = GridBoard.settings(board);
    settings.fanout.minEscapeByPitch = byPitch;
    settings.fanout.minEscapeByPitchGridOnly = byPitchGridOnly;
    settings.fanout.protectEscapes = protect;
    settings.fanout.protectEscapesGridOnly = protectGridOnly;
    BatchFanout.fanoutBoard(board, settings, null);
    return board.getHash();
  }

  @Test
  void gridBallsKeepTheGates() {
    String everyPin = fannedOut(8, true, false, true, false);
    assertNotEquals(
        fannedOut(8, false, false, false, false), everyPin, "the gates change the 8 x 8 fanout");
    assertEquals(
        everyPin, fannedOut(8, true, true, true, true), "every pad of the 8 x 8 grid is a ball");
  }

  @Test
  void padsOutsideGridsFanOutAsWithoutTheGates() {
    // 3 x 3 is below the smallest grid the planner detects.
    String everyPin = fannedOut(3, true, false, true, false);
    String without = fannedOut(3, false, false, false, false);
    assertNotEquals(without, everyPin, "the gates change the 3 x 3 fanout");
    assertEquals(without, fannedOut(3, true, true, true, true));
  }

  @Test
  void eachGateIsScopedOnItsOwn() {
    // Pitch window on grid balls only, protection on every pin: as with protection alone.
    assertEquals(fannedOut(3, false, false, true, false), fannedOut(3, true, true, true, false));
    // Protection on grid balls only, pitch window on every pin: as with the window alone.
    assertEquals(fannedOut(3, true, false, false, false), fannedOut(3, true, false, true, true));
  }
}
