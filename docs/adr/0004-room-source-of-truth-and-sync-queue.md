# 0004. Room as the source of truth, with local changes queued for sync

Status: Accepted

## Context

Subscriptions, listening progress and downloads must work offline and survive failed or interrupted network operations, while still syncing with a Nextcloud server.

## Decision

Room is the single source of truth. Screens observe it through ViewModels and never read the network directly. Every local change that must reach the server (played, position, subscription changes) is written to Room and recorded in a sync queue in the same transaction (`TransactionRunner`). Queues are cleared only after a successful push.

## Consequences

- The UI always works offline and a failed sync never loses local work.
- Sync state (cursors, queues) is part of the schema, so schema changes need migrations; the schema is exported.
- Writers must use the repositories, not the DAOs, when the change needs to be synced, or the queue entry is missed.
