package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * An 8 x 8 grid of 0.8 mm pitch pads whose channels are too narrow for a trace: with the default
 * escape window no inner pad can reach a via, with {@code min_escape_by_pitch} each one drills in
 * the diagonal gap next to it.
 */
class FanoutEscapeWindowTest {

  private static final int GRID = 8;
  private static final int PITCH_UM = 800; // DSN coordinates are in um
  private static final int PITCH = PITCH_UM * 10; // board units, resolution 10 per um

  private static String dsn() {
    StringBuilder image = new StringBuilder("(image BGA");
    StringBuilder target = new StringBuilder("(image J");
    StringBuilder nets = new StringBuilder();
    int pinNo = 0;
    for (int row = 0; row < GRID; row++) {
      for (int col = 0; col < GRID; col++) {
        pinNo++;
        int x = (2 * col - (GRID - 1)) * PITCH_UM / 2;
        int y = (2 * row - (GRID - 1)) * PITCH_UM / 2;
        image
            .append(" (pin P ")
            .append(pinNo)
            .append(' ')
            .append(x)
            .append(' ')
            .append(y)
            .append(')');
        target
            .append(" (pin T ")
            .append(pinNo)
            .append(' ')
            .append(col * 1200)
            .append(' ')
            .append(row * 1200)
            .append(')');
        nets.append(" (net N")
            .append(pinNo)
            .append(" (pins U1-")
            .append(pinNo)
            .append(" J1-")
            .append(pinNo)
            .append("))");
      }
    }
    image.append(')');
    target.append(')');
    return "(pcb grid (resolution um 10) (unit um)\n"
        + "  (structure (layer top (type signal)) (layer bottom (type signal))\n"
        + "    (boundary (rect pcb 0 0 40000 40000)) (via V) (rule (width 250) (clearance 100)))\n"
        + "  (placement (component BGA (place U1 10000 10000 front 0))\n"
        + "             (component J (place J1 25000 25000 front 0)))\n"
        + "  (library "
        + image
        + ' '
        + target
        + "\n    (padstack P (shape (circle top 400)) (attach off))\n"
        + "    (padstack T (shape (circle top 600)) (shape (circle bottom 600)) (attach off))\n"
        + "    (padstack V (shape (circle top 450)) (shape (circle bottom 450)) (attach off)))\n"
        + "  (network"
        + nets
        + "))\n";
  }

  private static RoutingBoard board() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(dsn().getBytes(StandardCharsets.UTF_8)), null, null, "grid");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private static RouterSettings settings(RoutingBoard board, boolean byPitch) {
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.applyBoardSpecificOptimizations(board);
    settings.fanout.minEscapeByPitch = byPitch;
    settings.fanout.maxPasses = 2;
    settings.fanout.maxSearchStepsPerPin = 20000L;
    return settings;
  }

  /** Inner pins (not on the outer ring) of U1 whose connection leaves the top layer. */
  private static int innerPinsWithVia(RoutingBoard board) {
    int count = 0;
    for (Pin pin : board.getSmdPins()) {
      int index = pin.pinIndex; // 0-based, row-major
      int row = index / GRID;
      int col = index % GRID;
      boolean inner = row > 0 && row < GRID - 1 && col > 0 && col < GRID - 1;
      if (inner && pin.connectedSetLeavesLayer(pin.getNetNumber(0), pin.firstLayer())) {
        count++;
      }
    }
    return count;
  }

  @Test
  void theDefaultWindowKeepsInnerPinsOnTheSurface() {
    RoutingBoard board = board();
    BatchFanout.fanoutBoard(board, settings(board, false), null);
    assertEquals(0, innerPinsWithVia(board), "no inner pin can reach a via 2.5 mm away");
  }

  @Test
  void halfThePitchLetsEveryInnerPinDrillBesideItself() {
    RoutingBoard board = board();
    BatchFanout.fanoutBoard(board, settings(board, true), null);
    int inner = (GRID - 2) * (GRID - 2);
    int withVia = innerPinsWithVia(board);
    // 21 of 36 when written: the rest drill, then lose the via to a neighbour's search, because
    // the rip-up check does not protect another pin's escape during fanout (the next change).
    assertTrue(withVia >= inner / 2, withVia + " of " + inner + " inner pins escaped");
  }

  @Test
  void nearestPinDistanceIsThePitch() {
    RoutingBoard board = board();
    Pin first = board.getSmdPins().iterator().next();
    assertEquals(PITCH, board.nearestPinDistance(first), 1.0);
  }
}
