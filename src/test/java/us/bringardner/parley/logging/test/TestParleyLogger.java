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
		assertEquals(LogLevel.WARN, ((ParleyLogger) logger).getLevel(), "With no parley.log.level, a Parley logger is at WARN");
	}

	@Test
	public void testParleyLoggerDefaultsToWarn() {
		String name = "us.bringardner.parley.test.warn.Default";
		String[] keys = { "parley.log.level", "parley.log.level.test.warn" };
		ParleyLogger logger = (ParleyLogger) System.getLogger(name);
		LogHelper helper = new LogHelper(name);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		logger.setOut(new PrintStream(out, true));
		logger.setErr(new PrintStream(out, true));
		try {
			//  Nothing set: no application has to set a level to keep DEBUG and INFO out
			assertFalse(logger.isLoggable(System.Logger.Level.DEBUG));
			assertFalse(logger.isLoggable(System.Logger.Level.INFO));
			assertTrue(logger.isLoggable(System.Logger.Level.WARNING));
			assertTrue(logger.isLoggable(System.Logger.Level.ERROR));
			assertFalse(logger.isLoggable(System.Logger.Level.OFF));
			helper.logDebug("default debug");
			helper.logInfo("default info");
			helper.logWarn("default warn");
			helper.logError("default error");

			//  A setting made after the logger was (a server reading its properties file) counts
			System.setProperty("parley.log.level", "INFO");
			BaseObject.refreshLogLevels();
			assertEquals(LogLevel.DEBUG, logger.getLevel(), "BaseObject decides once a setting covers the name");
			helper.logDebug("info setting debug");
			helper.logInfo("info setting info");

			System.clearProperty("parley.log.level");
			System.setProperty("parley.log.level.test.warn", "DEBUG");
			BaseObject.refreshLogLevels();
			helper.logDebug("module setting debug");

			//  An empty setting is no setting
			System.setProperty("parley.log.level.test.warn", " ");
			BaseObject.refreshLogLevels();
			assertEquals(LogLevel.WARN, logger.getLevel());
			helper.logInfo("empty setting info");

			//  An object's own level is BaseObject's; the logger still decides
			System.clearProperty("parley.log.level.test.warn");
			BaseObject.refreshLogLevels();
			helper.setLogLevel(LogLevel.DEBUG);
			helper.logDebug("object level debug");
			helper.setLogLevel(LogLevel.ERROR);
			helper.logWarn("object level warn");
		} finally {
			for(String k : keys) {
				System.clearProperty(k);
			}
			BaseObject.refreshLogLevels();
			logger.setOut(null);
			logger.setErr(null);
		}
		String o = out.toString();
		assertFalse(o.contains("default debug"), o);
		assertFalse(o.contains("default info"), o);
		assertTrue(o.contains("WARN " + name + " - default warn"), o);
		assertTrue(o.contains("ERROR " + name + " - default error"), o);
		assertFalse(o.contains("info setting debug"), o);
		assertTrue(o.contains("INFO " + name + " - info setting info"), o);
		assertTrue(o.contains("DEBUG " + name + " - module setting debug"), o);
		assertFalse(o.contains("empty setting info"), o);
		assertFalse(o.contains("object level debug"), o);
		assertFalse(o.contains("object level warn"), o);
	}

	@Test
	public void testSetLevelOnAParleyLoggerWins() {
		String name = "us.bringardner.parley.test.warn.SetLevel";
		ParleyLogger logger = (ParleyLogger) System.getLogger(name);
		try {
			logger.setLevel(LogLevel.INFO);
			assertEquals(LogLevel.INFO, logger.getLevel());
			assertTrue(logger.isLoggable(System.Logger.Level.INFO));
			assertFalse(logger.isLoggable(System.Logger.Level.DEBUG));

			System.setProperty("parley.log.level", "DEBUG");
			assertFalse(logger.isLoggable(System.Logger.Level.DEBUG), "setLevel wins over the settings");

			logger.setLevel(LogLevel.NONE);
			assertFalse(logger.isLoggable(System.Logger.Level.ERROR));
		} finally {
			System.clearProperty("parley.log.level");
		}
	}

	@Test
	public void testLevelForNames() {
		String[] keys = { "parley.log.level", "parley.log.level.javax.management", "parley.log.level.pop3" };
		try {
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor("us.bringardner.parley.pop3.server.Pop3Server"), "Parley's default to WARN too");
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor("us.bringardner.fsh.Shell"));
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor("javax.management.mbeanserver"), "Others default to WARN");
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor(null));

			System.setProperty("parley.log.level", "ERROR");
			System.setProperty("parley.log.level.javax.management", "DEBUG");
			System.setProperty("parley.log.level.pop3", "NONE");
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("javax.management.mbeanserver"), "the nearest name");
			assertEquals(LogLevel.ERROR, ParleyLoggerFinder.levelFor("jdk.internal.httpclient.debug"), "parley.log.level");
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("us.bringardner.parley.pop3.Pop3Client"),
					"BaseObject reads parley.log.level for Parley loggers, not the finder");
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("us.bringardner.fsh.Shell"), "parley.log.level covers every Parley logger");

			System.clearProperty("parley.log.level");
			assertEquals(LogLevel.DEBUG, ParleyLoggerFinder.levelFor("us.bringardner.parley.pop3.Pop3Client"), "parley.log.level.pop3 covers it");
			assertEquals(LogLevel.WARN, ParleyLoggerFinder.levelFor("us.bringardner.parley.smtp.SmtpClient"), "nothing covers it");
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
			//  (with no setting the logger would hold DEBUG and INFO back)
			System.setProperty("parley.log.level.test.ThroughBaseObject", "DEBUG");
			BaseObject.refreshLogLevels();
			helper.logDebug("debug from BaseObject");
			helper.setLogLevel(LogLevel.INFO);
			helper.logDebug("held back by BaseObject");
			helper.logInfo(() -> "lazy info");
		} finally {
			System.clearProperty("parley.log.level.test.ThroughBaseObject");
			BaseObject.refreshLogLevels();
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
