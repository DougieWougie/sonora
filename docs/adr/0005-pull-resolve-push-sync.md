# 0005. Sync by pull, resolve, then push; latest timestamp wins

Status: Accepted

## Context

Both the device and the server can change the same subscription or episode state between syncs, and the two clocks cannot be trusted to agree.

## Decision

`SyncCoordinator` pulls remote changes first, resolves conflicts, then pushes local ones. Conflicts resolve to the most recent timestamp, with ties going to the local device. Cursors are the server's own UNIX-second clock, never the device clock. Network calls happen outside database transactions, and each phase commits its cursor only after its local changes are stored. Within the background run, the remote step goes first so newly synced subscriptions are refreshed in the same run.

## Consequences

- Pushing before pulling would let local state always win, which would defeat the timestamp rule.
- A device with a badly wrong clock can still lose or win conflicts unfairly, because event timestamps come from the device.
- The sync backend is hidden behind `SyncProvider`, so a server other than Nextcloud GPodder could be added without changing the coordinator.
