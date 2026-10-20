package app.freerouting.datastructures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A step limit stops after a fixed number of checks and counts them. */
class TimeLimitStepsTest {

  @Test
  void stopsAfterTheGivenNumberOfChecks() {
    TimeLimit limit = TimeLimit.ofSteps(3);
    assertFalse(limit.limitExceeded());
    assertFalse(limit.limitExceeded());
    assertFalse(limit.limitExceeded());
    assertFalse(limit.wasExceeded());
    assertTrue(limit.limitExceeded());
    assertTrue(limit.wasExceeded());
    assertEquals(4, limit.steps());
  }

  @Test
  void multiplyScalesTheSteps() {
    TimeLimit limit = TimeLimit.ofSteps(2);
    limit.multiply(2);
    for (int i = 0; i < 4; i++) {
      assertFalse(limit.limitExceeded(), "check " + (i + 1));
    }
    assertTrue(limit.limitExceeded());
  }

  @Test
  void timeLimitCountsChecksToo() {
    TimeLimit limit = new TimeLimit(60_000);
    assertFalse(limit.limitExceeded());
    assertFalse(limit.limitExceeded());
    assertEquals(2, limit.steps());
    assertFalse(limit.wasExceeded());
  }
}
