package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.drc.NetWidths;
import app.freerouting.drc.OpenConnections;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two nets cross a keepout wall through gaps of 0.7 mm: the 0.4 mm signal net S and the 1.0 mm
 * power net P. Neither fits at its class width (clearance 0.2 mm on both sides); both would at a
 * 0.15 mm neck. Only the signal net may be narrowed along the run: the power net stays open and the
 * report names it, unless {@code neck_max_class_width_um} admits its class.
 */
class NeckWideClassTest extends RoutingFixtureTest {

  private RoutingJob route(TestingSettings settings) {
    settings.setFanoutEnabled(false);
    settings.setOptimizerEnabled(false);
    settings.setMaxPasses(5);
    settings.setJobTimeoutString("00:01:00");
    return runRoutingJob(getRoutingJob("NeckWideClass.dsn", settings));
  }

  private static List<String> openNets(RoutingJob job) {
    return OpenConnections.of(job.board).stream().map(entry -> entry.net).distinct().toList();
  }

  private static List<String> narrowedNets(RoutingJob job, Double maxClassWidthUm) {
    return NetWidths.narrowed(job.board, maxClassWidthUm).stream()
        .map(NetWidths.Narrowed::net)
        .toList();
  }

  @Test
  void theDefaultFallbackNecksOnlyTheSignalNet() {
    // The fallback stage on, as by default: the race list holds continue-auto-neck.
    TestingSettings settings = new TestingSettings();
    settings.setFallbackStrategies(DefaultSettings.DEFAULT_FALLBACK_STRATEGIES);
    RoutingJob job = route(settings);

    assertEquals(List.of("P"), openNets(job), "only the power net is open");
    assertEquals(List.of("S"), narrowedNets(job, null), "the signal net is narrowed and reported");
    List<String> wideOpen = NetWidths.wideOpen(job.board, OpenConnections.of(job.board), null);
    assertEquals(1, wideOpen.size(), "the open power net is reported: " + wideOpen);
    assertTrue(wideOpen.get(0).startsWith("P (class POWER, 1.000 mm)"), wideOpen.get(0));
  }

  @Test
  void anExplicitNeckWidthAlsoLeavesTheWideNetAlone() {
    TestingSettings settings = new TestingSettings();
    settings.setNeckWidthUm(150);
    RoutingJob job = route(settings);

    assertEquals(List.of("P"), openNets(job), "only the power net is open");
    assertEquals(List.of("S"), narrowedNets(job, null), "the signal net is narrowed");
  }

  @Test
  void aWiderSignalWidthAdmitsThePowerClass() {
    TestingSettings settings = new TestingSettings();
    settings.setNeckWidthUm(150);
    settings.setNeckMaxClassWidthUm(1000);
    RoutingJob job = route(settings);

    assertEquals(List.of(), openNets(job), "both nets route");
    assertEquals(List.of("P", "S"), narrowedNets(job, 1000.0), "both are narrowed and reported");
    assertTrue(
        NetWidths.narrowed(job.board, 1000.0).stream().noneMatch(NetWidths.Narrowed::wide),
        "neither class is wider than the signal width given");
  }
}
