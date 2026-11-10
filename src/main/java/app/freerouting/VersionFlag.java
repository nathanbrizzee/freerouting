package app.freerouting;

import app.freerouting.constants.Constants;

/**
 * {@code --version} (or {@code -version}, as {@code java} spells it): prints the version and the
 * build date and exits, without opening the GUI or starting a job. Agents try it first to check
 * which jar they run.
 */
public final class VersionFlag {

  private VersionFlag() {}

  /** Whether the arguments ask for the version. */
  public static boolean requested(String[] args) {
    if (args == null) {
      return false;
    }
    for (String arg : args) {
      if ("--version".equalsIgnoreCase(arg) || "-version".equalsIgnoreCase(arg)) {
        return true;
      }
    }
    return false;
  }

  /** The line printed: {@code Freerouting 2.5.0 (build-date: 2026-01-01)}. */
  public static String text() {
    return "Freerouting "
        + Constants.FREEROUTING_VERSION
        + " (build-date: "
        + Constants.FREEROUTING_BUILD_DATE
        + ")";
  }

  /** Prints the version line to standard output; returns the exit code. */
  public static int print() {
    System.out.println(text());
    return 0;
  }
}
