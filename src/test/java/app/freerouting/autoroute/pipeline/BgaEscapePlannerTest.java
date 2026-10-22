package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.settings.RouterSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The BGA planner finds the pin grid and gives every inner ball a dog-bone in a diagonal gap. */
class BgaEscapePlannerTest {

  private static final int GRID = 8;
  private static final int PITCH_UM = 800;

  @Test
  void detectsTheGridButNotTheConnector() {
    RoutingBoard board = GridBoard.board(GRID, PITCH_UM);
    List<BgaEscapePlanner.Grid> grids = BgaEscapePlanner.detectGrids(board);
    assertEquals(1, grids.size(), "the SMD grid; the through-hole connector is not a candidate");
    BgaEscapePlanner.Grid grid = grids.get(0);
    assertEquals("U1", grid.name());
    assertEquals(GRID, grid.cols());
    assertEquals(GRID, grid.rows());
    assertEquals(PITCH_UM * GridBoard.UNITS_PER_UM, grid.pitch(), 1.0);
    assertEquals(GRID * GRID, grid.balls().size());
    for (BgaEscapePlanner.Ball ball : grid.balls()) {
      int row = ball.pin().pinIndex / GRID;
      int col = ball.pin().pinIndex % GRID;
      int ring = Math.min(Math.min(col, GRID - 1 - col), Math.min(row, GRID - 1 - row));
      assertEquals(ring, ball.ring(), "ring of pin " + (ball.pin().pinIndex + 1));
    }
  }

  @Test
  void givesEveryInnerBallItsViaAndLeavesTheOuterRingOnTheSurface() {
    RoutingBoard board = GridBoard.board(GRID, PITCH_UM);
    RouterSettings settings = GridBoard.settings(board);
    int violationsBefore = new DesignRulesChecker(board, null).getAllClearanceViolations().size();

    BgaEscapePlanner.Summary summary = BgaEscapePlanner.run(board, settings, null);

    int inner = (GRID - 2) * (GRID - 2);
    assertEquals(inner, summary.needVia(), "every inner ball needs a via");
    assertEquals(inner, summary.placed(), "and gets one: " + summary);
    assertEquals(
        violationsBefore,
        new DesignRulesChecker(board, null).getAllClearanceViolations().size(),
        "no new clearance violations");
    for (Pin pin : board.getSmdPins()) {
      int row = pin.pinIndex / GRID;
      int col = pin.pinIndex % GRID;
      boolean isInner = row > 0 && row < GRID - 1 && col > 0 && col < GRID - 1;
      boolean hasVia = pin.connectedSetLeavesLayer(pin.getNetNumber(0), pin.firstLayer());
      assertEquals(isInner, hasVia, "pin " + (pin.pinIndex + 1));
    }

    RoutingBoard again = GridBoard.board(GRID, PITCH_UM);
    BgaEscapePlanner.run(again, GridBoard.settings(again), null);
    assertEquals(board.getHash(), again.getHash(), "the same plan on every run");
  }

  @Test
  void theFanoutStageKeepsThePlan() {
    RoutingBoard board = GridBoard.board(GRID, PITCH_UM);
    RouterSettings settings = GridBoard.settings(board);
    settings.fanout.strategy = "bga";
    settings.fanout.protectEscapes = true;
    settings.fanout.minEscapeByPitch = true;
    BatchFanout.fanoutBoard(board, settings, null);
    int innerWithVia = 0;
    for (Pin pin : board.getSmdPins()) {
      int row = pin.pinIndex / GRID;
      int col = pin.pinIndex % GRID;
      boolean isInner = row > 0 && row < GRID - 1 && col > 0 && col < GRID - 1;
      if (isInner && pin.connectedSetLeavesLayer(pin.getNetNumber(0), pin.firstLayer())) {
        innerWithVia++;
      }
    }
    assertEquals((GRID - 2) * (GRID - 2), innerWithVia, "every inner ball still has its via");
    assertFalse(board.getVias().isEmpty());
    assertTrue(board.getVias().size() >= (GRID - 2) * (GRID - 2));
  }
}
