package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.DesignBlocks;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying {@link DesignBlocks} for connections blocked by the design. Ported from
 * freerouting.net (Erik Gavriluk, Experimental Television Center).
 */
class DesignBlocksTest {

  private static final String DSN =
      """
      (pcb blocks (resolution um 1) (unit um)
        (structure (layer top (type signal)) (layer bottom (type signal))
          (boundary (rect pcb 0 0 40000 20000)) (via V) (rule (width 200) (clearance 200)))
        (placement
          (component P (place A 3000 5000 front 0) (place B 17000 5000 front 0)
            (place C 3000 15000 front 0) (place D 17000 15000 front 0)
            (place E 25000 15000 front 0) (place F 35000 15000 front 0))
          (component X (place X1 3000 5000 front 0))
          (component H (place T 30000 5000 front 0) (place U 36000 5000 front 0))
          (component Y (place Y1 30000 5000 front 0))
          (component W (place W1 25750 15000 front 0))
          (component G (place G1 10000 10000 front 0) (place J1 22000 9000 front 0))
          (component P2 (place G2 10000 17000 front 0) (place J2 33000 9000 front 0))
          (component Z (place Z1 10550 10000 front 0) (place Z2 22900 9000 front 0)))
        (library
          (image P (pin S 1 0 0))
          (image X (pin S e1 0 0))
          (image H (pin R 1 0 0))
          (image Y (pin S e1 0 0))
          (image W (pin S e1 0 0))
          (image G (pin T 1 0 0))
          (image P2 (pin S 1 0 0))
          (image Z (pin S e1 0 0))
          (padstack S (shape (rect top -400 -400 400 400)) (attach off))
          (padstack T (shape (rect top -50 -50 50 50)) (attach off))
          (padstack R (shape (circle top 800)) (shape (circle bottom 800)) (attach off))
          (padstack V (shape (circle top 600)) (shape (circle bottom 600)) (attach off)))
        (network (net N (pins A-1 B-1)) (net M (pins C-1 D-1)) (net K (pins T-1 U-1)) (net Q (pins E-1 F-1))
          (net G (pins G1-1 G2-1)) (net J (pins J1-1 J2-1))))
      """;

  private static RoutingBoard loadBoard() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "blocks");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private static Pin pinOf(RoutingBoard board, String component) {
    return board.getPins().stream()
        .filter(
            p ->
                board.components.get(p.getComponentId()) != null
                    && component.equals(board.components.get(p.getComponentId()).name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void testPinOnForeignCopperOnEveryLayerIsBlocked() {
    RoutingBoard board = loadBoard();
    Pin a = pinOf(board, "A");
    assertTrue(DesignBlocks.isBlocked(a));
    assertNotNull(DesignBlocks.blockingItems(a));
    assertEquals(1, DesignBlocks.blockingItems(a).size());
    assertEquals(
        "X1", board.components.get(DesignBlocks.blockingItems(a).get(0).getComponentId()).name);

    assertFalse(DesignBlocks.isBlocked(pinOf(board, "B")));
    assertFalse(DesignBlocks.isBlocked(pinOf(board, "T")));
    // W1's netless pad overlaps E-1 by 50 um but stays 350 um from its centre: a trace can leave on
    // the free side
    assertFalse(DesignBlocks.isBlocked(pinOf(board, "E")));
  }

  /**
   * G1's 100 um pad stays 100 um from Z1's netless pad, inside the 200 um clearance; even its far
   * corner is 200 um away, closer than the clearance plus the narrowest trace, so no trace can
   * leave it. J1's 800 um pad at the same gap from Z2 has a far side a trace can leave from.
   */
  @Test
  void testPinWithinClearanceOfForeignCopperAtEveryCornerIsBlocked() {
    RoutingBoard board = loadBoard();
    Pin g1 = pinOf(board, "G1");
    assertTrue(DesignBlocks.isBlocked(g1));
    assertEquals(1, DesignBlocks.blockingItems(g1).size());
    assertEquals(
        "Z1", board.components.get(DesignBlocks.blockingItems(g1).get(0).getComponentId()).name);
    Pin g2 = pinOf(board, "G2");
    assertTrue(DesignBlocks.connectionsBlocked(board, g2, g2.getNetNumber(0)));

    assertFalse(DesignBlocks.isBlocked(pinOf(board, "J1")));
  }

  @Test
  void testNetWhoseOnlyOtherGroupIsBlockedIsBlockedFromEitherEnd() {
    RoutingBoard board = loadBoard();
    Pin a = pinOf(board, "A");
    Pin b = pinOf(board, "B");
    Pin c = pinOf(board, "C");
    Pin t = pinOf(board, "T");

    assertTrue(DesignBlocks.connectionsBlocked(board, a, a.getNetNumber(0)));
    assertTrue(DesignBlocks.connectionsBlocked(board, b, b.getNetNumber(0)));
    assertFalse(DesignBlocks.connectionsBlocked(board, c, c.getNetNumber(0)));
    assertFalse(DesignBlocks.connectionsBlocked(board, t, t.getNetNumber(0)));
  }

  @Test
  void testNetsOfRuleBlockedPinsAreKnownFromTheFirstPass() {
    RoutingBoard board = loadBoard();
    assertNull(AutoroutePassRunner.ruleBlockedNets(board));

    Pin c = pinOf(board, "C");
    Pin d = pinOf(board, "D");
    assertFalse(AutoroutePassRunner.designBlocked(board, d, d.getNetNumber(0)));

    board.ruleBlockedPins = Collections.singleton(c.getId());
    Set<Integer> ruleBlockedNets = AutoroutePassRunner.ruleBlockedNets(board);
    assertNotNull(ruleBlockedNets);
    assertEquals(Collections.singleton(c.getNetNumber(0)), ruleBlockedNets);

    assertTrue(AutoroutePassRunner.designBlocked(board, c, c.getNetNumber(0)));
    assertTrue(AutoroutePassRunner.designBlocked(board, d, d.getNetNumber(0)));
  }
}
