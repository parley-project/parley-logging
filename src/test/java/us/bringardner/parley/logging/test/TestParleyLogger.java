package us.bringardner.parley.logging.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ListResourceBundle;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.util.LogHelper;
import us.bringardner.parley.logging.ParleyLogger;
import us.bringardner.parley.logging.ParleyLoggerFinder;

/**
 * ParleyLogger as a System.Logger, and ParleyLoggerFinder: installed from META-INF/services, the
 * levels it makes loggers with, and BaseObject writing through it.
 */
public class TestParleyLogger {

	private static ParleyLogger withStreams(String name, LogLevel level, ByteArrayOutputStream out, ByteArrayOutputStream err) {
		ParleyLogger ret = new ParleyLogger(name, level);
		ret.setOut(new PrintStream(out, true));
		ret.setErr(new PrintStream(err, true));
		return ret;
	}

	@Test
	public void testLevelsAndStreams() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		ParleyLogger logger = withStreams("test.parley.streams", LogLevel.DEBUG, out, err);
		assertEquals("test.parley.streams", logger.getName());

		logger.log(System.Logger.Level.INFO, "to out");
		logger.log(System.Logger.Level.TRACE, "trace is debug");
		logger.log(System.Logger.Level.WARNING, "with a trace", new IOException("warn exception"));
		logger.log(System.Logger.Level.ERROR, "an error");

		String o = out.toString();
		String e = err.toString();
		assertTrue(o.contains("INFO test.parley.streams - to out"), o);
		assertTrue(o.contains("DEBUG test.parley.streams - trace is debug"), o);
		assertTrue(e.contains("WARN test.parley.streams - with a trace"), "Entries with a stack trace go to err");
		assertTrue(e.contains("java.io.IOException: warn exception"));
		assertTrue(e.contains("ERROR test.parley.streams - an error"), "Errors go to err");
		assertFalse(o.contains("an error"));

		out.reset();
		logger.setLevel(LogLevel.WARN);
		assertFalse(logger.isLoggable(System.Logger.Level.INFO));
		assertTrue(logger.isLoggable(System.Logger.Level.WARNING));
		logger.log(System.Logger.Level.INFO, "hidden");
		logger.log(System.Logger.Level.INFO, () -> {
			throw new IllegalStateException("must not be called");
		});
		assertEquals("", out.toString());

