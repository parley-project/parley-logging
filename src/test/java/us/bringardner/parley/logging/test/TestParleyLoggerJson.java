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
package us.bringardner.parley.logging.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.core.LogContext;
import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.util.LogHelper;
import us.bringardner.parley.logging.ParleyLogger;

/**
 * ParleyLogger's JSON form (parley.log.format=json): one JSON object on a line for each entry, with
 * the Elastic Common Schema's field names.
 */
public class TestParleyLoggerJson {

	private static final String FORMAT = ParleyLogger.PROPERTY_LOG_FORMAT;

	/** A logger made with parley.log.format set to this value (null for not set), writing to out. */
	private static ParleyLogger logger(String name, String format, ByteArrayOutputStream out) {
		ParleyLogger ret;
		try {
			if( format != null ) {
				System.setProperty(FORMAT, format);
			}
			ret = new ParleyLogger(name, LogLevel.DEBUG);
		} finally {
			System.clearProperty(FORMAT);
		}
		PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
		ret.setOut(ps);
		ret.setErr(ps);
		return ret;
	}

	private static String[] lines(ByteArrayOutputStream out) {
		String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
		return text.isEmpty() ? new String[0] : text.split("\\R");
	}

	/**
	 * Reads one entry: a JSON object whose values are all strings, the only kind ParleyLogger writes.
	 * It is strict (any other JSON, or anything after the object, fails), so it also checks that the
	 * line is valid JSON.
	 */
	private static Map<String, String> parse(String line) {
		Map<String, String> ret = new LinkedHashMap<>();
		int[] pos = { 0 };
		expect(line, pos, '{');
		while( true ) {
			String name = string(line, pos);
			expect(line, pos, ':');
			assertNull(ret.put(name, string(line, pos)), "Field written twice: " + name);
			if( line.charAt(pos[0]) == '}' ) {
				pos[0]++;
				break;
			}
			expect(line, pos, ',');
		}
		assertEquals(line.length(), pos[0], "Nothing after the object: " + line);
		return ret;
	}

	private static void expect(String line, int[] pos, char c) {
		assertEquals(c, line.charAt(pos[0]), "At " + pos[0] + " of " + line);
		pos[0]++;
	}

	private static String string(String line, int[] pos) {
		expect(line, pos, '"');
		StringBuilder ret = new StringBuilder();
		while( true ) {
			char c = line.charAt(pos[0]++);
			if( c == '"' ) {
				return ret.toString();
			}
			assertTrue(c >= 0x20, "A control character must be escaped, at " + (pos[0] - 1) + " of " + line);
			if( c != '\\' ) {
				ret.append(c);
				continue;
			}
			char e = line.charAt(pos[0]++);
			switch (e) {
			case '"':
			case '\\':
			case '/':
				ret.append(e);
				break;
			case 'n':
				ret.append('\n');
				break;
			case 'r':
				ret.append('\r');
				break;
			case 't':
				ret.append('\t');
				break;
			case 'b':
				ret.append('\b');
				break;
			case 'f':
				ret.append('\f');
				break;
			case 'u':
				ret.append((char) Integer.parseInt(line.substring(pos[0], pos[0] + 4), 16));
				pos[0] += 4;
				break;
			default:
				throw new AssertionError("Invalid escape \\" + e + " in " + line);
			}
		}
	}

	@Test
	public void testFieldsAndTheirOrder() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ParleyLogger logger = logger("test.json.Fields", "json", out);
		logger.log(System.Logger.Level.INFO, "Listening on port 110");
		logger.log(System.Logger.Level.TRACE, "trace is debug");
		logger.log(System.Logger.Level.WARNING, "queue at {0} of {1}", 9, 10);

		String[] lines = lines(out);
		assertEquals(3, lines.length, "One line an entry");
		Map<String, String> entry = parse(lines[0]);
		assertEquals(List.of("@timestamp", "log.level", "message", "ecs.version", "log.logger", "process.thread.name"),
				new ArrayList<>(entry.keySet()), "The fields, with the three a person reads first");
		assertTrue(entry.get("@timestamp").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2}"), entry.get("@timestamp"));
		assertEquals("INFO", entry.get("log.level"));
		assertEquals("Listening on port 110", entry.get("message"));
		assertEquals(ParleyLogger.ECS_VERSION, entry.get("ecs.version"));
		assertEquals("test.json.Fields", entry.get("log.logger"));
		assertEquals(Thread.currentThread().getName(), entry.get("process.thread.name"));

