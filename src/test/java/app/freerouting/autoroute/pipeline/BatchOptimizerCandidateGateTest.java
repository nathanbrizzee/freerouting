package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void moreCompleteUglierCandidateIsAccepted() {
    // A private board: two connections closed (26 -> 24 open), score 673.40 -> 672.85.
    assertNull(
        BatchOptimizer.optimizerCandidateRejectionReason(
            26, 0, 673.40f, statistics(24, 0), 672.85f));
    assertNull(
        BatchOptimizer.optimizerCandidateRejectionReason(1, 0, 500.0f, statistics(0, 0), 400.0f));
  }

  @Test
  void moreCompleteCandidateWithANewViolationIsRejected() {
    assertEquals(
        "DRC_COUNT_REGRESSION",
        BatchOptimizer.optimizerCandidateRejectionReason(26, 0, 673.40f, statistics(24, 1), 700f));
  }

  @Test
  void finalBoardWithFewerOpenIsNotReplacedForItsScore() {
    assertFalse(BatchOptimizer.finalBoardRegressed(673.40f, 26, 0, 672.85f, 24, 0));
    assertTrue(BatchOptimizer.finalBoardRegressed(673.40f, 24, 0, 672.85f, 24, 0));
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
