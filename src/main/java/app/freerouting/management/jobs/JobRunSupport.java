package app.freerouting.management.jobs;

import app.freerouting.autoroute.PadNecks;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.drc.Congestion;
import app.freerouting.drc.OpenConnections;
import app.freerouting.logger.FRLogger;
import app.freerouting.util.TextManager;
import app.freerouting.util.gson.GsonProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

/**
 * What a routing job does the same way whether it runs headless or in the GUI: the job time limit
 * and search step budget, the live status file and the stop file, the end-of-job report in the log,
 * and the result manifest. An agent that launches Freerouting with the GUI visible gets the same
 * files and log lines as from a headless run.
 */
public final class JobRunSupport {

  /** The longest job time limit honoured, in seconds. */
  static final long MAX_TIMEOUT = 24 * 60 * 60;

  private static final long STATUS_FILE_INTERVAL_MILLIS = 2000;
  private static final int OPEN_CONNECTIONS_LOGGED = 50;
  private static final int PLACEMENT_HINTS_LOGGED = 10;
  private static final int REGIONS_LOGGED = 5;
  private static volatile boolean statusFileWarned;

  private JobRunSupport() {}

  /**
   * Sets the job's clock limit from {@code job_timeout} (at most 24 hours, counted from {@code
   * job.startedAt}) and the thread's search step budget from {@code max_search_steps}.
   */
  public static void applyLimits(RoutingJob job, StoppableThread thread) {
    if (job.startedAt == null) {
      job.startedAt = Instant.now();
    }
    Long timeout = TextManager.parseTimespanString(job.routerSettings.jobTimeoutString);
    if (timeout != null) {
      job.timeoutAt = job.startedAt.plusSeconds(Math.min(timeout, MAX_TIMEOUT));
    }
    if (thread != null
        && job.routerSettings.maxSearchSteps != null
        && job.routerSettings.maxSearchSteps > 0) {
      thread.setSearchStepBudget(job.routerSettings.maxSearchSteps);
      job.logInfo(
          "Search step budget: the job stops after "
              + job.routerSettings.maxSearchSteps
              + " maze search steps (router.max_search_steps).");
    }
  }

  /** Whether the job's clock limit has passed. */
  public static boolean clockExpired(RoutingJob job) {
    return job.timeoutAt != null && !Instant.now().isBefore(job.timeoutAt);
  }

  /**
   * Watches a job that has no CLI wait loop (a GUI run): it rewrites the status file every two
   * seconds, stops the job when the stop file appears or the clock limit passes, and ends when the
   * job reaches a terminal state.
   */
  public static final class Watcher {
    private final RoutingJob job;
    private volatile boolean stopFileSeen;
    private volatile boolean clockStopped;

    private Watcher(RoutingJob job) {
      this.job = job;
    }

    /** Starts a daemon watcher for job. */
    public static Watcher start(RoutingJob job) {
      Watcher watcher = new Watcher(job);
      Thread thread = new Thread(watcher::run, "job-watcher-" + job.shortName);
      thread.setDaemon(true);
      thread.start();
      return watcher;
    }

    /** Whether the stop file stopped the job. */
    public boolean stopFileSeen() {
      return this.stopFileSeen;
    }

    /** Whether the clock limit stopped the job. */
    public boolean clockStopped() {
      return this.clockStopped;
    }

