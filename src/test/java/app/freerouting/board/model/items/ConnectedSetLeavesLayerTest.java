package app.freerouting.board.model.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The early-exit check must give the same answer as building the whole connected set and looking
 * for an item that is not confined to the layer, for every pin and every layer of the pin, on
 * boards with and without copper pours, before and after routing.
 */
class ConnectedSetLeavesLayerTest extends RoutingFixtureTest {

  private static boolean leavesLayerFromFullSet(Item item, int net, int layer) {
    for (Item current : item.getConnectedSet(net)) {
      if (current.firstLayer() != layer || current.lastLayer() != layer) {
        return true;
      }
    }
    return false;
  }

  @ParameterizedTest
  @CsvSource({
    "Issue313-FastTest.dsn, true",
    "Issue313-FastTest.dsn, false",
    "Issue508-DAC2020_bm01.dsn, false",
    "Issue145-smoothieboard.dsn, false",
    "Issue054-tairakb.dsn, true"
  })
  void matchesTheFullConnectedSet(String fixture, boolean route) {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setRouterEnabled(route);
    testSettingsSource.setFanoutEnabled(route);
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies("none");
    RoutingJob job = getRoutingJob(fixture, testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 2;
    job = runRoutingJob(job);
    assertNotNull(job.board, "the board loads");
    int checked = 0;
    for (Pin pin : job.board.getPins()) {
      if (pin.netCount() != 1) {
        continue;
      }
      int net = pin.getNetNumber(0);
      for (int layer = pin.firstLayer(); layer <= pin.lastLayer(); layer++) {
        assertEquals(
            leavesLayerFromFullSet(pin, net, layer),
            pin.connectedSetLeavesLayer(net, layer),
            fixture + " " + pin);
        checked++;
      }
    }
    assertEquals(true, checked > 0, "pins were checked");
  }
}
