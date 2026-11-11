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

No application has to set a level: when no `parley.log.level` system property covers a logger's
name, the logger lets `WARN` and `ERROR` through.

- A logger named under `us.bringardner.` is Parley's: its messages come through `BaseObject`. When a
  `parley.log.level` system property covers its name (`parley.log.level.pop3.server.Pop3Server`, then
  `parley.log.level.pop3.server`, `parley.log.level.pop3`, then `parley.log.level`), `BaseObject` has
  already decided with it and the logger lets everything through (see parley-core's README). When
  none does, the logger lets `WARN` and `ERROR` through. The settings are looked at each time a
  message below `WARN` arrives, so one made after the logger (a server that loads its properties file
  when it starts) counts.
- Any other logger (the JDK's, JMX's, HttpClient's) uses the nearest `parley.log.level` system
  property for its name (`parley.log.level.javax.management`, then `parley.log.level.javax`, then
  `parley.log.level`), `WARN` when none is set, so their DEBUG and TRACE messages don't flood the log.
  These loggers are made once per name and read their settings then.

`ParleyLogger.setLevel` on a logger wins over both.

The logger's level is the only one a `System.Logger` knows. A level an object was given with
`BaseObject.setLogLevel`, or a `parley.log.level` in an object's own configuration, decides what
`BaseObject` passes on; the logger then decides again. To see a Parley object's INFO or DEBUG
messages, set a `parley.log.level` system property that covers it (`-Dparley.log.level.pop3=DEBUG`).

## Settings

System properties, read when a logger is made:

| Property | Meaning |
|---|---|
| `parley.log.file` | A file to append to, or `System.out` (the default) or `System.err`. Loggers that name the same file share it. |
| `parley.log.fileMaxSize` | Start a new log file when it would grow past this size: bytes, or with a `K`, `M` or `G` suffix (`10M`). The old file is renamed `<file>.1` (the previous `.1` becomes `.2` and so on). The default is no limit. |
| `parley.log.fileCount` | How many old log files to keep, 5 by default. With 0 the log file is started again from empty. |
| `parley.log.format` | `text` (the default) or `json`: one JSON object on a line for each entry (see JSON entries below). |
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

## JSON entries

With `-Dparley.log.format=json` every entry is one JSON object on one line, so a log store (Elastic,
Loki, CloudWatch and the like) can index its fields without parsing text, and a stack trace stays
inside its entry:

```json
{"@timestamp":"2026-11-11T14:20:03.512-05:00","log.level":"INFO","message":"Login succeeded for tony","ecs.version":"1.2.0","log.logger":"us.bringardner.parley.pop3.server.Pop3Server","process.thread.name":"Pop3Server-12","labels.context":"Pop3Server-4127"}
```

The field names are the [Elastic Common Schema](https://www.elastic.co/docs/reference/ecs/logging/intro)'s,
the ones the ECS encoders for Logback and Log4j write, so Parley's entries line up with other
systems' in the same store:

| Field | Value |
|---|---|
| `@timestamp` | The time, as in the text form: local time with its offset from UTC. |
| `log.level` | `ERROR`, `WARN`, `INFO` or `DEBUG`. |
| `message` | The message. |
| `ecs.version` | The version of the schema the names are from. |
| `log.logger` | The logger's name (for a Parley object, its class). |
| `process.thread.name` | The thread that logged. |
| `labels.context` | The ID of the work the thread is doing, when it has one: a server's session (`Pop3Server-4127`) or a queued message's ID (parley-core's `LogContext`). Filter on it to follow one connection or one message. In the text form the ID is in front of the message (`[Pop3Server-4127] Login ...`); here it is taken out of the message. |
| `error.type`, `error.message`, `error.stack_trace` | An exception's class, its message (when it has one) and its stack trace, when the entry has an exception. |

Every value is a string, and the fields are always in this order. The layout is fixed: an
application that needs other fields or another layout can put Logback or Log4j behind `System.Logger`
in place of parley-logging. An ID that an object puts in front of its own message while its thread
has no `LogContext` (a connection logging outside a handler's call) stays in the message text.

## Building and testing

```bash
mvn verify
```

## License

Apache License 2.0.
