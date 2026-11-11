/**
 * Copyright 1998-2026 Tony Bringardner
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *	@author Tony Bringardner
 */
package us.bringardner.parley.logging.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.util.LogHelper;
import us.bringardner.parley.logging.ParleyLogger;

/**
 * Creates one of the loggers the benchmarks compare, set up the same way as the others, and removes
 * everything it made when it is closed.
 * <p>
 * The logger types:
 * <ol>
 * <li>{@link #PARLEY}: a ParleyLogger called directly, as a System.Logger, at level INFO.</li>
 * <li>{@link #BASE_OBJECT}: a Parley object logging through BaseObject (logInfo, logDebug ...), which
 *     calls the JDK's System.Logger, which parley-logging's ParleyLoggerFinder gives a ParleyLogger:
 *     what Parley code does in an application that includes parley-logging. parley.log.level is INFO.</li>
 * <li>{@link #BASE_OBJECT_DEFAULT}: the same with no parley.log.level set, so BaseObject passes
 *     everything on and the logger holds DEBUG and INFO back (it looks at the parley.log.level
 *     settings for each such message). Only the level check and the debug calls are measured.</li>
 * <li>{@link #JUL}: java.util.logging called directly, at level INFO: what an application with no
 *     System.Logger provider writes with.</li>
 * <li>{@link #LOG4J}: Log4j 2 called directly (its own API), at level INFO.</li>
 * <li>{@link #LOGBACK}: Logback called directly (through SLF4J, its API), at level INFO.</li>
 * <li>{@link #BASE_OBJECT_LOG4J}, {@link #BASE_OBJECT_LOGBACK} and {@link #BASE_OBJECT_JUL}: a Parley
 *     object logging through BaseObject and System.Logger, as in {@link #BASE_OBJECT}, with Log4j 2
 *     (its log4j-jpl bridge), Logback (SLF4J's slf4j-jdk-platform-logging bridge) or
 *     java.util.logging (the JDK's own, when a JVM has no provider) behind System.Logger: what Parley
 *     code costs in an application that uses that logging system. Each is set up as it is when called
 *     directly.</li>
 * </ol>
 * A JVM has one System.Logger provider, the first the JDK finds. The benchmark profile keeps every
 * provider's service file off the class path (parley-logging's own too) and gives each benchmark
 * class's JVMs the one they need with -Xbootclasspath/a ({@link #FINDER_PARLEY},
 * {@link #FINDER_LOG4J}, {@link #FINDER_SLF4J}); the java.util.logging JVMs get none. The fixture
 * checks that the JVM it runs in has the provider its logger type needs.
 * Every logger writes to a file in a new temporary folder with a similar one-line layout. The file is
 * rotated at {@link #MAX_FILE_SIZE} with one old file kept, so a long run can't fill the disk.
 * <p>
 * How entries reach the file:
 * <ol>
 * <li>Not batched: every entry is written to the file as soon as it is logged (each logger's default).</li>
 * <li>Batched: entries are collected and written together. ParleyLogger: its parley.log.flushInterval
 *     setting ({@link #FLUSH_INTERVAL_MS}). java.util.logging has no setting for it (its FileHandler
 *     writes every entry), so a FileHandler that leaves the writing to a timer
 *     ({@link BatchedFileHandler}) is used. Log4j and Logback: immediateFlush off; each writes when
 *     its 8 KB buffer is full (neither has a timer).</li>
 * </ol>
 * One fixture is made in a JVM (each benchmark runs in a JVM of its own): the settings are system
 * properties, read when the loggers are made.
 */
final class LoggerFixture implements AutoCloseable {

	/** The logger types, the values of the benchmarks' loggerType parameter. */
	static final String PARLEY = "ParleyLogger";
	static final String BASE_OBJECT = "BaseObjectToParleyLogger";
	static final String BASE_OBJECT_LOG4J = "BaseObjectToLog4j";
	static final String BASE_OBJECT_LOGBACK = "BaseObjectToLogback";
	static final String BASE_OBJECT_JUL = "BaseObjectToJavaUtilLogging";
	static final String BASE_OBJECT_DEFAULT = "BaseObjectNoLevelSet";
	static final String JUL = "JavaUtilLogging";
	static final String LOG4J = "Log4j";
	static final String LOGBACK = "Logback";

