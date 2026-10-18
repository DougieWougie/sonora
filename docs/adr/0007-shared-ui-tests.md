# 0007. UI tests shared between Robolectric and device runs

Status: Accepted

## Context

Compose UI tests are valuable but slow on a device. The project is developed test-first, so most UI tests need to run quickly in the normal `testDebugUnitTest` loop, yet still be checkable on real hardware.

## Decision

Keep Compose UI tests in `src/sharedTest`, added to both the `test` and `androidTest` source sets in `app/build.gradle.kts`. They run on the JVM under Robolectric with `./gradlew testDebugUnitTest`, and on a device with `./gradlew connectedDebugAndroidTest`. Plain tests use JUnit 5, and the vintage engine runs the JUnit 4 Compose/Robolectric tests.

## Consequences

- One copy of each UI test serves both environments.
- Tests must avoid APIs that differ between Robolectric and a device.
- Both JUnit 4 and JUnit 5 are on the test classpath.
