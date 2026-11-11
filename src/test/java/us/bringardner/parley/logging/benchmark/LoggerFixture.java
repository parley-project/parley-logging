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
 * </ol>
 * Every logger writes to a file in a new temporary folder with a similar one-line layout. The file is
 * rotated at {@link #MAX_FILE_SIZE} with one old file kept, so a long run can't fill the disk.
 * <p>
 * How entries reach the file:
 * <ol>
 * <li>Not batched: every entry is written to the file as soon as it is logged (each logger's default).</li>
 * <li>Batched: entries are collected and written together. ParleyLogger: its parley.log.flushInterval
 *     setting ({@link #FLUSH_INTERVAL_MS}). java.util.logging has no setting for it (its FileHandler
 *     writes every entry), so a FileHandler that leaves the writing to a timer
 *     ({@link BatchedFileHandler}) is used.</li>
 * </ol>
 * One fixture is made in a JVM (each benchmark runs in a JVM of its own): the settings are system
 * properties, read when the loggers are made.
 */
final class LoggerFixture implements AutoCloseable {

	/** The logger types, the values of the benchmarks' loggerType parameter. */
	static final String PARLEY = "ParleyLogger";
	static final String BASE_OBJECT = "BaseObject";
	static final String BASE_OBJECT_DEFAULT = "BaseObjectNoLevelSet";
	static final String JUL = "JavaUtilLogging";

	/** The name every logger is created with (under us.bringardner., so the finder treats it as Parley's). */
	static final String LOGGER_NAME = LoggerBenchmark.class.getName();

	/** Each log file is rotated when it reaches this size (bytes), keeping one old file. */
	static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

	/** How often batched entries are written. */
	static final long FLUSH_INTERVAL_MS = 500;

	/** The layout of a java.util.logging entry: like ParleyLogger's (ISO 8601 time, level, logger name, message). */
	static final String JUL_FORMAT = "%1$tFT%1$tT.%1$tL%1$tz %4$s %3$s - %5$s%6$s%n";

	final BenchLogger logger;
	private final String type;
	private final Path dir;
	private java.util.logging.Handler julHandler;
	private ScheduledExecutorService julFlusher;

	/**
	 * @param type    one of {@link #PARLEY}, {@link #BASE_OBJECT}, {@link #BASE_OBJECT_DEFAULT} or {@link #JUL}
	 * @param batched true to collect entries and write them together, false to write each one as it is logged
	 */
	LoggerFixture(String type, boolean batched) throws IOException {
		this.type = type;
		dir = Files.createTempDirectory("parley-logger-benchmark");
		try {
			Path file = dir.resolve(type + ".log");
			boolean infoExpected = true;

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
				break;
			case BASE_OBJECT_DEFAULT:
				setParleyProperties(file, batched);
				System.clearProperty(BaseObject.PROPERTY_LOG_LEVEL);
				BaseObject.refreshLogLevels();
				logger = new BaseObjectLogger(new LogHelper(LOGGER_NAME));
				infoExpected = false;
				break;
			case JUL:
				useJulFile(file, batched);
				logger = new JulBenchLogger(java.util.logging.Logger.getLogger(LOGGER_NAME));
				break;
			default:
				throw new IllegalArgumentException("Unknown logger type " + type);
			}

			if( (type.equals(BASE_OBJECT) || type.equals(BASE_OBJECT_DEFAULT))
					&& !(System.getLogger(LOGGER_NAME) instanceof ParleyLogger) ) {
				throw new IllegalStateException("System.Logger isn't parley-logging's ParleyLogger: " + System.getLogger(LOGGER_NAME).getClass());
			}
			if( logger.isDebugEnabled() || logger.isInfoEnabled() != infoExpected || !logger.isWarnEnabled() ) {
				throw new IllegalStateException(type + " isn't at the level the benchmark expects (" + (infoExpected ? "INFO" : "WARN") + ")");
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
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

	/**
	 * Write anything still waiting, close the log file and stop the timer, and remove the temporary folder.
	 */
	@Override
	public void close() throws IOException {
		try {
			if( JUL.equals(type) ) {
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
