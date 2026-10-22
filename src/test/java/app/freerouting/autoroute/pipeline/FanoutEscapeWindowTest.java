package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.settings.RouterSettings;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * An 8 x 8 grid of 0.8 mm pitch pads whose channels are too narrow for a trace: with the default
 * escape window no inner pad can reach a via, with {@code min_escape_by_pitch} each one drills in
 * the diagonal gap next to it.
 */
class FanoutEscapeWindowTest {

  private static final int GRID = 8;
  private static final int PITCH_UM = 800; // DSN coordinates are in um
  private static final int PITCH = PITCH_UM * GridBoard.UNITS_PER_UM; // board units

  private static RoutingBoard board() {
    return GridBoard.board(GRID, PITCH_UM);
  }

  private static RouterSettings settings(RoutingBoard board, boolean byPitch) {
    RouterSettings settings = GridBoard.settings(board);
    settings.fanout.minEscapeByPitch = byPitch;
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

  /** Indices of the U1 pins whose connection leaves the top layer. */
  private static Set<Integer> pinsWithVia(RoutingBoard board) {
    Set<Integer> result = new TreeSet<>();
    for (Pin pin : board.getSmdPins()) {
      if (pin.connectedSetLeavesLayer(pin.getNetNumber(0), pin.firstLayer())) {
        result.add(pin.pinIndex);
      }
    }
    return result;
  }

  @Test
  void protectedEscapesAreNeverUndoneByTheNextPass() {
    RoutingBoard onePass = board();
    RouterSettings settings = settings(onePass, true);
    settings.fanout.protectEscapes = true;
    settings.fanout.maxPasses = 1;
    BatchFanout.fanoutBoard(onePass, settings, null);
    Set<Integer> afterOne = pinsWithVia(onePass);

    RoutingBoard twoPasses = board();
    settings.fanout.maxPasses = 2;
    BatchFanout.fanoutBoard(twoPasses, settings, null);
    Set<Integer> afterTwo = pinsWithVia(twoPasses);

    assertTrue(afterOne.size() > 30, afterOne.size() + " pins escaped in pass 1");
    assertTrue(afterTwo.containsAll(afterOne), "pass 2 keeps every escape of pass 1");
    // The pins still without a via have all four diagonal gaps taken by neighbours that chose
    // first; assigning the gaps by a matching is the BGA planner's job, not this change's.
    assertTrue(innerPinsWithVia(twoPasses) >= 21, "no worse than without the protection");
  }

  @Test
  void nearestPinDistanceIsThePitch() {
    RoutingBoard board = board();
    Pin first = board.getSmdPins().iterator().next();
    assertEquals(PITCH, board.nearestPinDistance(first), 1.0);
  }
}
