# Changelog

## parley-logging 2.0.0 (unreleased)

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
