package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The optimizer stage's search step limit ({@code optimizer.max_steps}). */
class OptimizerStepLimitTest {

  @Test
  void stopsOnceTheStageHasUsedTheLimit() {
    assertTrue(BatchOptimizer.stepLimitReached(1_000L, 1_000L));
    assertTrue(BatchOptimizer.stepLimitReached(1_000L, 5_000L));
    assertFalse(BatchOptimizer.stepLimitReached(1_000L, 999L));
  }

  @Test
  void isOffWithoutALimit() {
    assertFalse(BatchOptimizer.stepLimitReached(0L, 5_000L));
    assertFalse(BatchOptimizer.stepLimitReached(-1L, 5_000L));
  }
}
