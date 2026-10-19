package app.freerouting.logger;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Order;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.AppenderRefComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.config.plugins.Plugin;

/**
 * Custom Log4j2 ConfigurationFactory that programmatically builds the logging configuration based
 * on system properties set early in the application startup.
 *
 * <p>This eliminates the need for runtime configuration manipulation which causes threading issues
 * and exceptions.
 */
@Plugin(name = "FreeroutingConfigurationFactory", category = ConfigurationFactory.CATEGORY)
@Order(50)
public class Log4j2ConfigurationFactory extends ConfigurationFactory {

  private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-6level %msg%n";

  @Override
  protected String[] getSupportedTypes() {
    return new String[] {"*"};
  }

  @Override
  public Configuration getConfiguration(LoggerContext loggerContext, ConfigurationSource source) {
    return getConfiguration(loggerContext, source.toString(), (URI) null);
  }

  @Override
  public Configuration getConfiguration(
      LoggerContext loggerContext, String name, URI configLocation) {
    ConfigurationBuilder<BuiltConfiguration> builder = newConfigurationBuilder();

    // Read configuration from system properties
    boolean consoleEnabled = getBooleanProperty("freerouting.logging.console.enabled", true);

    // Set configuration name and status
    builder.setConfigurationName("FreeroutingConfiguration");
    builder.setStatusLevel(Level.WARN);

    // Create Console appender if enabled
    if (consoleEnabled) {
      AppenderComponentBuilder consoleAppender =
          builder
              .newAppender("Console", "Console")
              .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT)
              .add(builder.newLayout("PatternLayout").addAttribute("pattern", PATTERN));
      builder.add(consoleAppender);
    }

    // Create File appender if enabled
    String filePattern = getProperty("freerouting.logging.file.pattern", PATTERN);
    boolean fileEnabled = getBooleanProperty("freerouting.logging.file.enabled", true);
    String fileLocation = getProperty("freerouting.logging.file.location", null);
    if (fileEnabled && fileLocation != null && !fileLocation.isBlank()) {
      // Ensure parent directory exists
      File logFile = new File(fileLocation);
      File parentDir = logFile.getParentFile();
      if (parentDir != null && !parentDir.exists()) {
        parentDir.mkdirs();
      }

      // Keep investigations simple: write to a single growing, uncompressed log file.
      AppenderComponentBuilder fileAppender =
          builder
              .newAppender("File", "File")
              .addAttribute("fileName", fileLocation)
              .addAttribute("immediateFlush", true)
              .addAttribute("bufferedIO", true)
              .addAttribute("bufferSize", 8192)
              .add(builder.newLayout("PatternLayout").addAttribute("pattern", filePattern));
      builder.add(fileAppender);
    }

    // Create stderr appender for errors
    AppenderComponentBuilder stderrAppender =
        builder
            .newAppender("stderr", "Console")
            .addAttribute("target", ConsoleAppender.Target.SYSTEM_ERR)
            .add(
                builder
                    .newLayout("PatternLayout")
                    .addAttribute("pattern", filePattern)); // Use the same pattern for stderr
    builder.add(stderrAppender);

    // Configure root logger
    // The root logger level must be the most verbose level any appender accepts. With Level.ALL,
    // Logger.isTraceEnabled() is always true, so every FRLogger.isTraceEnabled() guard stays open
    // even when no appender would write the TRACE output.
    // Set -Dfreerouting.logging.trace.enabled=true to keep the TRACE guards open regardless of the
    // appender levels (the previous always-on behaviour, e.g. for parity investigations).
    Level rootLevel =
        getBooleanProperty("freerouting.logging.trace.enabled", false) ? Level.ALL : Level.ERROR;
    List<AppenderRefComponentBuilder> appenderRefs = new ArrayList<>();

    if (consoleEnabled) {
      Level consoleLevel = parseLevel(getProperty("freerouting.logging.console.level", "INFO"));
      appenderRefs.add(builder.newAppenderRef("Console").addAttribute("level", consoleLevel));
      rootLevel = moreVerbose(rootLevel, consoleLevel);
    }

    if (fileEnabled && fileLocation != null && !fileLocation.isBlank()) {
      Level fileLevel = parseLevel(getProperty("freerouting.logging.file.level", "DEBUG"));
      appenderRefs.add(builder.newAppenderRef("File").addAttribute("level", fileLevel));
      rootLevel = moreVerbose(rootLevel, fileLevel);
    }

    // Always add stderr for ERROR level
    appenderRefs.add(builder.newAppenderRef("stderr").addAttribute("level", Level.ERROR));

    RootLoggerComponentBuilder rootLogger = builder.newRootLogger(rootLevel);
    appenderRefs.forEach(rootLogger::add);

    builder.add(rootLogger);

    return builder.build();
  }

  private String getProperty(String key, String defaultValue) {
    String value = System.getProperty(key);
    return value != null && !value.isBlank() ? value : defaultValue;
  }

  private boolean getBooleanProperty(String key, boolean defaultValue) {
    String value = System.getProperty(key);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  private static Level moreVerbose(Level a, Level b) {
    return a.isMoreSpecificThan(b) ? b : a;
  }

  private Level parseLevel(String level) {
    try {
      return Level.valueOf(level.toUpperCase());
    } catch (Exception e) {
      return Level.INFO;
    }
  }
}
