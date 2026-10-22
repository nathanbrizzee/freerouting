package app.freerouting.drc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An unrouted board names each open connection by net and component pins. */
class OpenConnectionsTest {

  private static final String DSN =
      """
      (pcb open (resolution um 10) (unit um)
        (structure (layer top (type signal)) (layer bottom (type signal))
          (boundary (rect pcb 0 0 20000 20000)) (via V) (rule (width 250) (clearance 200)))
        (placement (component R (place R1 5000 10000 front 0) (place R2 15000 10000 front 0)))
        (library
          (image R (pin P 1 -500 0) (pin P 2 500 0))
          (padstack P (shape (rect top -300 -300 300 300)) (attach off))
          (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))
        (network (net A (pins R1-1 R2-1)) (net B (pins R1-2 R2-2))))
      """;

  @Test
  void namesTheOpenConnectionsByPin() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "open");
    assertInstanceOf(BoardReadResult.Success.class, result);
    BasicBoard board = ((BoardReadResult.Success) result).board();

    List<OpenConnections.Entry> open = OpenConnections.of(board);
    assertEquals(2, open.size());
    assertEquals("A", open.get(0).net);
    assertEquals(Set.of("R1-1", "R2-1"), Set.of(open.get(0).from, open.get(0).to));
    assertEquals("B", open.get(1).net);

    String line = OpenConnections.summary(open, 1);
    assertTrue(line.startsWith("Open connections (2): A: "), line);
    assertTrue(line.endsWith("; and 1 more"), line);

    OpenConnections.Entry a = open.get(0);
    Set<List<Double>> ends =
        Set.of(List.of(a.fromMm[0], a.fromMm[1]), List.of(a.toMm[0], a.toMm[1]));
    assertEquals(Set.of(List.of(4.5, 10.0), List.of(14.5, 10.0)), ends);
    assertEquals(10.0, a.lengthMm, 1e-9);
    assertEquals("free", a.fromExit.exit);
    assertEquals("free", a.toExit.exit);

    List<OpenConnections.Hint> hints = OpenConnections.hints(open);
    assertEquals(List.of("R1", "R2"), hints.stream().map(hint -> hint.component).toList());
    assertEquals(2, hints.get(0).openEnds);
    assertEquals(0, hints.get(0).closedEnds);
    assertTrue(hints.get(0).hint.startsWith("its pins can be left"), hints.get(0).hint);
  }

  /** Pin A1 sits inside a square of net B's trace on the only layer. */
  private static final String BOXED_IN =
      """
      (pcb boxed (resolution um 10) (unit um)
        (structure (layer top (type signal))
          (boundary (rect pcb 0 0 20000 20000)) (via V) (rule (width 250) (clearance 200)))
        (placement (component P (place A1 10000 10000 front 0) (place A2 18000 18000 front 0)
          (place B1 2000 2000 front 0) (place B2 2000 18000 front 0)))
        (library
          (image P (pin S 1 0 0))
          (padstack S (shape (rect top -300 -300 300 300)) (attach off))
          (padstack V (shape (circle top 600)) (attach off)))
        (network (net A (pins A1-1 A2-1)) (net B (pins B1-1 B2-1)))
        (wiring (wire (path top 250 9300 9300 10700 9300 10700 10700 9300 10700 9300 9450)
          (net B))))
      """;

  @Test
  void namesWhatClosesInPin() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(BOXED_IN.getBytes(StandardCharsets.UTF_8)),
            null,
            null,
            "boxed");
    assertInstanceOf(BoardReadResult.Success.class, result);
    BasicBoard board = ((BoardReadResult.Success) result).board();

    OpenConnections.Entry a =
        OpenConnections.of(board).stream().filter(e -> e.net.equals("A")).findFirst().get();
    OpenConnections.Exit boxed = "A1-1".equals(a.from) ? a.fromExit : a.toExit;
    assertEquals("closed", boxed.exit);
    assertEquals(1, boxed.closedBy.size(), boxed.closedBy.toString());
    assertTrue(boxed.closedBy.get(0).endsWith(" of net B"), boxed.closedBy.toString());
    OpenConnections.Exit open = "A1-1".equals(a.from) ? a.toExit : a.fromExit;
    assertEquals("free", open.exit);
  }

  @Test
  void hintsTellDesignLimitFromPlacementLimit() {
    OpenConnections.Exit byDesign = exit(true, "own pads", "fixed copper of net GND");
    OpenConnections.Exit byRoutes = exit(false, "pads of C3", "routes of net X");
    assertTrue(hint(byDesign).hint.contains("a design limit"), hint(byDesign).hint);
    OpenConnections.Hint both = hint(byRoutes, byDesign);
    assertEquals(2, both.closedEnds);
    assertEquals(1, both.closedByDesign);
    assertTrue(
        both.hint.startsWith(
            "closed in by pads of C3, routes of net X (1 more closed in by the design alone)"),
        both.hint);
  }

  private static OpenConnections.Exit exit(boolean byDesign, String... closedBy) {
    OpenConnections.Exit exit = new OpenConnections.Exit();
    exit.exit = "closed";
    exit.byDesign = byDesign;
    exit.closedBy = List.of(closedBy);
    return exit;
  }

  /** The hint for component U1 with one open connection per exit, each from one of its pins. */
  private static OpenConnections.Hint hint(OpenConnections.Exit... exits) {
    List<OpenConnections.Entry> open = new ArrayList<>();
    for (OpenConnections.Exit exit : exits) {
      OpenConnections.Entry entry = new OpenConnections.Entry();
      entry.fromComponent = "U1";
      entry.fromExit = exit;
      open.add(entry);
    }
    List<OpenConnections.Hint> hints = OpenConnections.hints(open);
    assertEquals(1, hints.size());
    return hints.get(0);
  }
}
