package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.BoardLoader;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/**
 * The capacity estimate before routing: 24 connections that must cross a keepout wall through a 2
 * mm gap on two layers (about 13 tracks at 0.3 mm pitch) make that cut over-full; the same board
 * without the wall has room.
 */
class CapacityReportTest extends RoutingFixtureTest {

  private CapacityReport measure(String fixture) {
    RoutingJob job = getRoutingJob(fixture, new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    CapacityReport report = CapacityReport.measure(job.board, job.routerSettings);
    assertNotNull(report);
    return report;
  }

  @Test
  void aWallWithANarrowGapIsAnOverFullCut() {
    CapacityReport report = measure("CapacityWall.dsn");
    CapacityReport.Cut worst = report.cuts().get(0);
    assertTrue(worst.vertical(), worst.toString());
    assertEquals(24, worst.crossing(), worst.toString());
    assertTrue(worst.use() > 1.0, worst.toString());
    String line = report.describe();
    assertTrue(line.startsWith("Capacity before routing: 24 open connection(s)"), line);
    assertTrue(line.contains(" Over-full cuts: x = 30."), line);
    assertTrue(line.contains(" mm, 24 connection(s) must cross where"), line);
  }

  @Test
  void theSameBoardWithoutTheWallHasRoom() {
    CapacityReport report = measure("CapacityOpen.dsn");
    assertTrue(report.cuts().get(0).use() < 0.5, report.cuts().get(0).toString());
    assertEquals(0, report.overTiles(), report.describe());
    assertTrue(report.regions().isEmpty());
    assertTrue(report.overallUse() < CapacityReport.TIGHT, report.describe());
    String line = report.describe();
    assertTrue(line.contains("Fullest cut: "), line);
    assertTrue(!line.contains("Over capacity"), line);
  }
}
