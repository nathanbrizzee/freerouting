package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.settings.RouterSettings;
import org.junit.jupiter.api.Test;

/**
 * The grid board marks its via {@code (attach off)}. By default the router still allows a via in an
 * own-net SMD pad; with {@code force_via_in_pad=false} it honours the DSN.
 */
class ViaInPadSettingTest {

  @Test
  void theDsnAttachFlagIsHonouredOnlyWhenAsked() {
    RoutingBoard board = GridBoard.board(4, 1000);
    RouterSettings settings = GridBoard.settings(board);
    int net = board.getSmdPins().iterator().next().getNetNumber(0);

    assertTrue(new AutorouteControl(board, net, settings).attachSmdAllowed, "forced by default");

    settings.autorouter.forceViaInPad = false;
    assertFalse(new AutorouteControl(board, net, settings).attachSmdAllowed, "the DSN says off");
  }
}