		assertEquals("DEBUG", parse(lines[1]).get("log.level"));
		assertEquals("WARN", parse(lines[2]).get("log.level"));
		assertEquals("queue at 9 of 10", parse(lines[2]).get("message"), "Parameters are formatted as in the text form");
	}

	@Test
	public void testTextThatNeedsEscaping() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ParleyLogger logger = logger("test.json.\"Escaping\"", "JSON", out);
		String[] messages = {
				"a \"quoted\" word and a back\\slash",
				"two\nlines, a\ttab and a\r\nWindows line end",
				"control characters \u0000 \u0001 \u001f \u007f and line separators    ",
				"not ASCII: café 日本語 😀",
				"{\"looks\":\"like JSON\"}",
				"",
		};
		for(String m : messages) {
			logger.log(System.Logger.Level.INFO, m);
		}
		logger.log(System.Logger.Level.INFO, (String) null);

		String[] lines = lines(out);
		assertEquals(messages.length + 1, lines.length, "A line end in a message doesn't start a new line");
		for(int i = 0; i < messages.length; i++) {
			Map<String, String> entry = parse(lines[i]);
			assertEquals(messages[i], entry.get("message"), "The message comes back as it was logged");
			assertEquals("test.json.\"Escaping\"", entry.get("log.logger"));
		}
		assertEquals("null", parse(lines[messages.length]).get("message"), "No message is written as the text form writes it");
	}

	@Test
	public void testExceptionIsInsideTheEntry() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ParleyLogger logger = logger("test.json.Error", "json", out);
		logger.log(System.Logger.Level.ERROR, "Can't read the mailbox", new IOException("disk \"sda\" failed", new IllegalStateException("cause")));
		logger.log(System.Logger.Level.WARNING, "No message in the exception", new IllegalStateException());

		String[] lines = lines(out);
		assertEquals(2, lines.length, "A stack trace doesn't spread over lines");
		Map<String, String> entry = parse(lines[0]);
		assertEquals("ERROR", entry.get("log.level"));
		assertEquals("Can't read the mailbox", entry.get("message"));
		assertEquals("java.io.IOException", entry.get("error.type"));
		assertEquals("disk \"sda\" failed", entry.get("error.message"));
		String trace = entry.get("error.stack_trace");
		assertTrue(trace.startsWith("java.io.IOException: disk \"sda\" failed"), trace);
		assertTrue(trace.contains("\tat " + TestParleyLoggerJson.class.getName() + ".testExceptionIsInsideTheEntry"), trace);
		assertTrue(trace.contains("Caused by: java.lang.IllegalStateException: cause"), trace);
		assertEquals("error.stack_trace", new ArrayList<>(entry.keySet()).get(entry.size() - 1), "The error fields are last");

		entry = parse(lines[1]);
		assertEquals("java.lang.IllegalStateException", entry.get("error.type"));
		assertFalse(entry.containsKey("error.message"), "No error.message when the exception has none");
		assertNotNull(entry.get("error.stack_trace"));
	}

	@Test
	public void testLogContextIsAFieldOfItsOwn() {
		String name = "us.bringardner.parley.test.json.Context";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
		ParleyLogger logger;
		try {
			//  The finder makes this logger when it is first asked for: the format is read then
			System.setProperty(FORMAT, "json");
			logger = (ParleyLogger) System.getLogger(name);
		} finally {
			System.clearProperty(FORMAT);
		}
		LogHelper helper = new LogHelper(name);
		logger.setOut(ps);
		logger.setErr(ps);
		try {
			helper.logWarn("before the session");
			try (LogContext.Scope scope = LogContext.enter("Pop3Server-4127")) {
				//  BaseObject writes "[Pop3Server-4127] Connected ..."
				helper.logWarn("Connected from 192.0.2.7");
				helper.logError("Failed", new IOException("reset"));
				//  Not through BaseObject: no ID in the text, and still the thread's field
				logger.log(System.Logger.Level.WARNING, "direct");
				logger.log(System.Logger.Level.WARNING, "[Pop3Server-4127]no space, so not BaseObject's");
				logger.log(System.Logger.Level.WARNING, "[Pop3Server-41270] another ID");
			}
			helper.logWarn("after the session");
		} finally {
			logger.setOut(null);
			logger.setErr(null);
		}

		String[] lines = lines(out);
		assertEquals(7, lines.length);
		assertFalse(parse(lines[0]).containsKey("labels.context"), "No field without an ID");
		assertEquals("before the session", parse(lines[0]).get("message"));

		Map<String, String> entry = parse(lines[1]);
		assertEquals("Pop3Server-4127", entry.get("labels.context"));
		assertEquals("Connected from 192.0.2.7", entry.get("message"), "The ID is taken out of the message text");
		assertEquals(List.of("@timestamp", "log.level", "message", "ecs.version", "log.logger", "process.thread.name", "labels.context"),
				new ArrayList<>(entry.keySet()));

		entry = parse(lines[2]);
		assertEquals("Pop3Server-4127", entry.get("labels.context"));
		assertEquals("Failed", entry.get("message"));
		assertEquals("java.io.IOException", entry.get("error.type"));

		assertEquals("Pop3Server-4127", parse(lines[3]).get("labels.context"));
		assertEquals("direct", parse(lines[3]).get("message"));
		assertEquals("[Pop3Server-4127]no space, so not BaseObject's", parse(lines[4]).get("message"));
		assertEquals("[Pop3Server-41270] another ID", parse(lines[5]).get("message"));

		assertFalse(parse(lines[6]).containsKey("labels.context"), "The ID ends with its scope");
	}

	@Test
	public void testTextIsTheDefault() {
		String err = captureSystemErr(() -> {
			for(String format : new String[] { null, "text", " TEXT ", "" }) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				logger("test.json.Text", format, out).log(System.Logger.Level.INFO, "plain");
				String[] lines = lines(out);
				assertEquals(1, lines.length);
				assertTrue(lines[0].endsWith(" INFO test.json.Text - plain"), "parley.log.format=" + format + ": " + lines[0]);
			}
		});
		assertEquals("", err, "Nothing to report for a valid format");
	}

	@Test
	public void testInvalidFormatIsReportedAndText() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		String err = captureSystemErr(() -> logger("test.json.Invalid", "xml", out).log(System.Logger.Level.INFO, "plain"));
		assertTrue(err.contains("Invalid parley.log.format (xml)"), err);
		String[] lines = lines(out);
		assertEquals(1, lines.length);
		assertTrue(lines[0].endsWith(" INFO test.json.Invalid - plain"), lines[0]);
	}

	/** Runs the task with System.err collected, and puts System.err back. */
	private static String captureSystemErr(Runnable task) {
		PrintStream before = System.err;
		ByteArrayOutputStream collected = new ByteArrayOutputStream();
		try (PrintStream ps = new PrintStream(collected, true, StandardCharsets.UTF_8)) {
			System.setErr(ps);
			task.run();
		} finally {
			System.setErr(before);
		}
		return new String(collected.toByteArray(), StandardCharsets.UTF_8);
	}

	@Test
	public void testJsonLogFile(@TempDir Path dir) throws IOException {
		File file = dir.resolve("json.log").toFile();
		System.setProperty(ParleyLogger.PROPERTY_LOG_FILE, file.getAbsolutePath());
		System.setProperty(FORMAT, "json");
		try {
			ParleyLogger logger = new ParleyLogger("test.json.File", LogLevel.INFO);
			logger.log(System.Logger.Level.INFO, "first");
			logger.log(System.Logger.Level.DEBUG, "held back");
			logger.log(System.Logger.Level.ERROR, "second", new IOException("to the same file"));
		} finally {
			System.clearProperty(ParleyLogger.PROPERTY_LOG_FILE);
			System.clearProperty(FORMAT);
			ParleyLogger.closeLogFiles();
		}
		List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		assertEquals(2, lines.size(), "Every line of the file is one entry");
		assertEquals("first", parse(lines.get(0)).get("message"));
		assertEquals("second", parse(lines.get(1)).get("message"));
		assertEquals("to the same file", parse(lines.get(1)).get("error.message"));
	}
}
