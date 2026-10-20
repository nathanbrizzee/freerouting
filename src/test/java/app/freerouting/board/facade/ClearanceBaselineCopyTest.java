package app.freerouting.board.facade;

import static org.junit.jupiter.api.Assertions.assertSame;

import app.freerouting.autoroute.BoardHistory;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.ClearanceBaseline;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A board copy keeps the input-board violation baseline. Without it every pre-existing violation
 * counts as new on the copy, and the fallback stage can never replace a board on a design that
 * loads with violations.
 */
class ClearanceBaselineCopyTest extends RoutingFixtureTest {

  @Test
  void copiesAndRestoresKeepTheBaseline() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(1);
    testSettingsSource.setJobTimeoutString("00:01:00");
    RoutingJob job = runRoutingJob(getRoutingJob("NeckFloor.dsn", testSettingsSource));
    ClearanceBaseline baseline = new ClearanceBaseline(List.of());
    job.board.clearanceBaseline = baseline;

    assertSame(baseline, job.board.deepCopy().clearanceBaseline);

    BoardHistory history = new BoardHistory(job.routerSettings);
    history.add(job.board);
    assertSame(baseline, history.restoreBestBoard().clearanceBaseline);
  }
}
