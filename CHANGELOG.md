# Changelog

## parley-logging 2.0.0 (unreleased)

### Added (2026-11-11: To-Do 19.4, the logger benchmark)

- **Logger benchmark** (`mvn -Pbenchmark test`, JMH, test code only): parley-core's benchmark of the
  `ILogger` implementations, brought up to date for logging through `System.Logger`. It compares a
  `ParleyLogger` called directly, a Parley object logging through `BaseObject` and `System.Logger`
  to a `ParleyLogger`, and `java.util.logging` called directly, every entry written at once and
  batched; and what a debug call costs when no `parley.log.level` is set. See the README.

### Changed (2026-11-11: To-Do 19.5, no application sets a level)

- **`WARN` is every logger's default, Parley's included**: a logger named under `us.bringardner.`
  lets everything through only when a `parley.log.level` system property covers its name (BaseObject
  has decided with it). When none does it lets `WARN` and `ERROR` through, where it let everything
  through before, so an application no longer sets `parley.log.level` at startup to keep DEBUG and
  INFO out. A setting made after the logger was made counts. `ParleyLogger.setLevel` wins.
- A level set only with `BaseObject.setLogLevel`, or a `parley.log.level` only in an object's own
  configuration, no longer shows INFO or DEBUG by itself: the logger holds them back until a
  `parley.log.level` system property covers the object's logger.
- `ParleyLoggerFinder.DEFAULT_LEVEL` is the default; it replaces `DEFAULT_OTHER_LEVEL`, which is removed.

### Changed (2026-11-11: To-Do 19.6)

- **ISO 8601 time stamp**: an entry starts with `yyyy-MM-dd'T'HH:mm:ss.SSS+HH:MM`, the local time with
  its offset from UTC (`2026-11-11T06:52:03.123-05:00`), in place of `MM-dd-yyyy HH:mm:ss.SSS` with no
  zone.

### Added (2026-11-10: To-Do 19.5)

- **New module**: `ParleyLogger` moved here from parley-core (package `us.bringardner.parley.logging`).
  It is now a `System.Logger` and no longer extends `BaseObject`, so it can't log into itself; its
  settings (`parley.log.file`, `parley.log.fileMaxSize`, `parley.log.fileCount`,
  `parley.log.flushInterval`) are system properties.
- **`ParleyLoggerFinder`**: the `System.LoggerFinder` that makes `ParleyLogger` the JVM's
  `System.Logger`. Loggers under `us.bringardner.` let everything through (BaseObject has decided);
  any other logger uses the nearest `parley.log.level` system property, `WARN` when none is set.
