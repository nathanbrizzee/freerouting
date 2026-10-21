package app.freerouting.drc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
  }
}
