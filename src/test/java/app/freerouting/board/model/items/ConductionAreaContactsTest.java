package app.freerouting.board.model.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.searchtree.SearchTreeObject;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.settings.sources.TestingSettings;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The contacts of a conduction area, found from the items of its nets, must be the set the overlap
 * query over the whole area finds, before and after routing.
 */
class ConductionAreaContactsTest extends RoutingFixtureTest {

  /** The contact search as it was: every item overlapping the area, filtered by net and layer. */
  private static Set<Item> contactsFromOverlapQuery(ConductionArea area) {
    BasicBoard board = area.board;
    Set<Item> result = new TreeSet<>();
    for (int i = 0; i < area.tileShapeCount(); i++) {
      TileShape currentShape = area.getTileShape(i);
      for (SearchTreeObject currentObject :
          board.overlappingObjects(currentShape, area.getLayer())) {
        if (!(currentObject instanceof Item currentItem)) {
          continue;
        }
        if (currentItem != area && currentItem.sharesNet(area) && currentItem.sharesLayer(area)) {
          if (currentItem instanceof Trace currentTrace) {
            if (currentShape.contains(currentTrace.firstCorner())
                || currentShape.contains(currentTrace.lastCorner())) {
              result.add(currentItem);
            }
          } else if (currentItem instanceof DrillItem currentDrillItem) {
            if (currentShape.contains(currentDrillItem.getCenter())) {
              result.add(currentItem);
            }
          }
        }
      }
    }
    return result;
  }

  @ParameterizedTest
  @CsvSource({
    "Issue313-FastTest.dsn, false",
    "Issue313-FastTest.dsn, true",
    "Issue508-DAC2020_bm01.dsn, true",
    "Issue145-smoothieboard.dsn, false",
    "Issue153-wavefolder.dsn, false",
    "Issue054-tairakb.dsn, true",
    "Synthetic-HardMix-6L.dsn, false",
    "Issue367-Charger.dsn, true",
    "Issue732-CM5_MINIMA_3.dsn, false"
  })
  void matchesTheOverlapQuery(String fixture, boolean route) {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setRouterEnabled(route);
    testSettingsSource.setFanoutEnabled(route);
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies("none");
    RoutingJob job = getRoutingJob(fixture, testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 2;
    job = runRoutingJob(job);
    assertNotNull(job.board, "the board loads");
    int areas = 0;
    for (var it = job.board.itemList.startReadObject(); ; ) {
      var object = job.board.itemList.readObject(it);
      if (object == null) {
        break;
      }
      if (object instanceof ConductionArea area) {
        assertEquals(
            contactsFromOverlapQuery(area), area.getNormalContacts(), fixture + " " + area);
        areas++;
      }
    }
    System.out.println(fixture + ": " + areas + " conduction areas compared");
  }
}
