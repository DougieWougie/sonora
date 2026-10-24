# 0008. Defer extra indexes on the episode table

Status: Accepted

## Context

A pre-release review suggested indexes on `episode.downloadState`, `episode.played` and `episode.publishedAtMillis`. The table already has indexes on `(podcastId, guid)` and `enclosureUrl`, which cover the lookups the sync and download code do per episode.

The unindexed queries are the library-wide ones: `downloadState IN (...)`, `played = 0 ORDER BY publishedAtMillis DESC LIMIT n`, and the unplayed count. They scan the table, which holds one user's subscriptions, so thousands of rows at most. `played` and `downloadState` have only two to five distinct values, so an index on either alone would rarely help the planner.

The database is at version 1 with `exportSchema = true`, no migrations and no destructive fallback. Any index changes the schema, so it needs version 2, a `Migration`, and a migration test against the exported schemas.

## Decision

Add no further episode indexes for now. Revisit when a measurement shows one of the scanning queries is slow on a realistic library, such as a few tens of thousands of episodes on a low-end device.

If that happens, the candidates are:

- `(played, publishedAtMillis)` for the unplayed list and count.
- `(downloadState)` as a partial or composite index if the downloads screen is the slow one.
- `(podcastId, publishedAtMillis)` for the per-podcast list, replacing a sort with an index walk.

Ship it as its own change: bump the version, write the `Migration`, and cover it with a migration test.

## Consequences

- No schema migration is needed for the first test release, and version 1 stays free of untested migration code.
- The library-wide queries keep scanning the table. This is unmeasured, not known to be a problem.
- Any later index costs a migration, so it should be bundled with other schema changes where possible.
