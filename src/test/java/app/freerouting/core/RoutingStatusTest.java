package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the live status an agent reads while a job runs. */
class RoutingStatusTest {

  @Test
  void tracksUnroutedCountsAndAttempts() {
    RoutingStatus status = new RoutingStatus();
    status.stageStarted("fanout");
    status.passLimits(10, 0, null);
    status.passStarted(1, 40, 40);
    status.itemDone(true, "GND");
    status.passFinished(1, 25, 0);
    status.passStarted(2, 25, 25);
    status.passFinished(2, 27, 1);

    Map<String, Object> s = status.snapshot();
    assertEquals("autoroute", s.get("stage"));
    assertEquals(40, s.get("unrouted_start"));
    assertEquals(27, s.get("unrouted_now"));
    assertEquals(25, s.get("unrouted_best"));
    assertEquals(1, s.get("violations_now"));
    assertEquals("GND", s.get("current_net"));
    assertEquals(2, ((List<?>) s.get("passes")).size());

    status.fallbackPlanned(5);
    status.attemptStarted("fanout-retry");
    status.passStarted(1, 30, 30);
    status.passFinished(1, 30, 0);
    s = status.snapshot();
    assertEquals("fanout-retry", s.get("attempt"));
    assertEquals(1, s.get("attempt_number"));
    assertEquals(5, s.get("attempts_planned"));
    assertEquals(25, s.get("unrouted_best"), "the best result is kept across attempts");
  }

  @Test
  void estimateCountsPassesLeftUnderTheStagnationLimit() {
    RoutingStatus status = new RoutingStatus();
    status.passLimits(10, 0, null);
    for (int p = 1; p <= 4; p++) {
      status.passStarted(p, 10, 20);
      status.passFinished(p, 20, 0);
    }
    status.passStarted(5, 10, 20);
    Map<?, ?> e = (Map<?, ?>) status.snapshot().get("estimate");
    assertTrue((Boolean) e.get("available"));
    // The best was reached in pass 1, so at most 10 - 4 = 6 more passes run.
    assertEquals(6, e.get("passes_left_at_most"));
    assertFalse((Boolean) e.get("fallback_included"));
  }

  @Test
  void finishedStatusHasNoEstimate() {
    RoutingStatus status = new RoutingStatus();
    status.passStarted(1, 10, 10);
    status.passFinished(1, 0, 0);
    status.finished("COMPLETED");
    Map<String, Object> s = status.snapshot();
    assertEquals("finished", s.get("stage"));
    assertEquals("COMPLETED", s.get("final_state"));
    assertFalse(s.containsKey("estimate"));
  }
}
