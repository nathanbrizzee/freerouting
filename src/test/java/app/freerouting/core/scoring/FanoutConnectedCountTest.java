package app.freerouting.core.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The statistics count the SMD pins left to escape from one connected set per group of connected
 * pins; the count must equal asking every pin for its unconnected set, on boards with and without
 * copper pours, before and after routing.
 */
class FanoutConnectedCountTest extends RoutingFixtureTest {

  private static int pinsToEscapeOnePinAtATime(BasicBoard board) {
    int total = 0;
    int connected = 0;
    for (Pin pin : board.getSmdPins()) {
      if (pin.netCount() > 0) {
        total++;
        if (pin.getUnconnectedSet(pin.getNetNumber(0)).isEmpty()) {
          connected++;
        }
      }
    }
    return total - connected;
  }

  @ParameterizedTest
  @CsvSource({
    "Issue313-FastTest.dsn, true",
    "Issue313-FastTest.dsn, false",
    "Issue508-DAC2020_bm01.dsn, false",
    "Issue145-smoothieboard.dsn, false",
    "Issue153-wavefolder.dsn, false",
    "Issue054-tairakb.dsn, false"
  })
  void matchesThePerPinCount(String fixture, boolean route) {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setRouterEnabled(route);
    testSettingsSource.setFanoutEnabled(route);
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies("none");
    RoutingJob job = getRoutingJob(fixture, testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 2;
    job = runRoutingJob(job);
    assertNotNull(job.board, "the board loads");
    BoardStatistics stats = new BoardStatistics(job.board, null, false);
    assertEquals(pinsToEscapeOnePinAtATime(job.board), stats.fanout.pinsToEscape, fixture);
  }
}