    private void run() {
      long lastStatusWrite = 0;
      while (this.job.state == null || !this.job.state.isTerminal()) {
        long now = System.currentTimeMillis();
        if (now - lastStatusWrite >= STATUS_FILE_INTERVAL_MILLIS) {
          writeStatusFile(this.job);
          lastStatusWrite = now;
          if (!this.stopFileSeen && stopFileExists(this.job)) {
            this.stopFileSeen = true;
            FRLogger.info(
                "Stop file '"
                    + this.job.routerSettings.stopFilePath
                    + "' found: stopping after the current connection and writing the best"
                    + " board.");
            this.job.status().stopped("STOP_FILE");
            requestStop();
          }
        }
        if (!this.clockStopped && clockExpired(this.job)) {
          this.clockStopped = true;
          this.job.logInfo("The job time limit (router.job_timeout) has passed; stopping.");
          requestStop();
        }
        try {
          Thread.sleep(250);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    private void requestStop() {
      if (this.job.thread != null) {
        this.job.thread.requestStop();
      }
    }
  }

  /**
   * The terminal state of a job without the CLI's bookkeeping: timed out at the step budget or the
   * clock limit, cancelled when stopped for another reason than the stop file, else completed.
   */
  public static RoutingJobState terminalState(
      RoutingJob job, StoppableThread thread, Watcher watcher) {
    if (thread != null && thread.isSearchStepBudgetReached()) {
      job.logInfo(
          "The job stopped at its search step budget after "
              + thread.searchSteps()
              + " steps (router.max_search_steps).");
      return RoutingJobState.TIMED_OUT;
    }
    if (watcher != null && watcher.clockStopped()) {
      return RoutingJobState.TIMED_OUT;
    }
    if (thread != null
        && thread.isStopRequested()
        && (watcher == null || !watcher.stopFileSeen())) {
      return RoutingJobState.CANCELLED;
    }
    return RoutingJobState.COMPLETED;
  }

  /**
   * Logs the end of a job: its state and elapsed time, then, when connections are open, the open
   * connections, placement hints, crowded regions and verdict, and the pad necks the board uses.
   */
  public static void logReport(RoutingJob job, String details) {
    double seconds =
        job.startedAt != null && job.finishedAt != null
            ? java.time.Duration.between(job.startedAt, job.finishedAt).toMillis() / 1000.0
            : 0;
    job.logInfo(
        "Job '"
            + job.shortName
            + "' finished with state: "
            + job.state
            + (details != null ? details : "")
            + " (elapsed: "
            + FRLogger.formatDuration(seconds)
            + ", finished at UTC: "
            + job.finishedAt
            + ").");
    if (job.board == null) {
      return;
    }
    List<OpenConnections.Entry> open = OpenConnections.of(job.board);
    if (!open.isEmpty()) {
      job.logInfo(OpenConnections.summary(open, OPEN_CONNECTIONS_LOGGED));
      List<OpenConnections.Hint> hints = OpenConnections.hints(open);
      for (int i = 0; i < Math.min(PLACEMENT_HINTS_LOGGED, hints.size()); i++) {
        job.logInfo("Placement hint: " + hints.get(i));
      }
      for (Congestion.Region region : Congestion.regions(job.board, open, REGIONS_LOGGED)) {
        job.logInfo("Crowded region: " + region);
      }
      job.logInfo("Verdict: " + OpenConnections.verdict(open));
    }
    for (String neck : PadNecks.used(job.board)) {
      job.logInfo("Pad neck used: " + neck);
    }
  }

  /**
   * Rewrites the live status file, if one was requested, through a temporary file and a rename, so
   * a reader never sees a half-written file. Failures are logged once per run and otherwise
   * ignored: the status file must never stop a routing job.
   */
  public static void writeStatusFile(RoutingJob job) {
    if (job.routerSettings == null
        || job.routerSettings.statusFilePath == null
        || job.routerSettings.statusFilePath.isBlank()) {
      return;
    }
    Path target = Path.of(job.routerSettings.statusFilePath);
    try {
      Path parent = target.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path temp = target.resolveSibling(target.getFileName() + ".tmp");
      Files.writeString(
          temp, GsonProvider.GSON.toJson(job.status().snapshot()), StandardCharsets.UTF_8);
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | RuntimeException e) {
      if (!statusFileWarned) {
        statusFileWarned = true;
        FRLogger.warn("Couldn't write the status file '" + target + "': " + e.getMessage());
      }
    }
  }

  /** Whether the job's stop file is configured and present. */
  public static boolean stopFileExists(RoutingJob job) {
    String path = job.routerSettings != null ? job.routerSettings.stopFilePath : null;
    return path != null && !path.isBlank() && Files.exists(Path.of(path));
  }

  /**
   * The process exit code for a finished job: 0 when it completed or timed out and its output was
   * written, 1 otherwise.
   */
  public static int exitCode(RoutingJob job, boolean outputWritten) {
    return outputWritten
            && (job.state == RoutingJobState.COMPLETED || job.state == RoutingJobState.TIMED_OUT)
        ? 0
        : 1;
  }

  /** Writes the result manifest when {@code result_json} is set; failures are logged. */
  public static void writeResultManifest(
      RoutingJob job, String inputFile, boolean outputWritten, int exitCode, Integer cpuScore) {
    if (job.routerSettings == null
        || job.routerSettings.resultJsonPath == null
        || job.routerSettings.resultJsonPath.isBlank()) {
      return;
    }
    try {
      RoutingResultManifest manifest =
          RoutingResultManifest.fromJob(job, inputFile, outputWritten, exitCode, cpuScore);
      RoutingResultManifest.write(Path.of(job.routerSettings.resultJsonPath), manifest);
    } catch (IOException e) {
      FRLogger.error(
          "Couldn't write routing result manifest to '" + job.routerSettings.resultJsonPath + "'",
          e);
    }
  }
}
