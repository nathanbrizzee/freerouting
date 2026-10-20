package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The search step budget stops the whole job once the counted steps reach it. */
class SearchStepBudgetThreadTest {

  private static StoppableThread thread() {
    return new StoppableThread() {
      @Override
      protected void threadAction() {}
    };
  }

  @Test
  void stopsTheJobAtTheBudget() {
    StoppableThread thread = thread();
    thread.setSearchStepBudget(100);
    thread.addSearchSteps(60);
    assertFalse(thread.isStopRequested());
    thread.addSearchSteps(40);
    assertTrue(thread.isStopRequested());
    assertTrue(thread.isSearchStepBudgetReached());
    assertEquals(100, thread.searchSteps());
  }

  @Test
  void noBudgetOnlyCounts() {
    StoppableThread thread = thread();
    thread.addSearchSteps(1_000_000);
    assertFalse(thread.isStopRequested());
    assertFalse(thread.isSearchStepBudgetReached());
    assertEquals(1_000_000, thread.searchSteps());
  }
}
