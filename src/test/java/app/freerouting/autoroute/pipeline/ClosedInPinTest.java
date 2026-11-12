package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.StoppableThread;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A pin no trace can leave, even with every route ripped, is remembered after its first failed
 * search, and its later searches are skipped.
 */
class ClosedInPinTest {

  /** W2 sits in a tight keepout ring that leaves no room for a trace; W0 and W1 are free. */
  private static final String DSN =
      """
      (pcb closed_in (resolution um 10) (unit um)
        (structure (layer top (type signal))
          (boundary (rect pcb 0 0 40000 25000))
          (keepout (rect signal 28000 16000 32000 17350))
          (keepout (rect signal 28000 18650 32000 20000))
          (keepout (rect signal 28000 16000 29350 20000))
          (keepout (rect signal 30650 16000 32000 20000))
          (rule (width 200) (clearance 200)))
        (placement (component C
          (place W0 14000 18000 front 0) (place W1 4000 8000 front 0)
          (place W2 30000 18000 front 0)))
        (library (image C (pin P 1 0 0))
          (padstack P (shape (circle top 800)) (attach off)))
        (network (net WALLED (pins W0-1 W1-1 W2-1))))
      """;

  @Test
  void aPinClosedInByKeepoutsIsSkippedAfterItsFirstFailedSearch() {
    RoutingJob job = jobFor(false);
    BatchAutorouter router =
        BatchAutorouter.createAttempt(job, job.board, job.routerSettings, false);
    router.autoroutePass(1);

    Pin w2 = pin(job.board, "W2");
    assertTrue(router.closedInPins.contains(w2.getId()), "W2 must be found closed in");
    assertEquals(1, router.closedInPins.size(), "only W2: W0 and W1 can be left");

    long before = job.thread.searchSteps();
    router.autoroutePass(2);
    assertEquals(before, job.thread.searchSteps(), "the second pass must not search for W2 again");
  }

  @Test
  void withViasInPadsThePinIsNotSkipped() {
    RoutingJob job = jobFor(true);
    BatchAutorouter router =
        BatchAutorouter.createAttempt(job, job.board, job.routerSettings, false);
    router.autoroutePass(1);
    assertTrue(router.closedInPins.isEmpty(), "a via in the pad could still leave it");
  }

  private static Pin pin(RoutingBoard board, String component) {
    for (Pin pin : board.getPins()) {
      if (component.equals(pin.componentName())) {
        return pin;
      }
    }
    throw new AssertionError(component);
  }

  private static RoutingJob jobFor(boolean viaInPad) {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null, "closed");
    assertInstanceOf(BoardReadResult.Success.class, result);
    RoutingBoard board = (RoutingBoard) ((BoardReadResult.Success) result).board();
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.applyBoardSpecificOptimizations(board);
    settings.autorouter.viaInPad = viaInPad;
    RoutingJob job = new RoutingJob();
    job.board = board;
    job.routerSettings = settings;
    job.thread =
        new StoppableThread() {
          @Override
          protected void threadAction() {}
        };
    return job;
  }
}
