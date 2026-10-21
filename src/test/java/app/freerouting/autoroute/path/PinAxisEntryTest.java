package app.freerouting.autoroute.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A row of long pads 0.2 mm wide at 0.4 mm pitch, so the gap between them equals the clearance: a
 * trace reaches the middle pad only along the pad's axis, necked down. From a point off to the side
 * the insert goes along the axis into the centre.
 */
class PinAxisEntryTest {

  private static final String DSN =
      """
      (pcb axis (resolution um 10) (unit um)
        (structure (layer top (type signal)) (layer bottom (type signal))
          (boundary (rect pcb 0 0 20000 20000)) (via V) (rule (width 250) (clearance 200)))
        (placement (component R (place C1 10000 10000 front 0)))
        (library
          (image R (pin P 1 0 800) (pin P 2 0 400) (pin P 3 0 0) (pin P 4 0 -400)
            (pin P 5 0 -800))
          (padstack P (shape (rect top -350 -100 350 100)) (attach off))
          (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))
        (network (net N (pins C1-3)) (net A (pins C1-1)) (net B (pins C1-2))
          (net C (pins C1-4)) (net D (pins C1-5))))
      """;

  private static RoutingBoard loadBoard() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "axis");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  @Test
  void entersTheFinePitchPadAlongItsAxis() {
    RoutingBoard board = loadBoard();
    Pin target =
        board.getPins().stream().filter(p -> p.getPinIndex() == 2).findFirst().orElseThrow();
    int net = target.getNetNumber(0);
    AutorouteControl ctrl = new AutorouteControl(board, net, new DefaultSettings().getSettings());
    IntPoint centre = (IntPoint) target.getCenter();
    IntPoint from = new IntPoint(centre.x + 19000, centre.y + 3750);
    assertTrue(
        new FoundConnectionInserter(board, ctrl).insertAlongPinAxis(from, centre, 0, target),
        "the trace reaches the centre of the pad");

    PolylineTrace last = null;
    for (Item item : board.getConnectableItems(net)) {
      if (item instanceof PolylineTrace trace
          && (trace.firstCorner().equals(centre) || trace.lastCorner().equals(centre))) {
        last = trace;
      }
    }
    assertNotNull(last, "a trace ends in the centre");
    Point[] corners = last.polyline().corners();
    Point other = last.lastCorner().equals(centre) ? corners[corners.length - 2] : corners[1];
    assertEquals(centre.y, other.toFloat().round().y, "the last piece runs along the pad axis");
    assertEquals(0, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
  }
}
