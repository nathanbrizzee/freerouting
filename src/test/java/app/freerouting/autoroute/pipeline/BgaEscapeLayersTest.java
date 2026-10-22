package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Via;
import app.freerouting.drc.DesignRulesChecker;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * With blind vias in the rule, the planner fills the layers from the outside in: ring 1 escapes on
 * the first inner layer through the shortest via, the next rings move deeper as the channels fill,
 * and the innermost ring takes the through via to the bottom.
 */
class BgaEscapeLayersTest {

  private static final int GRID = 10;

  @Test
  void ringsFillTheLayersFromTheOutsideIn() {
    // 4 layers, 120 um traces: one trace fits between two 450 um vias at 0.8 mm
    // (8000 - 4500 - 1000 = 2500 >= 1200 + 1000), and the part has 36 channels per layer.
    RoutingBoard board =
        GridBoard.board(GridBoard.dsn(GRID, 800, 4, 120, new int[][] {{0, 1}, {0, 2}}));
    int violationsBefore = new DesignRulesChecker(board, null).getAllClearanceViolations().size();

    BgaEscapePlanner.Summary summary = BgaEscapePlanner.run(board, GridBoard.settings(board), null);

    int inner = (GRID - 2) * (GRID - 2);
    assertEquals(inner, summary.placed(), "every inner ball gets its via: " + summary);
    assertEquals(
        violationsBefore,
        new DesignRulesChecker(board, null).getAllClearanceViolations().size(),
        "no new clearance violations");

    Map<Integer, Map<Integer, Integer>> endLayerByRing = new TreeMap<>();
    for (Pin pin : board.getSmdPins()) {
      int row = pin.pinIndex / GRID;
      int col = pin.pinIndex % GRID;
      int ring = Math.min(Math.min(col, GRID - 1 - col), Math.min(row, GRID - 1 - row));
      Via via = null;
      for (Item item : pin.getConnectedSet(pin.getNetNumber(0))) {
        if (item instanceof Via found) {
          via = found;
        }
      }
      if (ring == 0) {
        continue;
      }
      assertNotNull(via, "pin " + (pin.pinIndex + 1) + " has a via");
      endLayerByRing
          .computeIfAbsent(ring, r -> new TreeMap<>())
          .merge(via.getPadstack().toLayer(), 1, Integer::sum);
    }
    // 36 channels per layer: ring 1 (28 balls) on layer 1, ring 2 (20) 8 on layer 1 and 12 on
    // layer 2, rings 3 (12) and 4 (4) on layer 2, which still has room, so the through via to the
    // bottom is not needed.
    assertEquals(Map.of(1, 28), endLayerByRing.get(1), "ring 1");
    assertEquals(Map.of(1, 8, 2, 12), endLayerByRing.get(2), "ring 2");
    assertEquals(Map.of(2, 12), endLayerByRing.get(3), "ring 3");
    assertEquals(Map.of(2, 4), endLayerByRing.get(4), "ring 4");
  }

  @Test
  void theInnermostRingsTakeTheThroughViaWhenTheBlindLayersAreFull() {
    // Only the blind via to layer 1 besides the through via: ring 1 and 8 balls of ring 2 fill
    // layer 1, the other 28 balls go through to the bottom.
    RoutingBoard board = GridBoard.board(GridBoard.dsn(GRID, 800, 4, 120, new int[][] {{0, 1}}));
    BgaEscapePlanner.Summary summary = BgaEscapePlanner.run(board, GridBoard.settings(board), null);
    assertEquals((GRID - 2) * (GRID - 2), summary.placed(), summary.toString());
    Map<Integer, Integer> byEndLayer = new TreeMap<>();
    for (Via via : board.getVias()) {
      byEndLayer.merge(via.getPadstack().toLayer(), 1, Integer::sum);
    }
    assertEquals(Map.of(1, 36, 3, 28), byEndLayer);
  }

  @Test
  void throughViasOnlyKeepTheSmallestViaForEveryBall() {
    RoutingBoard board = GridBoard.board(GridBoard.dsn(GRID, 800, 4, 120, new int[0][]));
    BgaEscapePlanner.run(board, GridBoard.settings(board), null);
    for (Via via : board.getVias()) {
      assertEquals(3, via.getPadstack().toLayer(), "through via");
    }
  }
}
