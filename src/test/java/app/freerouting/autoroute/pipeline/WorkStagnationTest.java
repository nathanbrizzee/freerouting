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

  @Test
  void gainStopFiresWhenTheLastTwoPassesGainLessThanTheRatio() {
    // 57 -> 46 is a gain of 19 per cent: below a fifth, so it stops.
    assertTrue(AutorouteBatchLoop.gainStagnated(0.2, true, 8, 200_000_000L, 57, 46));
    // 46 -> 38 is a gain of 17 per cent: also below a fifth.
    assertTrue(AutorouteBatchLoop.gainStagnated(0.2, true, 10, 200_000_000L, 46, 38));
    // 60 -> 40 is a third: it goes on.
    assertFalse(AutorouteBatchLoop.gainStagnated(0.2, true, 9, 200_000_000L, 60, 40));
  }

  @Test
  void gainStopWaitsForPassEightAndNeedsOpenConnections() {
    assertFalse(AutorouteBatchLoop.gainStagnated(0.2, true, 7, 200_000_000L, 50, 50));
    assertFalse(AutorouteBatchLoop.gainStagnated(0.2, true, 9, 200_000_000L, 50, 0));
    assertFalse(AutorouteBatchLoop.gainStagnated(0.2, false, 9, 200_000_000L, 50, 50));
  }

  @Test
  void gainStopLeavesCheapBoardsAlone() {
    // 23 million steps by pass 8: the passes take seconds, so a slow gain is no reason to stop.
    assertFalse(AutorouteBatchLoop.gainStagnated(0.2, true, 8, 23_000_000L, 57, 46));
  }

  @Test
  void gainStopIsOffWithoutARatio() {
    assertFalse(AutorouteBatchLoop.gainStagnated(-1.0, true, 9, 200_000_000L, 50, 50));
    assertFalse(AutorouteBatchLoop.gainStagnated(0.0, true, 9, 200_000_000L, 50, 50));
  }

  @Test
  void theStepLimitStopsPassesThatSpentItWithConnectionsOpen() {
    assertTrue(AutorouteBatchLoop.stepLimitReached(250_000_000L, true, 12, 250_000_000L));
    assertFalse(AutorouteBatchLoop.stepLimitReached(250_000_000L, true, 12, 249_999_999L));
    assertFalse(AutorouteBatchLoop.stepLimitReached(250_000_000L, true, 0, 300_000_000L));
    assertFalse(AutorouteBatchLoop.stepLimitReached(250_000_000L, false, 12, 300_000_000L));
    assertFalse(AutorouteBatchLoop.stepLimitReached(0L, true, 12, 300_000_000L));
    assertFalse(AutorouteBatchLoop.stepLimitReached(-1L, true, 12, 300_000_000L));
  }
}
