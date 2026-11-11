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

### Logger benchmark

`LoggerBenchmark` (in `src/test/java`, package `us.bringardner.parley.logging.benchmark`) is a
[JMH](https://github.com/openjdk/jmh) benchmark of what Parley logging costs. It compares:

1. `ParleyLogger`: a `ParleyLogger` called directly, as the `System.Logger` it is.
2. `BaseObject`: a Parley object logging through `BaseObject` and the JDK's `System.Logger` to a
   `ParleyLogger`, with `parley.log.level=INFO`. This is what Parley code does in an application
   that includes parley-logging.
3. `JavaUtilLogging`: `java.util.logging` called directly, which is what an application with no
   `System.Logger` provider writes with.
4. `BaseObjectNoLevelSet` (`NoLevelSetBenchmark`): the Parley object again with no `parley.log.level`
   set, where the logger holds DEBUG and INFO back and looks at the settings for each such message.
   Only the level check and the debug calls are measured.

It measures level checks, debug calls when debug is off (with a constant message, a concatenated one
and a `Supplier`), info written to a file from one thread and from four, and warn with a stack
trace, every entry written to the file as soon as it is logged. `BatchedLoggerBenchmark` measures the
file-writing cases again with entries collected and written together: `ParleyLogger` with
`parley.log.flushInterval=500ms`, and `java.util.logging` with a `FileHandler` that writes every
500 ms (`java.util.logging` has no setting for it). Run them all with

```bash
mvn -Pbenchmark test
```

It takes about 10 minutes. The benchmark profile builds in `target/benchmark` (apart from Eclipse's
classes) and runs the benchmarks in place of the unit tests; normal builds don't run it. After JMH's
own table it prints how the loggers compare, also saved to `target/benchmark/relative.txt`:

1. Every entry written at once: for each benchmark the fastest logger is 1.00, the others multiples of its time.
2. Batched: the same, among the batched loggers.
3. Batched against every entry written at once: each logger's batched time divided by its own unbatched
   time (0.25 is four times faster batched).

JMH options go in `-Djmh.args` (they replace the default, which also writes
`target/benchmark/logger-benchmark.json`). For example:

```bash
# 3 JVMs per benchmark, for steadier results with an error margin
mvn -Pbenchmark test -Djmh.args="-f 3"
# only the info benchmarks, 8 threads, two of the loggers
mvn -Pbenchmark test -Djmh.args="LoggerBenchmark.info -t 8 -p loggerType=ParleyLogger,BaseObject"
# JMH's own help
mvn -Pbenchmark test -Djmh.args="-h"
```

Results are for comparing loggers, or one version against another, on the same machine and JDK.
Differences under a nanosecond (the debug benchmarks) are within noise unless they hold up with `-f 3`.

## License

Apache License 2.0.
