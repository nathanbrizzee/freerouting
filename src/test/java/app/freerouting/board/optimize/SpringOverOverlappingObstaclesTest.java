package app.freerouting.board.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A fixed trace on a foreign pin: the two obstacles overlap and neither contains the other. Spring
 * over gives up on such a pair unless {@code spring_over_overlaps} is on; then it goes around their
 * common octagon.
 */
class SpringOverOverlappingObstaclesTest {

  private static final String DSN =
      """
      (pcb spring (resolution um 1) (unit um)
        (structure (layer top (type signal)) (layer bottom (type signal))
          (boundary (rect pcb 0 0 20000 12000)) (via V) (rule (width 200) (clearance 200)))
        (placement
          (component P (place A 3000 5400 front 0) (place B 17000 5400 front 0)
            (place X1 10000 5000 front 0)))
        (library
          (image P (pin S 1 0 0))
          (padstack S (shape (rect top -300 -300 300 300)) (attach off))
          (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))
        (network (net N (pins A-1 B-1)) (net X (pins X1-1)))
        (wiring (wire (path top 300 10000 5000 13000 5000) (net X) (type fix))))
      """;

  private static RoutingBoard loadBoard() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "spring");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private static int netOf(RoutingBoard board, String component) {
    return board.getPins().stream()
        .filter(p -> component.equals(board.components.get(p.getComponentId()).name))
        .map(p -> p.getNetNumber(0))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void springsOverAFixedTraceOnAForeignPin() {
    RoutingBoard board = loadBoard();
    int net = netOf(board, "A");
    int[] nets = {net};
    int halfWidth = 100;
    Polyline path = new Polyline(new Point[] {new IntPoint(3000, 5400), new IntPoint(17000, 5400)});

    assertNull(
        new TraceShover(board).springOverObstacles(path, halfWidth, 0, nets, 1, null),
        "off by default");
    board.springOverOverlaps = true;
    Polyline detour = new TraceShover(board).springOverObstacles(path, halfWidth, 0, nets, 1, null);
    assertNotNull(detour, "the pin and the fixed trace on it are passed as one obstacle");
    assertTrue(detour.cornerCount() > 2, "the route detours");

    Point reached =
        board.insertForcedTracePolyline(
            path, halfWidth, 0, nets, 1, 10, 10, 20, 0, 100, true, null);
    assertEquals(path.lastCorner(), reached, "the insert reaches the end");
    assertEquals(0, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
  }
}
