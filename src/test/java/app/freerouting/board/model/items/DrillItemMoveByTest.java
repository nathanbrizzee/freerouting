package app.freerouting.board.model.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.core.library.Padstack;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.IntVector;
import app.freerouting.geometry.planar.Point;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.GlobalSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A via that moves to the place it already is has no trace to connect, so none is attempted. */
class DrillItemMoveByTest {

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
  }

  @Test
  void movingByZeroWarnsOfNoDegeneratePolyline() throws Exception {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue143-rpi_splitter.dsn");
    Padstack padstack = board.library.padstacks.get("Round1$13.779528");
    assertNotNull(padstack);
    Via via =
        board.insertVia(
            padstack, new IntPoint(100000, 100000), new int[] {1}, 1, FixedState.UNFIXED, true);
    assertNotNull(via);
    board.insertTrace(
        new Point[] {new IntPoint(100000, 100000), new IntPoint(160000, 100000)},
        via.firstLayer(),
        1500,
        new int[] {1},
        1,
        FixedState.UNFIXED);
    assertFalse(via.getNormalContacts().isEmpty(), "the trace must touch the via");

    int warningsBefore = FRLogger.getLogEntries().getWarningCount();
    int itemsBefore = board.getItems().size();
    via.moveBy(IntVector.ZERO);

    assertEquals(warningsBefore, FRLogger.getLogEntries().getWarningCount());
    assertEquals(itemsBefore, board.getItems().size());
    assertEquals(new IntPoint(100000, 100000), via.getCenter());
  }
}
