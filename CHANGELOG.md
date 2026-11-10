# Changelog

## parley-logging 2.0.0 (unreleased)

### Added (2026-11-10: To-Do 19.5)

- **New module**: `ParleyLogger` moved here from parley-core (package `us.bringardner.parley.logging`).
  It is now a `System.Logger` and no longer extends `BaseObject`, so it can't log into itself; its
  settings (`parley.log.file`, `parley.log.fileMaxSize`, `parley.log.fileCount`,
  `parley.log.flushInterval`) are system properties.
- **`ParleyLoggerFinder`**: the `System.LoggerFinder` that makes `ParleyLogger` the JVM's
  `System.Logger`. Loggers under `us.bringardner.` let everything through (BaseObject has decided);
  any other logger uses the nearest `parley.log.level` system property, `WARN` when none is set.
