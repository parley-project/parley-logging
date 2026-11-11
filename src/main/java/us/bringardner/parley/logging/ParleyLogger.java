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
package us.bringardner.parley.logging;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import us.bringardner.parley.core.LogContext;
import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.core.util.Durations;

/**
 * Parley's own log writer: a {@link System.Logger} that writes one line an entry
 * ({@code yyyy-MM-dd'T'HH:mm:ss.SSS+HH:MM [thread] LEVEL name - message}, then any stack trace) to System.out,
 * System.err or a log file. {@link ParleyLoggerFinder} makes it the JVM's System.Logger, so that
 * BaseObject's logging (and anything else that logs through System.Logger) is written here.
 * <p>
 * With {@value #PROPERTY_LOG_FORMAT} set to json, an entry is one JSON object on a line instead
 * (see {@link #PROPERTY_LOG_FORMAT}), for a log store that indexes fields.
 * <p>
 * It is not a BaseObject, so it never logs into itself. Its settings are system properties, read when
 * the logger is made: {@value #PROPERTY_LOG_FILE}, {@value #PROPERTY_LOG_FILE_MAX_SIZE},
 * {@value #PROPERTY_LOG_FILE_COUNT}, {@value #PROPERTY_LOG_FLUSH_INTERVAL} and {@value #PROPERTY_LOG_FORMAT}.
 */
public class ParleyLogger implements System.Logger {

	/** A file to append to, or System.out (the default) or System.err. */
	public static final String PROPERTY_LOG_FILE = "parley.log.file";
	/**
	 * When the log file would grow past this size it is renamed (to logFile.1, the old logFile.1 to logFile.2 ...)
	 * and a new one is started. Bytes, or with a K, M or G suffix ("10M"). The default (0) is no limit.
	 */
	public static final String PROPERTY_LOG_FILE_MAX_SIZE = "parley.log.fileMaxSize";
	/**
	 * How many old log files are kept when {@link #PROPERTY_LOG_FILE_MAX_SIZE} is set, the default is
	 * {@link #DEFAULT_LOG_FILE_COUNT}. With 0 the log file is started again from empty.
	 */
	public static final String PROPERTY_LOG_FILE_COUNT = "parley.log.fileCount";
	public static final int DEFAULT_LOG_FILE_COUNT = 5;
	/**
	 * The time between flushes of a log file, a duration ({@code 500ms}, {@code 1s}). By default (0) every
	 * entry is written to the file as soon as it is logged. With a value (100ms to 1s is typical) entries
	 * are collected and written together, which is several times cheaper for a busy logger; the cost is
	 * that the last entries (up to this long) are lost if the JVM is killed. They are written at a normal exit and
	 * by {@link #closeLogFiles()}. Like the maximum size, the first logger to open a file decides it.
	 */
	public static final String PROPERTY_LOG_FLUSH_INTERVAL = "parley.log.flushInterval";

	/**
	 * How an entry is written: {@value #FORMAT_TEXT} (the default), one line of text, or
	 * {@value #FORMAT_JSON}, one JSON object on a line, so a log store can index its fields without
	 * parsing text. The JSON fields have the Elastic Common Schema's names, in this order:
	 * <ol>
	 * <li>{@code @timestamp}: the time, as in the text form (2026-11-11T06:52:03.123-05:00).</li>
	 * <li>{@code log.level}: ERROR, WARN, INFO or DEBUG.</li>
	 * <li>{@code message}: the message.</li>
	 * <li>{@code ecs.version}: {@value #ECS_VERSION}.</li>
	 * <li>{@code log.logger}: the logger's name.</li>
	 * <li>{@code process.thread.name}: the thread that logged.</li>
	 * <li>{@code labels.context}: the ID of the work the thread is doing (parley-core's LogContext: a
	 *     server's session, a queued message), when it has one. BaseObject puts that ID in front of
	 *     the message text ("[Pop3Server-4127] ..."); here it is taken out of the text.</li>
	 * <li>{@code error.type}, {@code error.message} and {@code error.stack_trace}: an exception's class,
	 *     message (when it has one) and stack trace, when the entry has an exception.</li>
	 * </ol>
	 * @since 2.0.0
	 */
	public static final String PROPERTY_LOG_FORMAT = "parley.log.format";
	public static final String FORMAT_TEXT = "text";
	public static final String FORMAT_JSON = "json";
	/** The version of the Elastic Common Schema the JSON field names are from. */
	public static final String ECS_VERSION = "1.2.0";

