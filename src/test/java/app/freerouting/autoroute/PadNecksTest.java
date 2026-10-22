package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Component;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** A pin closed in at its pad width gets a narrower neck and can then be routed. */
class PadNecksTest {

  /**
   * One layer, traces 0.3 mm wide with 0.21 mm clearance. U1 has three 0.3 x 1.6 mm pads at 0.5 mm
   * pitch, so a trace as wide as a pad leaves 0.2 mm to the next pad, less than the clearance. The
   * middle pin (net A) runs to R1 above; the outer pins (nets B and C) to R2 and R3 below.
   */
  private static final String DSN =
      """
      (pcb neck (resolution um 10) (unit um)
        (structure (layer top (type signal))
          (boundary (rect pcb 0 0 20000 20000)) (via V) (rule (width 300) (clearance 210)))
        (placement (component Q (place U1 10000 10000 front 0))
          (component R (place R1 10000 17000 front 0) (place R2 4000 3000 front 0)
            (place R3 16000 3000 front 0)))
        (library
          (image Q (pin S 1 -500 0) (pin S 2 0 0) (pin S 3 500 0))
          (image R (pin W 1 0 0))
          (padstack S (shape (rect top -150 -800 150 800)) (attach off))
          (padstack W (shape (rect top -500 -500 500 500)) (attach off))
          (padstack V (shape (circle top 600)) (attach off)))
        (network (net A (pins U1-2 R1-1)) (net B (pins U1-1 R2-1)) (net C (pins U1-3 R3-1))))
      """;

  private static RoutingBoard board() {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "neck");
    assertInstanceOf(BoardReadResult.Success.class, result);
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  private static Pin pin(RoutingBoard board, String component, String name) {
    for (Pin pin : board.getPins()) {
      Component owner = board.components.get(pin.getComponentId());
      if (owner != null && owner.name.equals(component) && name.equals(pin.name())) {
        return pin;
      }
    }
    throw new AssertionError("no pin " + component + "-" + name);
  }

  @Test
  void plansNecksOnlyWhereThePadWidthCannotLeave() {
    RoutingBoard board = board();
    final int padNeck = pin(board, "U1", "2").getTraceNeckdownHalfwidth(0);
    List<PadNecks.Neck> necks = PadNecks.plan(board);
    List<String> names = necks.stream().map(PadNecks.Neck::pin).toList();
    assertTrue(names.contains("U1-2"), necks.toString());
    assertTrue(names.stream().allMatch(name -> name.startsWith("U1-")), necks.toString());
    PadNecks.Neck middle = necks.get(names.indexOf("U1-2"));
    assertTrue(middle.neckMm() < 0.29 && middle.neckMm() >= 0.15, middle.toString());
    int planned = pin(board, "U1", "2").getTraceNeckdownHalfwidth(0);
    assertTrue(planned < padNeck, planned + " against " + padNeck);
    assertEquals(0, pin(board, "R1", "1").getAccessNeckHalfWidth(), "a free pin keeps its pad");
  }

  @Test
  void theNeckedPinStillRoutes() {
    assertEquals(AutorouteAttemptState.ROUTED, routeA(true));
  }

  private static AutorouteAttemptState routeA(boolean neck) {
    RoutingBoard board = board();
    if (neck) {
      PadNecks.plan(board);
    }
    int a = board.rules.nets.get("A", 1).netNumber;
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    AutorouteControl ctrl = new AutorouteControl(board, a, settings);
    AutorouteEngine engine =
        board.initAutoroute(a, ctrl.traceClearanceClassIndex, null, null, false);
    SortedSet<Item> ripped = new TreeSet<>();
    return engine.autorouteConnection(
            Set.of(pin(board, "U1", "2")), Set.of(pin(board, "R1", "1")), ctrl, ripped, null)
        .state;
  }
}
