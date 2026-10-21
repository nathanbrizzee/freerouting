package app.freerouting.logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.settings.DebugSettings;
import app.freerouting.settings.GlobalSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hot paths skip building trace messages when no granular trace can be seen: trace logging is off
 * and the GUI debugger neither steps nor delays.
 */
class GranularTraceWantedTest {

  private GlobalSettings savedSettings;
  private boolean savedTrace;
  private boolean savedStep;
  private int savedDelay;

  @BeforeEach
  void save() {
    savedSettings = Freerouting.globalSettings;
    if (Freerouting.globalSettings == null) {
      Freerouting.globalSettings = new GlobalSettings();
    }
    DebugSettings debug = Freerouting.globalSettings.debugSettings;
    savedTrace = FRLogger.granularTraceEnabled;
    savedStep = debug.singleStepExecution;
    savedDelay = debug.traceInsertionDelay;
    FRLogger.granularTraceEnabled = false;
    debug.singleStepExecution = false;
    debug.traceInsertionDelay = 0;
  }

  @AfterEach
  void restore() {
    DebugSettings debug = Freerouting.globalSettings.debugSettings;
    FRLogger.granularTraceEnabled = savedTrace;
    debug.singleStepExecution = savedStep;
    debug.traceInsertionDelay = savedDelay;
    Freerouting.globalSettings = savedSettings;
  }

  @Test
  void notWantedWhenTracingAndDebuggingAreOff() {
    assertFalse(FRLogger.isGranularTraceWanted());
  }

  @Test
  void wantedWhileTheDebuggerStepsOrDelays() {
    Freerouting.globalSettings.debugSettings.singleStepExecution = true;
    assertTrue(FRLogger.isGranularTraceWanted(), "single stepping");
    Freerouting.globalSettings.debugSettings.singleStepExecution = false;
    Freerouting.globalSettings.debugSettings.traceInsertionDelay = 50;
    assertTrue(FRLogger.isGranularTraceWanted(), "trace insertion delay");
  }
}
