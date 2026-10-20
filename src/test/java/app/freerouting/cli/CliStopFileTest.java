package app.freerouting.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.settings.RouterSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI stops a job when the configured stop file appears, and only then. */
class CliStopFileTest {

  @Test
  void stopFileCountsOnlyOnceItExists(@TempDir Path dir) throws IOException {
    RoutingJob job = new RoutingJob();
    job.routerSettings = new RouterSettings();
    assertFalse(CliRunner.stopFileExists(job), "no stop file configured");

    Path stop = dir.resolve("STOP");
    job.routerSettings.stopFilePath = stop.toString();
    assertFalse(CliRunner.stopFileExists(job), "configured but not created yet");

    Files.writeString(stop, "");
    assertTrue(CliRunner.stopFileExists(job));
  }
}