	/**
	 * The JVM argument that makes a System.LoggerFinder the JVM's: its service file, which the benchmark
	 * profile copies to a folder of its own under target/benchmark/finders, is put on the boot class
	 * path, where the JDK finds it first. The path is relative, so the benchmark runs from the project folder.
	 */
	static final String FINDER_PARLEY = "-Xbootclasspath/a:target/benchmark/finders/parley";
	static final String FINDER_LOG4J = "-Xbootclasspath/a:target/benchmark/finders/log4j";
	static final String FINDER_SLF4J = "-Xbootclasspath/a:target/benchmark/finders/slf4j";

	/** The System.LoggerFinder classes: parley-logging's, log4j-jpl's, slf4j-jdk-platform-logging's and the JDK's for java.util.logging. */
	private static final String FINDER_CLASS_PARLEY = "us.bringardner.parley.logging.ParleyLoggerFinder";
	private static final String FINDER_CLASS_LOG4J = "org.apache.logging.log4j.jpl.Log4jSystemLoggerFinder";
	private static final String FINDER_CLASS_SLF4J = "org.slf4j.jdk.platform.logging.SLF4JSystemLoggerFinder";
	private static final String FINDER_CLASS_JUL = "sun.util.logging.internal.LoggingProviderImpl";

	/** The name every logger is created with (under us.bringardner., so the finder treats it as Parley's). */
	static final String LOGGER_NAME = LoggerBenchmark.class.getName();

	/** Each log file is rotated when it reaches this size (bytes), keeping one old file. */
	static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

	/** How often batched entries are written. */
	static final long FLUSH_INTERVAL_MS = 500;

	/** The layout of a java.util.logging entry: like ParleyLogger's (ISO 8601 time, level, logger name, message). */
	static final String JUL_FORMAT = "%1$tFT%1$tT.%1$tL%1$tz %4$s %3$s - %5$s%6$s%n";

