package app.freerouting.core;

import app.freerouting.datastructures.Stoppable;

/** Runs an interactive action in a separate thread that can be stopped by the user. */
public abstract class StoppableThread extends Thread implements Stoppable {

  private StopRequestState stopRequestState = StopRequestState.NONE;

  /** Maze search steps the job may use, or 0 for no limit; see {@link #addSearchSteps(long)}. */
  private long searchStepBudget;

  private long searchSteps;
  private boolean searchStepBudgetReached;

  /** Creates a new stoppable thread. */
  protected StoppableThread() {}

  /** Performs the thread's action. */
  protected abstract void threadAction();

  @Override
  public void run() {
    threadAction();
  }

  /** Requests the thread to stop, including fanout, Autorouter, and Optimizer tasks. */
  @Override
  public synchronized void requestStop() {
    this.stopRequestState = StopRequestState.ALL;
  }

  @Override
  public synchronized boolean isStopRequested() {
    return this.stopRequestState == StopRequestState.ALL;
  }

  /**
   * Sets the job's search step budget ({@code router.max_search_steps}); 0 or less means no limit.
   */
  public synchronized void setSearchStepBudget(long steps) {
    this.searchStepBudget = Math.max(0, steps);
  }

  /**
   * Adds the steps of a finished search. When the total reaches the budget, the whole job is asked
   * to stop, as a timeout does. The steps depend only on the board and the settings, so the job
   * stops after the same search on every run, whatever the machine load.
   */
  public synchronized void addSearchSteps(long steps) {
    this.searchSteps += steps;
    if (this.searchStepBudget > 0
        && this.searchSteps >= this.searchStepBudget
        && !this.searchStepBudgetReached) {
      this.searchStepBudgetReached = true;
      this.stopRequestState = StopRequestState.ALL;
    }
  }

  /** The search step budget, or 0 for no limit. */
  public synchronized long searchStepBudget() {
    return this.searchStepBudget;
  }

  /** Maze search steps used so far by the fanout and autorouter searches of this thread. */
  public synchronized long searchSteps() {
    return this.searchSteps;
  }

  /** Whether the search step budget stopped the job. */
  public synchronized boolean isSearchStepBudgetReached() {
    return this.searchStepBudgetReached;
  }

  /** Requests the Autorouter to stop while other tasks continue. */
  public synchronized void requestStopAutoRouter() {
    if (this.stopRequestState == StopRequestState.NONE) {
      this.stopRequestState = StopRequestState.AUTO_ROUTER_ONLY;
    }
  }

  /** Returns whether the Autorouter should stop. */
  public synchronized boolean isStopAutoRouterRequested() {
    return this.stopRequestState != StopRequestState.NONE;
  }

  /**
   * Clears an autorouter-only stop so another fallback attempt can run. A stop of the whole job is
   * left in place.
   */
  public synchronized void resetAutoRouterStopRequest() {
    if (this.stopRequestState == StopRequestState.AUTO_ROUTER_ONLY) {
      this.stopRequestState = StopRequestState.NONE;
    }
  }

  /**
   * Resets the Autorouter stop request if it was set to AUTO_ROUTER_ONLY, allowing subsequent
   * stages (such as the optimizer) or retry attempts to run. Does not clear a full stop request
   * (ALL).
   */
  public synchronized void resetStopAutoRouterRequest() {
    resetAutoRouterStopRequest();
  }
}
