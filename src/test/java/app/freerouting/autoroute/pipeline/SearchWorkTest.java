package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.datastructures.TimeLimit;
import org.junit.jupiter.api.Test;

class SearchWorkTest {

  private static TimeLimit after(int steps) {
    TimeLimit limit = TimeLimit.ofSteps(1000);
    for (int i = 0; i < steps; i++) {
      limit.limitExceeded();
    }
    return limit;
  }

  @Test
  void countsAlreadyConnectedItemsApartFromFailedSearches() {
    SearchWork work = new SearchWork();
    work.record(after(10), AutorouteAttemptState.ROUTED, null);
    work.record(after(30), AutorouteAttemptState.FAILED, null);
    work.record(after(0), AutorouteAttemptState.ALREADY_CONNECTED, null);
    work.record(after(0), AutorouteAttemptState.NO_UNCONNECTED_NETS, null);
    assertEquals(
        "Search work: 2 connection searches, 40 steps, 0 stopped at the search limit; routed ones"
            + " took median 10, p99 10, max 10 steps, the costliest tenth 100% of them; 1 did not"
            + " route and took 30 steps (75%); 2 more were already connected.",
        work.describe("connection"));
  }

  @Test
  void saysNothingWhenEveryItemWasAlreadyConnected() {
    SearchWork work = new SearchWork();
    work.record(after(0), AutorouteAttemptState.CONNECTED_TO_PLANE, null);
    assertNull(work.describe("connection"));
  }
}
