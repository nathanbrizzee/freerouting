package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.logger.FRLogger;
import app.freerouting.logger.LogEntry;
import app.freerouting.settings.sources.TestingSettings;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code autorouter.first_nets} routes the named nets first in the first pass, and the {@code
 * failed-first} strategy routes the input board again with the nets the best board left open first.
 */
class FailedFirstTest extends RoutingFixtureTest {

  private static String logSince(Instant start) {
    return Arrays.stream(FRLogger.getLogEntries().getEntries(start, null))
        .map(LogEntry::getMessage)
        .collect(Collectors.joining("\n"));
  }

  @Test
  void firstNetsGoFirstInTheFirstPass() {
    Instant start = Instant.now();
    RoutingJob job = getRoutingJob("UnreachableNarrowGap.dsn", new TestingSettings());
    job.routerSettings.setFanoutEnabled(false);
    job.routerSettings.autorouter.firstNets = "B, missing";
    runRoutingJob(job);
    String log = logSince(start);
    assertTrue(log.contains(" of the 1 net(s) in first_nets first."), log);
  }

  @Test
  void failedFirstIsAKnownStrategy() {
    assertNotNull(RoutingFallback.find(RoutingFallback.FAILED_FIRST));
  }

  /** 24 connections must cross a wall where about 10 tracks fit, so some stay open. */
  @Test
  @Tag("slow")
  void failedFirstRoutesTheInputAgainWithTheOpenNetsFirst() {
    Instant start = Instant.now();
    RoutingJob job = getRoutingJob("CapacityWall.dsn", new TestingSettings());
    job.routerSettings.setFanoutEnabled(false);
    job.routerSettings.autorouter.fallbackStrategies = RoutingFallback.FAILED_FIRST;
    job.routerSettings.autorouter.fallbackRace = false;
    job.routerSettings.autorouter.gridFinisher = false;
    runRoutingJob(job);
    String log = logSince(start);
    assertTrue(
        log.contains("Routing fallback 'failed-first': routing the input board again with the "),
        log);
    assertTrue(log.contains(" net(s) in first_nets first."), log);
  }
}
