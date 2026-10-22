package app.freerouting.drc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The crowded region an open connection crosses is found and named by its parts. */
class CongestionTest {

  /**
   * Net A runs from R1 at (2, 10) mm to R2 at (18, 10) mm straight across U1, a 3 x 3 grid of 0.5
   * mm pads at 0.6 mm pitch centred at (10, 10) mm. The rest of the board is empty.
   */
  private static final String DSN =
      """
      (pcb crowd (resolution um 10) (unit um)
        (structure (layer top (type signal)) (layer bottom (type signal))
          (boundary (rect pcb 0 0 20000 20000)) (via V) (rule (width 250) (clearance 200)))
        (placement (component R (place R1 2000 10000 front 0) (place R2 18000 10000 front 0))
          (component Q (place U1 10000 10000 front 0)))
        (library
          (image R (pin P 1 0 0))
          (image Q (pin P 1 -600 -600) (pin P 2 0 -600) (pin P 3 600 -600)
            (pin P 4 -600 0) (pin P 5 0 0) (pin P 6 600 0)
            (pin P 7 -600 600) (pin P 8 0 600) (pin P 9 600 600))
          (padstack P (shape (rect top -250 -250 250 250)) (attach off))
          (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))
        (network (net A (pins R1-1 R2-1))))
      """;

  @Test
  void findsTheCrowdedRegionAnOpenConnectionCrosses() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "crowd");
    assertInstanceOf(BoardReadResult.Success.class, result);
    BasicBoard board = ((BoardReadResult.Success) result).board();

    List<OpenConnections.Entry> open = OpenConnections.of(board);
    assertEquals(1, open.size());
    List<Congestion.Region> regions = Congestion.regions(board, open, 3);
    assertFalse(regions.isEmpty());
    Congestion.Region top = regions.get(0);
    assertEquals("U1", top.components.get(0), top.toString());
    assertTrue(top.leftMm <= 10 && top.leftMm + top.widthMm >= 10, top.toString());
    assertTrue(top.copperFill > 0.1, top.toString());
    assertEquals(1, top.openCrossing);
    for (Congestion.Region region : regions) {
      assertTrue(
          region.copperFill * region.openCrossing <= top.copperFill * top.openCrossing,
          region.toString());
    }
    assertTrue(Congestion.regions(board, List.of(), 3).isEmpty(), "nothing open, nothing listed");
  }

  @Test
  void segmentMeetsBoxOnlyWhenItCrossesIt() {
    assertTrue(Congestion.segmentMeetsBox(new double[] {0, 5}, new double[] {10, 5}, 4, 4, 6, 6));
    assertFalse(Congestion.segmentMeetsBox(new double[] {0, 0}, new double[] {10, 0}, 4, 4, 6, 6));
    assertTrue(Congestion.segmentMeetsBox(new double[] {5, 5}, new double[] {5, 5}, 4, 4, 6, 6));
  }

  @Test
  void verdictCountsWhatHoldsEachConnection() {
    OpenConnections.Entry design = entry("closed", true);
    OpenConnections.Entry routes = entry("closed", false);
    OpenConnections.Entry free = entry("free", null);
    String verdict = OpenConnections.verdict(List.of(design, routes, free));
    assertTrue(
        verdict.startsWith(
            "3 connections open: 1 at a pin the design closes in, so no route reaches it as"
                + " placed; 1 at a pin that routes or nearby parts close in; 1 whose ends can be"),
        verdict);
    assertEquals(null, OpenConnections.verdict(List.of()));
  }

  private static OpenConnections.Entry entry(String exit, Boolean byDesign) {
    OpenConnections.Entry entry = new OpenConnections.Entry();
    entry.fromExit = new OpenConnections.Exit();
    entry.fromExit.exit = exit;
    entry.fromExit.byDesign = byDesign;
    return entry;
  }
}
