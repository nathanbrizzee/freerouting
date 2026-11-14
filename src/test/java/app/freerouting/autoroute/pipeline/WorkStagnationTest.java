package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The work-based pass stop ({@code autorouter.stagnation_work_ratio}). */
class WorkStagnationTest {

  @Test
  void stopsOnceTheWorkSinceTheBestExceedsTheRatio() {
    // Best (10 open) reached at pass 8 after 4 million steps; ratio 1.
    assertFalse(AutorouteBatchLoop.workStagnated(1.0, true, 10, 10, 8, 8_000_000L, 4_000_000L));
    assertTrue(AutorouteBatchLoop.workStagnated(1.0, true, 10, 10, 8, 8_000_001L, 4_000_000L));
    assertTrue(AutorouteBatchLoop.workStagnated(0.5, true, 10, 10, 8, 6_000_001L, 4_000_000L));
  }

  @Test
  void waitsAtLeastTwoPassesAfterTheBest() {
    assertFalse(AutorouteBatchLoop.workStagnated(0.1, true, 10, 9, 8, 99_000_000L, 1_000_000L));
    assertTrue(AutorouteBatchLoop.workStagnated(0.1, true, 10, 10, 8, 99_000_000L, 1_000_000L));
  }

  @Test
  void neverStopsBeforeTheFirstPass() {
    // Best at pass 2 after few steps: far past the ratio by pass 7, but too early to stop.
    assertFalse(AutorouteBatchLoop.workStagnated(1.0, true, 10, 7, 2, 99_000_000L, 1_000L));
    assertTrue(AutorouteBatchLoop.workStagnated(1.0, true, 10, 8, 2, 99_000_000L, 1_000L));
  }

  @Test
  void isOffWithoutARatioOrWithNothingOpen() {
    assertFalse(AutorouteBatchLoop.workStagnated(-1.0, true, 10, 12, 3, 99_000_000L, 1L));
    assertFalse(AutorouteBatchLoop.workStagnated(0.0, true, 10, 12, 3, 99_000_000L, 1L));
    assertFalse(AutorouteBatchLoop.workStagnated(1.0, true, 0, 12, 3, 99_000_000L, 1L));
    assertFalse(AutorouteBatchLoop.workStagnated(1.0, false, 10, 12, 3, 99_000_000L, 1L));
  }
}
