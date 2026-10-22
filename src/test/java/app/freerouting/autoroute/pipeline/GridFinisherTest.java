package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Via;
import app.freerouting.settings.RouterSettings;
import org.junit.jupiter.api.Test;

/**
 * Net A has two pads on the top layer, 14 mm apart, with a wall of net B between them on the top
 * layer from one board edge to the other. The grid finisher must cross on the bottom layer with two
 * vias, or, when the bottom layer is walled too, leave the board as it was.
 */
class GridFinisherTest {

  private static String dsn(boolean bottomWall) {
    return "(pcb t (resolution um 10) (unit um)\n"
        + "  (structure (layer top (type signal)) (layer bottom (type signal))\n"
        + "    (boundary (rect pcb 0 0 20000 20000)) (via V)"
        + " (rule (width 250) (clearance 200)))\n"
        + "  (placement (component P (place A1 3000 10000 front 0) (place A2 17000 10000 front 0)\n"
        + "    (place B1 10000 500 front 0) (place B2 10000 19500 front 0)))\n"
        + "  (library (image P (pin S 1 0 0))\n"
        + "    (padstack S (shape (circle top 600)) (attach off))\n"
        + "    (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))\n"
        + "  (network (net A (pins A1-1 A2-1)) (net B (pins B1-1 B2-1)))\n"
        + "  (wiring (wire (path top 250 10000 500 10000 19500) (net B) (type protect))"
        + (bottomWall ? " (wire (path bottom 250 10000 0 10000 20000) (net B) (type protect))" : "")
        + "))\n";
  }

  private static RouterSettings settings(RoutingBoard board) {
    RouterSettings settings = GridBoard.settings(board);
    settings.autorouter.gridFinisher = true;
    return settings;
  }

  @Test
  void crossesTheWallOnTheOtherLayer() {
    RoutingBoard board = GridBoard.board(dsn(false));
    assertEquals(1, board.routingLedger().incompleteCount());

    GridFinisher.Summary summary = GridFinisher.run(board, settings(board), null);

    assertEquals(1, summary.closed());
    assertEquals(0, board.routingLedger().incompleteCount());
    int vias = 0;
    for (Item item : board.getConnectableItems(net(board, "A"))) {
      assertTrue(item.clearanceViolations().isEmpty(), item + " has a clearance violation");
      if (item instanceof Via) {
        vias++;
      }
    }
    assertEquals(2, vias, "down before the wall and up after it");
  }

  @Test
  void leavesTheBoardAsItWasWhenNoPathExists() {
    RoutingBoard board = GridBoard.board(dsn(true));
    String before = items(board);

    GridFinisher.Summary summary = GridFinisher.run(board, settings(board), null);

    assertEquals(1, summary.tried());
    assertEquals(0, summary.closed());
    // The hash would also see caches the search fills; the items are what must not change.
    assertEquals(before, items(board));
    assertEquals(1, board.routingLedger().incompleteCount());
  }

  @Test
  void stopsAtItsStepBudget() {
    RoutingBoard board = GridBoard.board(dsn(false));
    RouterSettings settings = settings(board);
    settings.autorouter.gridFinisherMaxSteps = 10L;

    GridFinisher.Summary summary = GridFinisher.run(board, settings, null);

    assertEquals(0, summary.closed());
    assertTrue(summary.expansions() <= 11, summary.expansions() + " states expanded");
    assertEquals(1, board.routingLedger().incompleteCount());
  }

  @Test
  void isDeterministic() {
    RoutingBoard first = GridBoard.board(dsn(false));
    GridFinisher.run(first, settings(first), null);
    RoutingBoard second = GridBoard.board(dsn(false));
    GridFinisher.run(second, settings(second), null);
    assertEquals(first.getHash(), second.getHash());
  }

  private static String items(RoutingBoard board) {
    StringBuilder result = new StringBuilder();
    for (Item item : board.getItems()) {
      result.append(item.getId()).append(item.getClass().getSimpleName()).append(' ');
    }
    return result.toString();
  }

  private static int net(RoutingBoard board, String name) {
    return board.rules.nets.get(name, 1).netNumber;
  }
}