		logger.setLevel(null);
		assertEquals(LogLevel.NONE, logger.getLevel(), "A null level turns logging off");
		assertFalse(logger.isLoggable(System.Logger.Level.ERROR));
	}

	@Test
	public void testFormatAndBundle() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		ParleyLogger logger = withStreams("test.parley.format", LogLevel.DEBUG, out, err);
		logger.log(System.Logger.Level.INFO, "{0} of {1}", "one", 2);
		logger.log(System.Logger.Level.INFO, "no params {0}");
		ResourceBundle bundle = new ListResourceBundle() {
			@Override
			protected Object[][] getContents() {
				return new Object[][] { { "greeting", "hello {0}" } };
			}
		};
		logger.log(System.Logger.Level.INFO, bundle, "greeting", "world");
		logger.log(System.Logger.Level.INFO, bundle, "missing", (Throwable) null);
		String o = out.toString();
		assertTrue(o.contains("- one of 2"), o);
		assertTrue(o.contains("- no params {0}"), o);
		assertTrue(o.contains("- hello world"), o);
		assertTrue(o.contains("- missing"), o);
	}

	@Test
	public void testFinderIsInstalled() {
		System.Logger logger = System.getLogger("us.bringardner.parley.test.Installed");
		assertTrue(logger instanceof ParleyLogger, "META-INF/services makes ParleyLoggerFinder the JVM's finder: " + logger.getClass());
		assertSame(logger, System.getLogger("us.bringardner.parley.test.Installed"), "One logger per name");
		assertEquals(LogLevel.DEBUG, ((ParleyLogger) logger).getLevel(), "Parley loggers let everything through");
	}

	@Test
	public void testLevelForNames() {
		String[] keys = { "parley.log.level", "parley.log.level.javax.management", "parley.log.level.pop3" };
		try {
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("us.bringardner.parley.pop3.server.Pop3Server"));
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("us.bringardner.fsh.Shell"));
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor("javax.management.mbeanserver"), "Others default to WARN");
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor(null));

			System.setProperty("parley.log.level", "ERROR");
			System.setProperty("parley.log.level.javax.management", "DEBUG");
			System.setProperty("parley.log.level.pop3", "NONE");
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("javax.management.mbeanserver"), "the nearest name");
			assertEquals(LogLevel.ERROR, ParleyLoggerFinder.levelFor("jdk.internal.httpclient.debug"), "parley.log.level");
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("us.bringardner.parley.pop3.Pop3Client"),
					"BaseObject reads parley.log.level for Parley loggers, not the finder");
		} finally {
			for(String k : keys) {
				System.clearProperty(k);
			}
		}
	}

	@Test
	public void testBaseObjectWritesThroughParleyLogger() {
		LogHelper helper = new LogHelper("us.bringardner.parley.test.ThroughBaseObject");
		ParleyLogger logger = (ParleyLogger) System.getLogger("us.bringardner.parley.test.ThroughBaseObject");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		logger.setOut(new PrintStream(out, true));
		logger.setErr(new PrintStream(err, true));
		try {
			helper.logDebug("debug from BaseObject");
			helper.setLogLevel(LogLevel.INFO);
			helper.logDebug("held back by BaseObject");
			helper.logInfo(() -> "lazy info");
		} finally {
			logger.setOut(null);
			logger.setErr(null);
		}
		String o = out.toString();
		assertTrue(o.contains("DEBUG us.bringardner.parley.test.ThroughBaseObject - debug from BaseObject"), o);
		assertFalse(o.contains("held back"), o);
		assertTrue(o.contains("INFO us.bringardner.parley.test.ThroughBaseObject - lazy info"), o);
	}

	@Test
	public void testSystemStreamsSetting() {
		String key = ParleyLogger.PROPERTY_LOG_FILE;
		try {
			System.setProperty(key, "System.err");
			ParleyLogger toErr = new ParleyLogger("test.to.err");
			assertSame(System.err, toErr.getOut());
			assertSame(System.err, toErr.getErr());

			System.setProperty(key, "System.out");
			ParleyLogger toOut = new ParleyLogger("test.to.out");
			assertSame(System.out, toOut.getOut());
			assertSame(System.out, toOut.getErr());
		} finally {
			System.clearProperty(key);
		}
	}

	@Test
	public void testLogFileIsSharedAndAppended(@TempDir Path dir) throws IOException {
		File file = dir.resolve("shared.log").toFile();
		Files.write(file.toPath(), "existing line\n".getBytes(StandardCharsets.UTF_8));
		String key = ParleyLogger.PROPERTY_LOG_FILE;
		System.setProperty(key, file.getAbsolutePath());
		try {
			ParleyLogger one = new ParleyLogger("file.one");
			ParleyLogger two = new ParleyLogger("file.two");
			assertSame(one.getOut(), two.getOut(), "Loggers writing to the same file share one stream");
			one.log(System.Logger.Level.ERROR, "from one");
			two.log(System.Logger.Level.ERROR, "from two", new IOException("test exception"));
		} finally {
			System.clearProperty(key);
			ParleyLogger.closeLogFiles();
		}
		String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue(content.startsWith("existing line"), "The log file must be appended, not truncated");
		assertTrue(content.contains("file.one - from one"));
		assertTrue(content.contains("file.two - from two"));
		assertTrue(content.contains("java.io.IOException: test exception"));
	}

	@Test
	public void testLogFileInNewDirectory(@TempDir Path dir) throws IOException {
		File file = dir.resolve("a/b/new.log").toFile();
		String key = ParleyLogger.PROPERTY_LOG_FILE;
		System.setProperty(key, file.getPath());
		try {
			ParleyLogger logger = new ParleyLogger("test.new.dir");
			logger.log(System.Logger.Level.WARNING, "made the folders");
		} finally {
			System.clearProperty(key);
			ParleyLogger.closeLogFiles();
		}
		assertTrue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("made the folders"));
	}

	@Test
	public void testLogFileThatCanNotBeOpened(@TempDir Path dir) throws IOException {
		File folder = dir.resolve("a-folder").toFile();
		assertTrue(folder.mkdir());
		String key = ParleyLogger.PROPERTY_LOG_FILE;
		PrintStream err = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		System.setErr(new PrintStream(bytes, true));
		try {
			System.setProperty(key, folder.getPath());
			ParleyLogger logger = new ParleyLogger("test.can.not.open");
			assertSame(System.out, logger.getOut(), "A file that can't be opened leaves the logger on System.out");
		} finally {
			System.clearProperty(key);
			System.setErr(err);
		}
		assertTrue(bytes.toString().contains("Error opening log file"), bytes.toString());
	}

	@Test
	public void testLevelNameIsBaseObjects() {
		assertEquals("pop3.server.Pop3Server", BaseObject.levelName("us.bringardner.parley.pop3.server.Pop3Server"));
	}
}
