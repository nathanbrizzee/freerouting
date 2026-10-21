package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.drc.ClearanceBaseline;
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingFallbackTest {

  @Test
  void defaultStrategiesStartWithTheOnesThatHaveImprovedBoards() {
    RouterSettings defaults = new DefaultSettings().getSettings();
    List<String> names = new ArrayList<>();
    for (RoutingFallback.Strategy strategy : RoutingFallback.parse(defaults, null)) {
      names.add(strategy.name);
    }
    assertEquals(
        List.of("fanout-retry", "short-escape", "direct-first", "open-escape", "no-fanout"), names);
  }

  @Test
  void noneDisablesFallbackAndUnknownNamesAreSkipped() {
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.autorouter.fallbackStrategies = "none";
    assertTrue(RoutingFallback.parse(settings, null).isEmpty());

    List<String> unknown = new ArrayList<>();
    settings.autorouter.fallbackStrategies = " Open-Escape , bogus, open-escape, fanout-retry";
    List<String> names = new ArrayList<>();
    for (RoutingFallback.Strategy strategy : RoutingFallback.parse(settings, unknown::add)) {
      names.add(strategy.name);
    }
    assertEquals(List.of("open-escape", "fanout-retry"), names);
    assertEquals(List.of("bogus"), unknown);
  }

  @Test
  void aRaceRunsTheRaceList() {
    RouterSettings settings = new DefaultSettings().getSettings();
    assertEquals(names(RoutingFallback.parse(settings, null)), stageNames(settings));
    settings.autorouter.fallbackRace = true;
    assertEquals(
        names(RoutingFallback.parse(settings, null)),
        stageNames(settings),
        "one fallback thread: no race");
    settings.autorouter.fallbackThreads = 0;
    assertEquals(List.of("direct-first", "no-fanout", "seed-1"), stageNames(settings));
    settings.autorouter.fallbackStrategies = "none";
    assertTrue(stageNames(settings).isEmpty(), "none turns the race off too");
  }

  private static List<String> stageNames(RouterSettings settings) {
    return names(RoutingFallback.parseForStage(settings, null));
  }

  private static List<String> names(List<RoutingFallback.Strategy> strategies) {
    return strategies.stream().map(strategy -> strategy.name).toList();
  }

  @Test
  void seedStrategiesSetTheItemOrderSeed() {
    RouterSettings settings = new DefaultSettings().getSettings();
    List<String> unknown = new ArrayList<>();
    settings.autorouter.fallbackStrategies = "seed-3, Seed-1, seed-3, seed-0, seed-x";
    List<RoutingFallback.Strategy> parsed = RoutingFallback.parse(settings, unknown::add);
    assertEquals(2, parsed.size());
    assertEquals("seed-3", parsed.get(0).name);
    assertEquals("seed-1", parsed.get(1).name);
    assertEquals(List.of("seed-0", "seed-x"), unknown);
    RouterSettings copy = settings.clone();
    parsed.get(0).apply(copy);
    assertEquals(3, copy.autorouter.itemOrderSeed);
    assertEquals(null, settings.autorouter.itemOrderSeed);
  }

  @Test
  void directFirstAndOpenEscapeChangeACloneOnly() {
    RouterSettings defaults = new DefaultSettings().getSettings();
    RouterSettings directFirst = defaults.clone();
    RoutingFallback.find("direct-first").apply(directFirst);
    assertTrue(directFirst.usesDirectPinRouting());
    assertFalse(defaults.usesDirectPinRouting());

    RouterSettings openEscape = defaults.clone();
    RoutingFallback.find("open-escape").apply(openEscape);
    assertEquals(0.0, openEscape.fanout.minEscapeLengthMm);
    assertEquals(1000.0, openEscape.fanout.maxEscapeLengthMm);
    assertEquals(1.0, openEscape.autorouter.smdNetViaCostFactor);
    assertEquals(2.5, defaults.fanout.minEscapeLengthMm);

    assertTrue(RoutingFallback.find("fanout-retry").fanoutOnly);
    assertTrue(RoutingFallback.find("short-escape").fanoutOnly);
    assertFalse(RoutingFallback.find("direct-first").fanoutOnly);
  }

  @Test
  void fanoutStrategiesDoNotApplyWhenFanoutDoesNotRun() {
    RouterSettings settings = new DefaultSettings().getSettings();
    List<RoutingFallback.Strategy> parsed = RoutingFallback.parse(settings, null);
    assertTrue(RoutingFallback.applicable(parsed, settings, false).isEmpty());
    assertEquals(5, RoutingFallback.applicable(parsed, settings, true).size());
  }

  @Test
  void outcomeRanksFewerOpensThenFewerNewViolationsThenHigherScore() {
    RoutingFallback.Outcome worse = new RoutingFallback.Outcome("a", 2, 0, 0, 10, 0);
    RoutingFallback.Outcome better = new RoutingFallback.Outcome("b", 1, 0, 0, 1, 0);
    assertTrue(RoutingFallback.Outcome.compare(better, worse) < 0);
    RoutingFallback.Outcome dirty = new RoutingFallback.Outcome("c", 1, 3, 3, 50, 0);
    assertTrue(RoutingFallback.Outcome.compare(better, dirty) < 0);
    assertFalse(RoutingFallback.Outcome.canReplace(dirty, worse, 1));
    assertTrue(RoutingFallback.Outcome.canReplace(better, worse, 0));
  }

  @Test
  void designBlockedOpensCountAsComplete() {
    RoutingFallback.Outcome blocked = new RoutingFallback.Outcome("default", 2, 0, 0, 1, 2);
    assertTrue(blocked.isCompleteAndClean());
  }

  @Test
  void retryableStopsAreTheRouterGivingUp() {
    assertTrue(RoutingFallback.isRetryable(BatchAutorouter.StopReason.STAGNATION));
    assertTrue(RoutingFallback.isRetryable(BatchAutorouter.StopReason.NO_IMPROVEMENT));
    assertTrue(RoutingFallback.isRetryable(BatchAutorouter.StopReason.COMPLETED));
    assertFalse(RoutingFallback.isRetryable(BatchAutorouter.StopReason.MAX_PASSES));
    assertFalse(RoutingFallback.isRetryable(BatchAutorouter.StopReason.TIMED_OUT));
    assertFalse(RoutingFallback.isRetryable(BatchAutorouter.StopReason.CANCELLED));
    assertFalse(RoutingFallback.isRetryable(BatchAutorouter.StopReason.REPEATED));
    assertFalse(RoutingFallback.isRetryable(BatchAutorouter.StopReason.WORK_BUDGET));
  }

  @Test
  void preExistingViolationsAreNotCountedAsNew() {
    ClearanceViolation violation = new ClearanceViolation(null, null, null, 0, 100, 50);
    List<ClearanceViolation> violations = List.of(violation);
    ClearanceBaseline input = new ClearanceBaseline(violations);
    assertEquals(0, RoutingFallback.Outcome.newOrChangedCount(1, input, violations));
    assertEquals(1, RoutingFallback.Outcome.newOrChangedCount(1, null, violations));
    assertSame(input, RoutingFallback.Outcome.exemptionBaseline(null, input));
  }

  @Test
  void directFirstTreatsNonPositiveFanoutItemLimitAsUnlimited() {
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.fanout.maxItems = 0;
    assertEquals(Integer.MAX_VALUE, DirectPinRouting.fanoutItemLimit(settings));
    settings.fanout.maxItems = 3;
    assertEquals(3, DirectPinRouting.fanoutItemLimit(settings));
  }
}
