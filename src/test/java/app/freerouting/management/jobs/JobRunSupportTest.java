package app.freerouting.management.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.StoppableThread;
import app.freerouting.settings.RouterSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A GUI run gets the headless limits, status file, stop file and terminal states. */
class JobRunSupportTest {

  /** A thread that never runs; only its stop and step state matter here. */
  private static final class IdleThread extends StoppableThread {
    @Override
    protected void threadAction() {}
  }

  private static RoutingJob job() {
    RoutingJob job = new RoutingJob();
    job.routerSettings = new RouterSettings();
    job.state = RoutingJobState.RUNNING;
    return job;
  }

  @Test
  void limitsComeFromTheSettings() {
    RoutingJob job = job();
    job.routerSettings.jobTimeoutString = "00:10:00";
    job.routerSettings.maxSearchSteps = 1000L;
    IdleThread thread = new IdleThread();
    JobRunSupport.applyLimits(job, thread);
    assertNotNull(job.timeoutAt);
    assertEquals(600, job.timeoutAt.getEpochSecond() - job.startedAt.getEpochSecond());
    thread.addSearchSteps(1000);
    assertTrue(thread.isSearchStepBudgetReached());
    assertEquals(RoutingJobState.TIMED_OUT, JobRunSupport.terminalState(job, thread, null));
  }

  @Test
  void stopIsCancelUnlessTheStopFileOrClockCausedIt() {
    RoutingJob job = job();
    IdleThread thread = new IdleThread();
    assertEquals(RoutingJobState.COMPLETED, JobRunSupport.terminalState(job, thread, null));
    thread.requestStop();
    assertEquals(RoutingJobState.CANCELLED, JobRunSupport.terminalState(job, thread, null));
  }

  @Test
  void theWatcherWritesStatusAndStopsOnTheStopFileAndTheClock(@TempDir Path dir)
      throws IOException, InterruptedException {
    RoutingJob job = job();
    Path status = dir.resolve("status.json");
    Path stop = dir.resolve("STOP");
    job.routerSettings.statusFilePath = status.toString();
    job.routerSettings.stopFilePath = stop.toString();
    IdleThread thread = new IdleThread();
    job.thread = thread;
    Files.writeString(stop, "");
    JobRunSupport.Watcher watcher = JobRunSupport.Watcher.start(job);
    for (int i = 0; i < 100 && !watcher.stopFileSeen(); i++) {
      Thread.sleep(50);
    }
    assertTrue(watcher.stopFileSeen());
    assertTrue(thread.isStopRequested());
    assertTrue(Files.exists(status), "status file written");
    assertEquals(RoutingJobState.COMPLETED, JobRunSupport.terminalState(job, thread, watcher));

    RoutingJob timed = job();
    timed.startedAt = Instant.now().minusSeconds(10);
    timed.timeoutAt = Instant.now().minusSeconds(1);
    IdleThread timedThread = new IdleThread();
    timed.thread = timedThread;
    JobRunSupport.Watcher clock = JobRunSupport.Watcher.start(timed);
    for (int i = 0; i < 100 && !clock.clockStopped(); i++) {
      Thread.sleep(50);
    }
    assertTrue(timedThread.isStopRequested());
    assertEquals(RoutingJobState.TIMED_OUT, JobRunSupport.terminalState(timed, timedThread, clock));
    job.state = RoutingJobState.COMPLETED;
    timed.state = RoutingJobState.TIMED_OUT;
  }
}