	/** The same layout for Log4j and for Logback (their pattern languages differ a little). */
	static final String LOG4J_PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} [%t] %p %c - %m%n";
	static final String LOGBACK_PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} [%thread] %level %logger - %msg%n";

	final BenchLogger logger;
	private final String type;
	private final Path dir;
	private java.util.logging.Handler julHandler;
	private ScheduledExecutorService julFlusher;
	private ch.qos.logback.classic.LoggerContext logbackContext;

	/**
	 * @param type    one of the logger types above
	 * @param batched true to collect entries and write them together, false to write each one as it is logged
	 */
	LoggerFixture(String type, boolean batched) throws IOException {
		this.type = type;
		dir = Files.createTempDirectory("parley-logger-benchmark");
		try {
			Path file = dir.resolve(type + ".log");
			boolean infoExpected = true;
			//  The System.LoggerFinder this JVM must have, for the types that log through System.Logger
			String finder = null;

			switch (type) {
			case PARLEY:
				setParleyProperties(file, batched);
				logger = new DirectLogger(new ParleyLogger(LOGGER_NAME, LogLevel.INFO));
				break;
			case BASE_OBJECT:
				setParleyProperties(file, batched);
				System.setProperty(BaseObject.PROPERTY_LOG_LEVEL, "INFO");
				BaseObject.refreshLogLevels();
				logger = new BaseObjectLogger(new LogHelper(LOGGER_NAME));
				finder = FINDER_CLASS_PARLEY;
				break;
			case BASE_OBJECT_LOG4J:
				useLog4jFile(file, batched);
				logger = baseObjectAtInfo();
				finder = FINDER_CLASS_LOG4J;
				break;
			case BASE_OBJECT_LOGBACK:
				useLogbackFile(file, batched);
				logger = baseObjectAtInfo();
				finder = FINDER_CLASS_SLF4J;
				break;
			case BASE_OBJECT_JUL:
				useJulFile(file, batched);
				logger = baseObjectAtInfo();
				finder = FINDER_CLASS_JUL;
				break;
			case BASE_OBJECT_DEFAULT:
				setParleyProperties(file, batched);
				System.clearProperty(BaseObject.PROPERTY_LOG_LEVEL);
				BaseObject.refreshLogLevels();
				logger = new BaseObjectLogger(new LogHelper(LOGGER_NAME));
				infoExpected = false;
				finder = FINDER_CLASS_PARLEY;
				break;
			case JUL:
				useJulFile(file, batched);
				logger = new JulBenchLogger(java.util.logging.Logger.getLogger(LOGGER_NAME));
				break;
			case LOG4J:
				useLog4jFile(file, batched);
				logger = new Log4jBenchLogger(org.apache.logging.log4j.LogManager.getLogger(LOGGER_NAME));
				break;
			case LOGBACK:
				logger = new LogbackBenchLogger(useLogbackFile(file, batched));
				break;
			default:
				throw new IllegalArgumentException("Unknown logger type " + type);
			}

			if( finder != null ) {
				String found = System.LoggerFinder.getLoggerFinder().getClass().getName();
				if( !finder.equals(found) ) {
					throw new IllegalStateException(type + " needs " + finder + " behind System.Logger, and this JVM has " + found
							+ ". Run with the benchmark profile (mvn -Pbenchmark test), from the project folder.");
				}
			}
			if( logger.isDebugEnabled() || logger.isInfoEnabled() != infoExpected || !logger.isWarnEnabled() ) {
				throw new IllegalStateException(type + " isn't at the level the benchmark expects (" + (infoExpected ? "INFO" : "WARN") + ")");
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
	}

	/** A Parley object with parley.log.level=INFO: BaseObject holds DEBUG back, and what it passes goes to System.Logger. */
	private static BenchLogger baseObjectAtInfo() {
		System.setProperty(BaseObject.PROPERTY_LOG_LEVEL, "INFO");
		BaseObject.refreshLogLevels();
		return new BaseObjectLogger(new LogHelper(LOGGER_NAME));
	}

	private static void setParleyProperties(Path file, boolean batched) {
		System.setProperty(ParleyLogger.PROPERTY_LOG_FILE, file.toString());
		System.setProperty(ParleyLogger.PROPERTY_LOG_FILE_MAX_SIZE, Long.toString(MAX_FILE_SIZE));
		System.setProperty(ParleyLogger.PROPERTY_LOG_FILE_COUNT, "1");
		if( batched ) {
			System.setProperty(ParleyLogger.PROPERTY_LOG_FLUSH_INTERVAL, FLUSH_INTERVAL_MS + "ms");
		}
	}

	/** Send every java.util.logging message to the file (and nowhere else). */
	private void useJulFile(Path file, boolean batched) throws IOException {
		System.setProperty("java.util.logging.SimpleFormatter.format", JUL_FORMAT);
		java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
		for (java.util.logging.Handler h : root.getHandlers()) {
			root.removeHandler(h);
			h.close();
		}
		//  count 2 is the current file and one old one
		if( batched ) {
			BatchedFileHandler handler = new BatchedFileHandler(file.toString(), (int) MAX_FILE_SIZE, 2);
			julHandler = handler;
			julFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "benchmark-jul-flush");
				t.setDaemon(true);
				return t;
			});
			julFlusher.scheduleWithFixedDelay(handler::write, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
		} else {
			julHandler = new java.util.logging.FileHandler(file.toString(), (int) MAX_FILE_SIZE, 2, true);
		}
		julHandler.setFormatter(new java.util.logging.SimpleFormatter());
		julHandler.setLevel(java.util.logging.Level.ALL);
		root.addHandler(julHandler);
		root.setLevel(java.util.logging.Level.INFO);
	}

	/** Send every Log4j message to the file (and nowhere else). */
	private void useLog4jFile(Path file, boolean batched) throws IOException {
		Path config = writeLog4jConfig(file, batched);
		System.setProperty("log4j2.configurationFile", config.toString());
		//  Behind System.Logger, Log4j may have started already (the JDK logs while the JVM starts): give it the file now
		org.apache.logging.log4j.core.config.Configurator.reconfigure(config.toUri());
	}

	private Path writeLog4jConfig(Path file, boolean batched) throws IOException {
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<Configuration status=\"WARN\">\n"
				+ "  <Appenders>\n"
				+ "    <RollingFile name=\"file\" fileName=\"" + file + "\" filePattern=\"" + file + ".%i\""
				+ " immediateFlush=\"" + !batched + "\" bufferedIO=\"true\">\n"
				+ "      <PatternLayout pattern=\"" + LOG4J_PATTERN + "\"/>\n"
				+ "      <SizeBasedTriggeringPolicy size=\"" + MAX_FILE_SIZE + "\"/>\n"
				+ "      <DefaultRolloverStrategy max=\"1\"/>\n"
				+ "    </RollingFile>\n"
				+ "  </Appenders>\n"
				+ "  <Loggers>\n"
				+ "    <Root level=\"INFO\"><AppenderRef ref=\"file\"/></Root>\n"
				+ "  </Loggers>\n"
				+ "</Configuration>\n";
		Path config = dir.resolve("log4j2-benchmark.xml");
		Files.writeString(config, xml);
		return config;
	}

	/** Send every Logback message to the file (and nowhere else), set up in code. */
	private org.slf4j.Logger useLogbackFile(Path file, boolean batched) {
		ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
		logbackContext = context;
		//  (without a configuration file Logback starts with a console appender at DEBUG)
		context.reset();

		ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
		encoder.setContext(context);
		encoder.setPattern(LOGBACK_PATTERN);
		encoder.start();

		ch.qos.logback.core.rolling.RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.rolling.RollingFileAppender<>();
		appender.setContext(context);
		appender.setName("file");
		appender.setFile(file.toString());
		appender.setAppend(true);
		appender.setImmediateFlush(!batched);
		appender.setEncoder(encoder);

		ch.qos.logback.core.rolling.FixedWindowRollingPolicy rolling = new ch.qos.logback.core.rolling.FixedWindowRollingPolicy();
		rolling.setContext(context);
		rolling.setParent(appender);
		rolling.setFileNamePattern(file + ".%i");
		rolling.setMinIndex(1);
		rolling.setMaxIndex(1);
		rolling.start();

		ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy<ch.qos.logback.classic.spi.ILoggingEvent> trigger = new ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy<>();
		trigger.setContext(context);
		trigger.setMaxFileSize(new ch.qos.logback.core.util.FileSize(MAX_FILE_SIZE));
		trigger.start();

		appender.setRollingPolicy(rolling);
		appender.setTriggeringPolicy(trigger);
		appender.start();

		ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		root.setLevel(ch.qos.logback.classic.Level.INFO);
		root.addAppender(appender);
		return context.getLogger(LOGGER_NAME);
	}

	/**
	 * Write anything still waiting, close the log file and stop the timer, and remove the temporary folder.
	 */
	@Override
	public void close() throws IOException {
		try {
			if( LOG4J.equals(type) || BASE_OBJECT_LOG4J.equals(type) ) {
				org.apache.logging.log4j.LogManager.shutdown();
			} else if( LOGBACK.equals(type) || BASE_OBJECT_LOGBACK.equals(type) ) {
				if( logbackContext != null ) {
					logbackContext.stop();
				}
			} else if( JUL.equals(type) || BASE_OBJECT_JUL.equals(type) ) {
				if( julFlusher != null ) {
					julFlusher.shutdownNow();
					try {
						julFlusher.awaitTermination(5, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				if( julHandler != null ) {
					java.util.logging.Logger.getLogger("").removeHandler(julHandler);
					julHandler.close();
				}
			} else {
				ParleyLogger.closeLogFiles();
			}
		} finally {
			deleteDir();
		}
	}

	private void deleteDir() throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	/** A ParleyLogger called as the System.Logger it is. */
	private static final class DirectLogger implements BenchLogger {
		private final System.Logger logger;

		DirectLogger(System.Logger logger) {
			this.logger = logger;
		}

		@Override
		public boolean isDebugEnabled() {
			return logger.isLoggable(System.Logger.Level.DEBUG);
		}

		@Override
		public boolean isInfoEnabled() {
			return logger.isLoggable(System.Logger.Level.INFO);
		}

		@Override
		public boolean isWarnEnabled() {
			return logger.isLoggable(System.Logger.Level.WARNING);
		}

		@Override
		public void debug(String msg) {
			logger.log(System.Logger.Level.DEBUG, msg);
		}

		@Override
		public void debug(Supplier<String> msg) {
			logger.log(System.Logger.Level.DEBUG, msg);
		}

		@Override
		public void info(String msg) {
			logger.log(System.Logger.Level.INFO, msg);
		}

		@Override
		public void warn(String msg, Throwable error) {
			logger.log(System.Logger.Level.WARNING, msg, error);
		}
	}

	/** A Parley object's own logging calls (BaseObject, then System.Logger, then ParleyLogger). */
	private static final class BaseObjectLogger implements BenchLogger {
		private final BaseObject object;

		BaseObjectLogger(BaseObject object) {
			this.object = object;
		}

		@Override
		public boolean isDebugEnabled() {
			return object.isDebugEnabled();
		}

		@Override
		public boolean isInfoEnabled() {
			return object.isInfoEnabled();
		}

		@Override
		public boolean isWarnEnabled() {
			return object.isWarnEnabled();
		}

		@Override
		public void debug(String msg) {
			object.logDebug(msg);
		}

		@Override
		public void debug(Supplier<String> msg) {
			object.logDebug(msg);
		}

		@Override
		public void info(String msg) {
			object.logInfo(msg);
		}

		@Override
		public void warn(String msg, Throwable error) {
			object.logWarn(msg, error);
		}
	}

	/** java.util.logging called directly. */
	private static final class JulBenchLogger implements BenchLogger {
		private final java.util.logging.Logger logger;

		JulBenchLogger(java.util.logging.Logger logger) {
			this.logger = logger;
		}

		@Override
		public boolean isDebugEnabled() {
			return logger.isLoggable(java.util.logging.Level.FINE);
		}

		@Override
		public boolean isInfoEnabled() {
			return logger.isLoggable(java.util.logging.Level.INFO);
		}

		@Override
		public boolean isWarnEnabled() {
			return logger.isLoggable(java.util.logging.Level.WARNING);
		}

		@Override
		public void debug(String msg) {
			logger.fine(msg);
		}

		@Override
		public void debug(Supplier<String> msg) {
			logger.fine(msg);
		}

		@Override
		public void info(String msg) {
			logger.info(msg);
		}

		@Override
		public void warn(String msg, Throwable error) {
			logger.log(java.util.logging.Level.WARNING, msg, error);
		}
	}

	/** Log4j 2 called directly. */
	private static final class Log4jBenchLogger implements BenchLogger {
		private final org.apache.logging.log4j.Logger logger;

		Log4jBenchLogger(org.apache.logging.log4j.Logger logger) {
			this.logger = logger;
		}

		@Override
		public boolean isDebugEnabled() {
			return logger.isDebugEnabled();
		}

		@Override
		public boolean isInfoEnabled() {
			return logger.isInfoEnabled();
		}

		@Override
		public boolean isWarnEnabled() {
			return logger.isWarnEnabled();
		}

		@Override
		public void debug(String msg) {
			logger.debug(msg);
		}

		@Override
		public void debug(Supplier<String> msg) {
			//  (Log4j has a Supplier of its own)
			org.apache.logging.log4j.util.Supplier<String> s = msg::get;
			logger.debug(s);
		}

		@Override
		public void info(String msg) {
			logger.info(msg);
		}

		@Override
		public void warn(String msg, Throwable error) {
			logger.warn(msg, error);
		}
	}

	/** Logback called directly, through SLF4J. */
	private static final class LogbackBenchLogger implements BenchLogger {
		private final org.slf4j.Logger logger;

		LogbackBenchLogger(org.slf4j.Logger logger) {
			this.logger = logger;
		}

		@Override
		public boolean isDebugEnabled() {
			return logger.isDebugEnabled();
		}

		@Override
		public boolean isInfoEnabled() {
			return logger.isInfoEnabled();
		}

		@Override
		public boolean isWarnEnabled() {
			return logger.isWarnEnabled();
		}

		@Override
		public void debug(String msg) {
			logger.debug(msg);
		}

		@Override
		public void debug(Supplier<String> msg) {
			//  (SLF4J takes a Supplier through its fluent calls)
			logger.atDebug().log(msg);
		}

		@Override
		public void info(String msg) {
			logger.info(msg);
		}

		@Override
		public void warn(String msg, Throwable error) {
			logger.warn(msg, error);
		}
	}

	/**
	 * A java.util.logging FileHandler that collects entries and leaves writing them to the file to
	 * {@link #write()}, called by a timer. FileHandler calls flush() after every entry; here that does
	 * nothing. close() still writes everything (it doesn't go through flush()), and the file is still
	 * rotated by size, a little later than with every entry written at once.
	 */
	static final class BatchedFileHandler extends java.util.logging.FileHandler {

		BatchedFileHandler(String pattern, int limit, int count) throws IOException {
			super(pattern, limit, count, true);
		}

		/** Called by FileHandler after every entry: the entries are written by {@link #write()} instead. */
		@Override
		public void flush() {
		}

		/** Write the entries collected since the last call to the file. */
		void write() {
			super.flush();
		}
	}
}
