package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies which pass results count as progress for the stagnation stop. */
class StagnationProgressTest {

  @Test
  void scoreGainAboveThresholdIsProgress() {
    assertTrue(AutorouteBatchLoop.isProgress(900.6f, 10, 900.0f, 10));
  }

  @Test
  void oneMoreConnectionOnALargeBoardIsProgress() {
    // On a board with 3000 connections one routed connection moves the score by about 0.2.
    assertTrue(AutorouteBatchLoop.isProgress(900.2f, 89, 900.0f, 90));
  }

  @Test
  void fewerOpensBoughtWithALowerScoreIsNotProgress() {
    // Fewer opens but new violations: the score fell.
    assertFalse(AutorouteBatchLoop.isProgress(899.0f, 89, 900.0f, 90));
  }

  @Test
  void smallScoreGainWithSameOpensIsNotProgress() {
    assertFalse(AutorouteBatchLoop.isProgress(900.2f, 90, 900.0f, 90));
  }

  @Test
  void firstPassIsProgress() {
    assertTrue(
        AutorouteBatchLoop.isProgress(100.0f, 500, Float.NEGATIVE_INFINITY, Integer.MAX_VALUE));
  }
}
