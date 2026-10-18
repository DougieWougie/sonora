# 0001. No Google Play Services, Firebase or closed-source binaries

Status: Accepted

## Context

Sonora is GPL-3.0-or-later and is meant to be distributable through F-Droid, which rejects apps that depend on proprietary components.

## Decision

Depend only on open-source (Apache 2.0, MIT or GPLv3-compatible) libraries from Maven Central and Google Maven. No `com.google.android.gms.*`, no Firebase, no closed-source binaries. This is a rule in `CLAUDE.md`.

## Consequences

- Features that Play Services would provide are built from open components instead: Media3 for playback, WorkManager for background work, Android Keystore for secrets, and Coil for images.
- Some conveniences are unavailable, for example Google-backed push messaging and sign-in.
- New dependencies need a licence check before they are added.
