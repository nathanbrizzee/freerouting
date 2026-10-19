package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.model.items.Item;
import app.freerouting.core.RoutingJob;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A pass loop that ends incomplete (here through the max-pass limit) must still remove dangling
 * traces and unused fanout vias. The final cleanup used to run only when no stop had been
 * requested, and every limit-based stop requests one.
 */
class DanglingTailCleanupTest extends RoutingFixtureTest {

  @Test
  void incompleteBoardHasNoTailsAfterMaxPassStop() {
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;

    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(1);
    testSettingsSource.setMaxItems(20);
    testSettingsSource.setJobTimeoutString("00:02:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testSettingsSource);
    runRoutingJob(job);

    List<Item> tails =
        job.board.getItems().stream()
            .filter(item -> item.isRoutable() && item.netCount() == 1 && item.isTail())
            .toList();
    assertTrue(
        job.board.getItems().stream().anyMatch(item -> item.isRoutable()),
        "the run should have inserted some copper");
    assertEquals(0, tails.size(), "dangling items left on the board: " + tails);
  }
}
