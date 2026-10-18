# 0006. Playback owned by a Media3 service, reached through PlaybackHub

Status: Accepted

## Context

Audio must keep playing, and be controllable from the notification and lock screen, when no screen is visible. ViewModels are created and destroyed with the UI, so they cannot own the player.

## Decision

Playback lives in `PodcastMediaSessionService` (Media3). Its rules live in `PlaybackStateManager`, which talks to the player only through the small `PlayerEngine` interface. `PlaybackHub` is a process-wide handle that the service attaches the manager to, and ViewModels depend on the `PlaybackPort` interface rather than on the hub or the player.

## Consequences

- Playback survives the UI, and the playback rules are unit-testable on the JVM with a fake engine.
- Commands sent before the service is running wait up to a timeout for it to attach.
- The manager is not thread-safe and must be driven from the player's own dispatcher (Main).
- The hub is global mutable state, which is acceptable because there is exactly one player per process.
