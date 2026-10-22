package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * A two-layer test board with a square grid of round SMD pads (component U1, pins numbered row by
 * row from 1) whose channels are too narrow for a trace, each wired to a through-hole pin of a
 * connector J1 placed well away. Pad 400 um, via 450 um, trace 250 um, clearance 100 um, so a via
 * fits only in the diagonal gap between four pads (pitch x 0.707 >= 200 + 100 + 225).
 */
final class GridBoard {

  private GridBoard() {}

  /** Board units per um: the DSN says {@code (resolution um 10)}. */
  static final int UNITS_PER_UM = 10;

  static String dsn(int grid, int pitchUm) {
    StringBuilder image = new StringBuilder("(image BGA");
    StringBuilder target = new StringBuilder("(image J");
    StringBuilder nets = new StringBuilder();
    int pinNo = 0;
    for (int row = 0; row < grid; row++) {
      for (int col = 0; col < grid; col++) {
        pinNo++;
        int x = (2 * col - (grid - 1)) * pitchUm / 2;
        int y = (2 * row - (grid - 1)) * pitchUm / 2;
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

  static RoutingBoard board(int grid, int pitchUm) {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(dsn(grid, pitchUm).getBytes(StandardCharsets.UTF_8)),
            null,
            null,
            "grid");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  /** Default settings sized for the board, with a short fanout budget. */
  static RouterSettings settings(RoutingBoard board) {
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.applyBoardSpecificOptimizations(board);
    settings.fanout.maxPasses = 2;
    settings.fanout.maxSearchStepsPerPin = 20000L;
    return settings;
  }
}
