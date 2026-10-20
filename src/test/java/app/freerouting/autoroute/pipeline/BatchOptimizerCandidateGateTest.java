package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.freerouting.core.scoring.BoardStatistics;
import org.junit.jupiter.api.Test;

/** Verifies the optimizer candidate vetoes and score-ranking rule. */
class BatchOptimizerCandidateGateTest {

  @Test
  void aBetterCandidateBelowZeroIsAccepted() {
    // Both scores clamp to 0 for display; ranked unclamped, the smaller penalty wins.
    assertNull(
        BatchOptimizer.optimizerCandidateRejectionReason(0, 0, -239.0f, statistics(0, 0), -120.0f));
    assertEquals(
        "OPTIMIZER_SCORE_NOT_IMPROVED",
        BatchOptimizer.optimizerCandidateRejectionReason(0, 0, -120.0f, statistics(0, 0), -239.0f));
  }

  @Test
  void improvementBelowZeroIsMeasuredAgainstThePenalty() {
    assertEquals(10.0, BatchOptimizer.improvementPercent(500.0f, 550.0f), 1e-9);
    // Score -250 is a penalty of 1250; rising to -125 removes 10% of it.
    assertEquals(10.0, BatchOptimizer.improvementPercent(-250.0f, -125.0f), 1e-9);
    assertEquals(0.0, BatchOptimizer.improvementPercent(0.0f, 0.0f), 1e-9);
  }

  @Test
  void connectivityRegressionVetoesHigherOptimizerScore() {
    BoardStatistics candidate = statistics(1, 0);

    assertEquals(
        "CONNECTIVITY_REGRESSION",
        BatchOptimizer.optimizerCandidateRejectionReason(0, 0, 500.0f, candidate, 900.0f));
  }

  @Test
  void clearanceRegressionVetoesHigherOptimizerScore() {
    BoardStatistics candidate = statistics(0, 1);

    assertEquals(
        "DRC_COUNT_REGRESSION",
        BatchOptimizer.optimizerCandidateRejectionReason(0, 0, 500.0f, candidate, 900.0f));
  }

  @Test
  void moreCompleteUglierCandidateIsRejected() {
    BoardStatistics candidate = statistics(0, 0);

    assertEquals(
        "OPTIMIZER_SCORE_NOT_IMPROVED",
        BatchOptimizer.optimizerCandidateRejectionReason(1, 0, 500.0f, candidate, 400.0f));
  }

  @Test
  void candidateWithNoVetoAndHigherOptimizerScoreIsAccepted() {
    BoardStatistics candidate = statistics(0, 0);

    assertNull(BatchOptimizer.optimizerCandidateRejectionReason(1, 1, 500.0f, candidate, 600.0f));
  }

  private static BoardStatistics statistics(int incompleteCount, int clearanceViolationCount) {
    BoardStatistics statistics = new BoardStatistics();
    statistics.connections.incompleteCount = incompleteCount;
    statistics.clearanceViolations.totalCount = clearanceViolationCount;
    return statistics;
  }
}
