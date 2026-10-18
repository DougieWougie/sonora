# Architecture decision records

Short records of decisions that are non-obvious or costly to reverse, kept in the format
Context, Decision, Consequences. See [../ARCHITECTURE.md](../ARCHITECTURE.md) for the overall design.

Add a new record as the next number, never rewrite an accepted one: supersede it with a new record
and update the old one's status to `Superseded by NNNN`.

| # | Decision |
|---|----------|
| [0001](0001-no-proprietary-dependencies.md) | No Google Play Services, Firebase or closed-source binaries |
| [0002](0002-https-by-default.md) | HTTPS by default, with an explicit cleartext opt-in |
| [0003](0003-streaming-hardened-xml-parsing.md) | Streaming, XXE-hardened XML parsing |
| [0004](0004-room-source-of-truth-and-sync-queue.md) | Room as the source of truth, with local changes queued for sync |
| [0005](0005-pull-resolve-push-sync.md) | Sync by pull, resolve, then push; latest timestamp wins |
| [0006](0006-service-owned-playback.md) | Playback owned by a Media3 service, reached through PlaybackHub |
| [0007](0007-shared-ui-tests.md) | UI tests shared between Robolectric and device runs |
