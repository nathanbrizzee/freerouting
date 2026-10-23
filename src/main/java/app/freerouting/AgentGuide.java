package app.freerouting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The guide for AI agents that run Freerouting: how to start a run, what the options do and how to
 * read the log and the result file. The build copies {@code docs/agent-guide.md} into the jar, so
 * {@code --agent-guide} prints the guide of the version that runs.
 */
public final class AgentGuide {

  /** Command-line flag that prints the guide and exits. */
  public static final String FLAG = "--agent-guide";

  /** Log line that tells an agent reading only a log where the guide is. */
  public static final String LOG_POINTER =
      "AI agents: run 'java -jar freerouting.jar "
          + FLAG
          + "' for how to run Freerouting and read this log.";

  static final String RESOURCE = "/app/freerouting/agent-guide.md";

  private AgentGuide() {}

  /** Whether the arguments ask for the guide. */
  public static boolean requested(String[] args) {
    if (args == null) {
      return false;
    }
    for (String arg : args) {
      if (FLAG.equalsIgnoreCase(arg)) {
        return true;
      }
    }
    return false;
  }

  /** The guide's text, or null when the jar was built without it. */
  public static String text() {
    try (InputStream in = AgentGuide.class.getResourceAsStream(RESOURCE)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Prints the guide to standard output, before logging starts so nothing else is mixed in.
   *
   * @return the process exit code: 0 when printed, 1 when the jar has no guide
   */
  public static int print() {
    String guide = text();
    if (guide == null) {
      System.err.println("This build has no agent guide (" + RESOURCE + ").");
      return 1;
    }
    System.out.print(guide);
    System.out.flush();
    return 0;
  }
}
