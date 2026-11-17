package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.freerouting.core.RoutingJob;
import app.freerouting.drc.NetWidths;
import app.freerouting.drc.OpenConnections;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 0.3 mm power net G has two pads 2 mm apart (1.4 mm edge to edge) behind a keepout wall with a
 * 0.6 mm gap, where 0.3 mm plus clearance on both sides needs 0.7 mm and a 0.15 mm neck needs 0.55
 * mm. The other nets are 0.2 mm signals, so G's class is wider than the signal width. The stub is
 * short, so G may neck down along it ({@code neck_stub_max_um}); with the exception off it stays
 * open.
 */
class NeckWideStubTest extends RoutingFixtureTest {

  private RoutingJob route(Double stubMaxUm) {
    TestingSettings settings = new TestingSettings();
    settings.setFanoutEnabled(false);
    settings.setOptimizerEnabled(false);
    settings.setMaxPasses(5);
    settings.setJobTimeoutString("00:01:00");
    settings.setNeckWidthUm(150);
    if (stubMaxUm != null) {
      settings.setNeckStubMaxUm(stubMaxUm);
    }
    return runRoutingJob(getRoutingJob("NeckWideStub.dsn", settings));
  }

  private static List<String> openNets(RoutingJob job) {
    return OpenConnections.of(job.board).stream().map(entry -> entry.net).distinct().toList();
  }

  @Test
  void aShortStubOfAWideNetNecksDown() {
    RoutingJob job = route(null);

    assertEquals(List.of(), openNets(job), "the stub closes at the neck width");
    assertEquals(
        List.of(), NetWidths.narrowed(job.board, null, 1500.0), "a stub is not reported as a run");
  }

  @Test
  void withTheStubExceptionOffTheWideNetStaysOpen() {
    RoutingJob job = route(0.0);

    assertEquals(List.of("G"), openNets(job), "the wide net is not narrowed");
  }

  @Test
  void aStubLimitBelowTheGapStaysOpen() {
    RoutingJob job = route(500.0);

    assertEquals(List.of("G"), openNets(job), "the pads are farther apart than the limit");
  }
}