	//  DateTimeFormatter is immutable and thread safe, so no locking is required.
	private static final DateTimeFormatter SECOND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	//  "xxx" is the offset from UTC as +HH:MM (+00:00 for UTC)
	private static final DateTimeFormatter OFFSET_FORMAT = DateTimeFormatter.ofPattern("xxx");

	/**
	 * The date, time and offset from UTC for one second, so only the milliseconds are worked out for
	 * each entry. The offset is worked out for each second, so it follows daylight saving time.
	 */
	private static final class Stamp {
		final long second;
		/** "yyyy-MM-dd'T'HH:mm:ss" */
		final String text;
		/** "+HH:MM" */
		final String offset;

		Stamp(long second) {
			this.second = second;
			ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(second), ZoneId.systemDefault());
			this.text = SECOND_FORMAT.format(time);
			this.offset = OFFSET_FORMAT.format(time);
		}
	}

	private static volatile Stamp stamp = new Stamp(0);

	/**
	 * Appends the time in ISO 8601 form, "yyyy-MM-dd'T'HH:mm:ss.SSS+HH:MM": the default time zone's
	 * local time with its offset from UTC (2026-11-11T06:52:03.123-05:00).
	 */
	static void appendTimestamp(StringBuilder out, long millis) {
		long second = Math.floorDiv(millis, 1000L);
		Stamp s = stamp;
		if( s.second != second ) {
			s = new Stamp(second);
			stamp = s;
		}
		int ms = (int) Math.floorMod(millis, 1000L);
		out.append(s.text).append('.');
		if( ms < 100 ) {
			out.append('0');
		}
		if( ms < 10 ) {
			out.append('0');
		}
		out.append(ms).append(s.offset);
	}


	private final String name;
	private volatile LogLevel level;
	private volatile PrintStream _out;
	private volatile PrintStream _err;
	/** True when entries are written as JSON ({@link #PROPERTY_LOG_FORMAT}) */
	private final boolean json;

	/**
	 * A logger that lets everything through (DEBUG), writing where {@value #PROPERTY_LOG_FILE} says.
	 *
	 * @param name the logger's name
	 */
	public ParleyLogger(String name) {
		this(name, LogLevel.DEBUG);
	}

	/**
	 * @param name  the logger's name
	 * @param level the level it lets through (null is NONE)
	 */
	public ParleyLogger(String name, LogLevel level) {
		this.name = name == null ? "" : name;
		setLevel(level);
		this.json = parseFormat(System.getProperty(PROPERTY_LOG_FORMAT));
		String tmp = System.getProperty(PROPERTY_LOG_FILE);
		if( tmp != null ) {
			tmp = tmp.trim();
			if( tmp.equals("System.err") ) {
				_out = System.err;
				_err = System.err;
			} else if( !tmp.isEmpty() && !tmp.equals("System.out") ) {
				long maxSize = parseSize(System.getProperty(PROPERTY_LOG_FILE_MAX_SIZE));
				int count = parseCount(System.getProperty(PROPERTY_LOG_FILE_COUNT));
				int flushInterval = parseInterval(System.getProperty(PROPERTY_LOG_FLUSH_INTERVAL));
				PrintStream ps = openLogFile(tmp, maxSize, count, flushInterval);
				if( ps != null ) {
					_out = ps;
					_err = ps;
				}
			}
		}
	}

	@Override
	public String getName() {
		return name;
	}

	/**
	 * @param level the level this logger lets through (null is NONE)
	 */
	public void setLevel(LogLevel level) {
		this.level = level == null ? LogLevel.NONE : level;
	}

	/**
	 * @return the level this logger lets through
	 */
	public LogLevel getLevel() {
		return level;
	}

	public PrintStream getOut() {
		PrintStream ret = _out;
		return ret != null ? ret : System.out;
	}

	public void setOut(PrintStream out) {
		_out = out;
	}

	/**
	 * @return the stream used for entries that include a stack trace.
	 * Unless set, this is the same as getOut() so all entries stay in order in one place.
	 */
	public PrintStream getErr() {
		PrintStream ret = _err;
		return ret != null ? ret : getOut();
	}

	public void setErr(PrintStream err) {
		_err = err;
	}

	@Override
	public boolean isLoggable(Level target) {
		return level.allows(LogLevel.of(target));
	}

	@Override
	public void log(Level target, ResourceBundle bundle, String msg, Throwable error) {
		if( isLoggable(target) ) {
			LogLevel l = LogLevel.of(target);
			String text = localize(bundle, msg);
			if( error == null ) {
				logMessage(l, text, l == LogLevel.ERROR ? getErr() : getOut());
			} else {
				logMessage(l, text, error, getErr());
			}
		}
	}

	@Override
	public void log(Level target, ResourceBundle bundle, String format, Object... params) {
		if( isLoggable(target) ) {
			LogLevel l = LogLevel.of(target);
			String text = localize(bundle, format);
			if( params != null && params.length > 0 ) {
				try {
					text = new MessageFormat(text, Locale.getDefault(Locale.Category.FORMAT)).format(params);
				} catch (IllegalArgumentException e) {
					//  Not a valid pattern: the text as it is
				}
			}
			logMessage(l, text, l == LogLevel.ERROR ? getErr() : getOut());
		}
	}

	private static String localize(ResourceBundle bundle, String msg) {
		if( bundle != null && msg != null ) {
			try {
				return bundle.getString(msg);
			} catch (MissingResourceException | ClassCastException e) {
				//  Not in the bundle: the key is the message
			}
		}
		return msg;
	}

	/** @return true for json, false for text, nothing or an invalid value (which is reported to System.err) */
	private static boolean parseFormat(String value) {
		if( value == null || value.trim().isEmpty() ) {
			return false;
		}
		String tmp = value.trim();
		if( tmp.equalsIgnoreCase(FORMAT_JSON) ) {
			return true;
		}
		if( !tmp.equalsIgnoreCase(FORMAT_TEXT) ) {
			System.err.println("Invalid "+PROPERTY_LOG_FORMAT+" ("+value+"). Using "+FORMAT_TEXT);
		}
		return false;
	}

	private static int parseCount(String value) {
		if( value == null || value.trim().isEmpty() ) {
			return DEFAULT_LOG_FILE_COUNT;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			System.err.println("Invalid "+PROPERTY_LOG_FILE_COUNT+" ("+value+"). Using "+DEFAULT_LOG_FILE_COUNT);
			return DEFAULT_LOG_FILE_COUNT;
		}
	}

	private static int parseInterval(String value) {
		if( value == null || value.trim().isEmpty() ) {
			return 0;
		}
		try {
			return Durations.toIntMillis(Durations.parse(value));
		} catch (IllegalArgumentException e) {
			System.err.println("Invalid "+PROPERTY_LOG_FLUSH_INTERVAL+" ("+value+"): "+e.getMessage()+". Every entry is written at once.");
			return 0;
		}
	}

	/**
	 * The log file streams that are written by a timer ({@link #PROPERTY_LOG_FLUSH_INTERVAL}). They are plain
	 * PrintStreams: println of a PrintStream subclass takes a slower path (two writes and two locks).
	 */
	private static final java.util.Set<PrintStream> deferredFiles = ConcurrentHashMap.newKeySet();

	private static ScheduledExecutorService flusher;

	/** Flush this stream every interval milliseconds, and when the JVM exits. */
	private static synchronized void scheduleFlush(PrintStream ps, long interval) {
		if( flusher == null ) {
			flusher = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "ParleyLogger-flush");
				t.setDaemon(true);
				return t;
			});
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				for(PrintStream p : logFiles.values()) {
					p.flush();
				}
			}, "ParleyLogger-shutdown"));
		}
		flusher.scheduleWithFixedDelay(ps::flush, interval, interval, TimeUnit.MILLISECONDS);
	}

	//  One PrintStream per log file, shared by all ParleyLoggers, so the file is opened once (in append mode).
	private static final ConcurrentHashMap<String, PrintStream> logFiles = new ConcurrentHashMap<>();
	//  The file streams under those PrintStreams, for closeLogFiles()
	private static final ConcurrentHashMap<String, LogFileStream> logFileStreams = new ConcurrentHashMap<>();

	/**
	 * Flush every log file and close it, so another program can move or delete it (Windows doesn't allow
	 * that while the file is open). Logging can go on: a file is opened again by the next entry written to it.
	 * Call it, for example, before the files are archived, or when the application shuts down.
	 */
	public static void closeLogFiles() {
		for(PrintStream ps : logFiles.values()) {
			ps.flush();
		}
		for(LogFileStream stream : logFileStreams.values()) {
			stream.release();
		}
	}

	/**
	 * Open (or find) the stream for a log file. The first logger to open a file decides its maximum
	 * size and how many old files are kept; loggers that name the same file share the stream.
	 */
	private static PrintStream openLogFile(String fileName, long maxSize, int count, int flushInterval) {
		File file = new File(fileName).getAbsoluteFile();
		String key = file.getPath();
		try {
			key = file.getCanonicalPath();
		} catch (IOException e) {
			// use the absolute path
		}
		PrintStream ret = logFiles.get(key);
		if( ret == null ) {
			synchronized (logFiles) {
				ret = logFiles.get(key);
				if( ret == null ) {
					try {
						File dir = file.getParentFile();
						if( dir != null && !dir.exists() ) {
							dir.mkdirs();
						}
						//  Buffered, and flushed at the end of every log entry (autoflush), so an entry is
						//  written with one write instead of one per line of a stack trace. With a flush
						//  interval the entries are collected and a timer writes them.
						LogFileStream stream = new LogFileStream(file, maxSize, count);
						boolean deferred = flushInterval > 0;
						ret = new PrintStream(new BufferedOutputStream(stream, deferred ? 32768 : 8192), !deferred);
						if( deferred ) {
							deferredFiles.add(ret);
							scheduleFlush(ret, flushInterval);
						}
						logFileStreams.put(key, stream);
						logFiles.put(key, ret);
					} catch (IOException e) {
						System.err.println("Error opening log file "+file+ " e="+e+". Logging to System.out");
						ret = null;
					}
				}
			}
		}
		return ret;
	}

	/**
	 * @param value a size in bytes, or with a K, M or G suffix (an optional B after it is allowed: "10MB")
	 * @return the size in bytes, 0 for null, empty or invalid values
	 */
	static long parseSize(String value) {
		if( value == null || value.trim().isEmpty() ) {
			return 0;
		}
		String tmp = value.trim().toUpperCase(Locale.ROOT);
		if( tmp.length() > 1 && tmp.endsWith("B") ) {
			tmp = tmp.substring(0, tmp.length()-1).trim();
		}
		long unit = 1;
		if( tmp.endsWith("K") ) {
			unit = 1024;
		} else if( tmp.endsWith("M") ) {
			unit = 1024*1024;
		} else if( tmp.endsWith("G") ) {
			unit = 1024L*1024*1024;
		}
		if( unit > 1 ) {
			tmp = tmp.substring(0, tmp.length()-1).trim();
		}
		try {
			long ret = Long.parseLong(tmp)*unit;
			if( ret >= 0 ) {
				return ret;
			}
		} catch (NumberFormatException e) {
		}
		System.err.println("Invalid "+PROPERTY_LOG_FILE_MAX_SIZE+" ("+value+"). The log file will not be rotated.");
		return 0;
	}

	/**
	 * Appends to a log file, starts a new one when it would grow past the maximum size, and reports
	 * write errors (a full disk, say) on System.err. PrintStream hides them, so before this, log
	 * entries could be lost without any sign.
	 * <p>
	 * At most once every {@link #CHECK_MILLIS} it also checks that the file is still there: if another
	 * program (logrotate, say) has moved or deleted it, a new file is started under the same name.
	 * Before, entries went on being written to the moved (or deleted) file, and the new one stayed empty.
	 */
	static final class LogFileStream extends OutputStream {
		/** How often (milliseconds) a write checks that the file hasn't been moved or deleted. */
		static final long CHECK_MILLIS = 1000;

		private final File file;
		private final long maxSize;
		private final int count;
		//  null when the file isn't open (see release()); the next write opens it
		private FileOutputStream out;
		private long size;
		//  The file system's identity of the open file (null where there isn't one, Windows for example)
		private Object fileKey;
		private long lastCheck;
		//  true from the first failed write until a write works again, so each failure is reported once
		private boolean failing;

		LogFileStream(File file, long maxSize, int count) throws IOException {
			this.file = file;
			this.maxSize = maxSize;
			this.count = Math.max(count, 0);
			open();
		}

		private void open() throws IOException {
			out = new FileOutputStream(file, true);
			size = file.length();
			fileKey = fileKey();
			lastCheck = System.nanoTime();
		}

		private Object fileKey() {
			try {
				return Files.readAttributes(file.toPath(), BasicFileAttributes.class).fileKey();
			} catch (IOException | RuntimeException e) {
				return null;
			}
		}

		/**
		 * Start a new file if the open one has been moved or deleted (checked at most every CHECK_MILLIS).
		 * A file that was truncated in place (logrotate's copytruncate) is still written to, from its new end.
		 */
		private void checkFile() throws IOException {
			long now = System.nanoTime();
			if( now-lastCheck < CHECK_MILLIS*1_000_000L ) {
				return;
			}
			lastCheck = now;
			Object key = fileKey();
			if( key == null && !file.exists() ) {
				//  Deleted, or moved and not replaced
				reopen();
			} else if( key != null && fileKey != null && !key.equals(fileKey) ) {
				//  Moved, and a new file created under the name
				reopen();
			} else {
				long length = file.length();
				if( length < size ) {
					//  Truncated: count from the new size so rotation by size still works
					size = length;
				}
			}
		}

		private void reopen() throws IOException {
			try {
				out.close();
			} catch (IOException e) {
			}
			out = null;
			open();
		}

		/**
		 * Close the file (see {@link ParleyLogger#closeLogFiles()}); the next write opens it again.
		 */
		synchronized void release() {
			if( out != null ) {
				try {
					out.close();
				} catch (IOException e) {
				}
				out = null;
			}
		}

		@Override
		public synchronized void write(int b) throws IOException {
			write(new byte[] {(byte) b}, 0, 1);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			try {
				if( out == null ) {
					//  Closed by release()
					open();
				} else {
					checkFile();
				}
				if( maxSize > 0 && size > 0 && size+len > maxSize ) {
					rotate();
				}
				out.write(b, off, len);
				size += len;
				if( failing ) {
					failing = false;
					System.err.println("ParleyLogger: writing to "+file+" works again.");
				}
			} catch (IOException e) {
				if( !failing ) {
					failing = true;
					System.err.println("ParleyLogger: can't write to "+file+" ("+e+"). Log entries are being lost.");
				}
				throw e;
			}
		}

		/** file.1 is the newest old file, file.&lt;count&gt; the oldest */
		private File old(int i) {
			return new File(file.getPath()+"."+i);
		}

		private void rotate() throws IOException {
			try {
				out.close();
			} catch (IOException e) {
			}
			try {
				if( count > 0 ) {
					for(int i=count-1; i > 0; i-- ) {
						File from = old(i);
						if( from.exists() ) {
							Files.move(from.toPath(), old(i+1).toPath(), StandardCopyOption.REPLACE_EXISTING);
						}
					}
					Files.move(file.toPath(), old(1).toPath(), StandardCopyOption.REPLACE_EXISTING);
				} else {
					Files.delete(file.toPath());
				}
			} catch (IOException e) {
				System.err.println("ParleyLogger: can't start a new log file, still appending to "+file+" ("+e+")");
				open();
				//  Don't try again until the file has grown by another maxSize
				size = 0;
				return;
			}
			open();
		}

		@Override
		public synchronized void flush() throws IOException {
			if( out != null ) {
				out.flush();
			}
		}

		@Override
		public synchronized void close() throws IOException {
			if( out != null ) {
				out.close();
				out = null;
			}
		}
	}

	protected void logMessage(LogLevel level, String msg, PrintStream out) {
		out.println(json ? formatJson(level, msg, null) : formatMessage(level, msg));
	}

	private void logMessage(LogLevel level, String msg, Throwable error, PrintStream out) {
		if( json ) {
			//  The stack trace is inside the entry: one line, one print
			out.println(formatJson(level, msg, error));
			return;
		}
		String line = formatMessage(level, msg);
		//  One print, so the message and stack trace stay together when several threads are logging
		//  and a log file gets them in one write (printStackTrace writes and flushes every line).
		StringWriter text = new StringWriter();
		PrintWriter pw = new PrintWriter(text);
		pw.println(line);
		error.printStackTrace(pw);
		pw.flush();
		out.print(text.toString());
		if( !deferredFiles.contains(out) ) {
			out.flush();
		}
	}

	private String formatMessage(LogLevel level, String msg) {
		String thread = Thread.currentThread().getName();
		String lvl = level.toString();
		StringBuilder ret = new StringBuilder(40 + thread.length() + lvl.length() + name.length() + (msg == null ? 4 : msg.length()));
		appendTimestamp(ret, System.currentTimeMillis());
		return ret.append(" [").append(thread).append("] ").append(lvl).append(' ').append(name).append(" - ").append(msg).toString();
	}

	/**
	 * An entry as one JSON object ({@link #PROPERTY_LOG_FORMAT} lists the fields).
	 *
	 * @param error the entry's exception, or null
	 */
	private String formatJson(LogLevel level, String msg, Throwable error) {
		String thread = Thread.currentThread().getName();
		String text = String.valueOf(msg);
		//  The ID of the work this thread is doing. BaseObject put it in front of the message: "[id] message"
		String context = LogContext.get();
		if( context != null && text.length() >= context.length() + 3 && text.charAt(0) == '['
				&& text.startsWith(context, 1) && text.startsWith("] ", context.length() + 1) ) {
			text = text.substring(context.length() + 3);
		}
		StringBuilder ret = new StringBuilder(160 + thread.length() + name.length() + text.length());
		ret.append("{\"@timestamp\":\"");
		appendTimestamp(ret, System.currentTimeMillis());
		ret.append("\",\"log.level\":\"").append(level.toString()).append('"');
		appendJsonField(ret, "message", text);
		appendJsonField(ret, "ecs.version", ECS_VERSION);
		appendJsonField(ret, "log.logger", name);
		appendJsonField(ret, "process.thread.name", thread);
		if( context != null ) {
			appendJsonField(ret, "labels.context", context);
		}
		if( error != null ) {
			appendJsonField(ret, "error.type", error.getClass().getName());
			String errorMessage = error.getMessage();
			if( errorMessage != null ) {
				appendJsonField(ret, "error.message", errorMessage);
			}
			StringWriter trace = new StringWriter();
			PrintWriter pw = new PrintWriter(trace);
			error.printStackTrace(pw);
			pw.flush();
			appendJsonField(ret, "error.stack_trace", trace.toString());
		}
		return ret.append('}').toString();
	}

	/** Appends ,"name":"value" (the name is one of ours and needs no escaping). */
	private static void appendJsonField(StringBuilder out, String name, String value) {
		out.append(",\"").append(name).append("\":\"");
		appendJsonText(out, value);
		out.append('"');
	}

	/**
	 * Appends text as the inside of a JSON string: a quote, a backslash and every control character
	 * (a line end, a tab ...) are escaped, so an entry is always one line and valid JSON.
	 */
	static void appendJsonText(StringBuilder out, String text) {
		for(int i = 0, n = text.length(); i < n; i++) {
			char c = text.charAt(i);
			switch (c) {
			case '"':
				out.append("\\\"");
				break;
			case '\\':
				out.append("\\\\");
				break;
			case '\n':
				out.append("\\n");
				break;
			case '\r':
				out.append("\\r");
				break;
			case '\t':
				out.append("\\t");
				break;
			default:
				//  Other control characters, and the two Unicode line separators that some JSON readers take as line ends
				if( c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029 ) {
					out.append("\\u");
					String hex = Integer.toHexString(c);
					for(int pad = hex.length(); pad < 4; pad++) {
						out.append('0');
					}
					out.append(hex);
				} else {
					out.append(c);
				}
			}
		}
	}
}
