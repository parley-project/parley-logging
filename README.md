# parley-logging

Parley's own log writer, for **applications**: `ParleyLogger`, which writes one line an entry to
`System.out`, `System.err` or a log file (rotated by size), and `ParleyLoggerFinder`, the
`System.LoggerFinder` that makes it the JVM's `System.Logger`.

- Java 11 or later
- Depends on parley-core
- Apache License 2.0

Parley's libraries log through parley-core's `BaseObject`, which writes to the JDK's `System.Logger`:
the application decides what writes the messages. An application that includes parley-logging gets
`ParleyLogger`; one that includes another provider (log4j's `log4j-jpl`, SLF4J's
`slf4j-jdk-platform-logging`) gets that; one with none gets java.util.logging.

**Only applications include it, never library code**: a provider takes over all `System.Logger`
output in its JVM, and with two providers the first on the class path wins. The JDK finds a provider
through the system class loader (the class path or module path): a copy in a web application's
`WEB-INF/lib` is not used.

## Getting started

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-logging</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Nothing else is needed: `META-INF/services/java.lang.System$LoggerFinder` installs it.

## Levels

- A logger named under `us.bringardner.` lets everything through: Parley's objects have already
  decided with their own level (`BaseObject.setLogLevel`, or the `parley.log.level` settings; see
  parley-core's README). With neither set, a Parley object logs everything, DEBUG included, so an
  application usually sets `parley.log.level` (fsh sets `WARN` when it isn't set).
- Any other logger (the JDK's, JMX's, HttpClient's) uses the nearest `parley.log.level` system
  property for its name (`parley.log.level.javax.management`, then `parley.log.level.javax`, then
  `parley.log.level`), `WARN` when none is set, so their DEBUG and TRACE messages don't flood the log.

A logger is made once per name and reads its settings then. `ParleyLogger.setLevel` changes one.

## Settings

System properties, read when a logger is made:

| Property | Meaning |
|---|---|
| `parley.log.file` | A file to append to, or `System.out` (the default) or `System.err`. Loggers that name the same file share it. |
| `parley.log.fileMaxSize` | Start a new log file when it would grow past this size: bytes, or with a `K`, `M` or `G` suffix (`10M`). The old file is renamed `<file>.1` (the previous `.1` becomes `.2` and so on). The default is no limit. |
| `parley.log.fileCount` | How many old log files to keep, 5 by default. With 0 the log file is started again from empty. |
| `parley.log.flushInterval` | The time between writes to the log file, a duration (`500ms`). The default is 0: every entry is written as soon as it is logged. With a value (100ms to 1s is typical) entries are collected and written together, which is about ten times cheaper for a busy logger. The last entries (up to that long) are lost if the JVM is killed; they are written at a normal exit and by `ParleyLogger.closeLogFiles()`. |

Entries are `yyyy-MM-dd'T'HH:mm:ss.SSS+HH:MM [thread] LEVEL name - message`, then any stack trace,
written in one piece. The time is ISO 8601: the machine's local time with its offset from UTC
(`2026-11-11T06:52:03.123-05:00`), so logs from machines in different time zones sort and line up. Errors and entries with a stack trace go to the error stream (the file, when there is
one). A message with parameters is formatted with `java.text.MessageFormat`, as `System.Logger`
specifies.

The first logger to open a file decides its size limit. If writing to the log file fails (a full
disk, say), that is reported once on `System.err`, and again when writing works again. If another
program moves or deletes the log file (logrotate, say), a new one is started under the same name
within a second; a file emptied in place (`copytruncate`) is written to from its new end.
`ParleyLogger.closeLogFiles()` flushes and closes all log files so they can be moved (Windows won't
move an open file); the next entry opens the file again.

## Building and testing

```bash
mvn verify
```

## License

Apache License 2.0.
