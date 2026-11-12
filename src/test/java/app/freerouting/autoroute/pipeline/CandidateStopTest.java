package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.StoppableThread;
import org.junit.jupiter.api.Test;

/** The stop of an optimizer pass's candidates: the job's stop, or the end of the pass. */
class CandidateStopTest {

  private static StoppableThread job() {
    return new StoppableThread() {
      @Override
      protected void threadAction() {}
    };
  }

  @Test
  void stopsWhenThePassEnds() {
    StoppableThread job = job();
    BatchOptimizer.CandidateStop stop = new BatchOptimizer.CandidateStop(job);
    assertFalse(stop.isStopRequested());
    stop.cancel();
    assertTrue(stop.isStopRequested());
    assertTrue(stop.isStopAutoRouterRequested());
    assertFalse(job.isStopRequested(), "ending a pass must not stop the job");
  }

  @Test
  void followsTheJobsStopAndCountsItsStepsThere() {
    StoppableThread job = job();
    BatchOptimizer.CandidateStop stop = new BatchOptimizer.CandidateStop(job);
    stop.addSearchSteps(1234);
    assertEquals(1234, job.searchSteps());
    job.requestStop();
    assertTrue(stop.isStopRequested());
  }
}
