# Project: Open Source Android Podcast App (GPLv3)

## License & Compliance
- **License:** GNU General Public License v3.0 or later (`GPL-3.0-or-later`).
- Include SPDX license headers (`// SPDX-License-Identifier: GPL-3.0-or-later`) in all Kotlin source files.
- **F-Droid Compliance:** Strictly NO proprietary Google Play Services dependencies (`com.google.android.gms.*`), Firebase, or closed-source binaries. Use only open-source Apache 2.0 / MIT / GPLv3 compatible libraries from Maven Central and Google Maven.

## Core Methodology: Strict Test-Driven Development (TDD)
You MUST follow strict Red-Green-Refactor TDD for every feature, parser, repository, and ViewModel:
1. **RED:** Write comprehensive unit tests first (using JUnit 5, MockK, Turbine for Flows, and OkHttp MockWebServer for network/sync).
2. **VERIFY FAILURE:** Run `./gradlew testDebugUnitTest` and verify that the new tests fail for the expected reason.
3. **GREEN:** Write the minimum production code required to make the tests pass.
4. **REFACTOR:** Clean up the code while keeping all tests green.
Never write production logic before writing and running its failing test.

**Coverage:** `./gradlew createDebugUnitTestCoverageReport` writes a JaCoCo report to `app/build/reports/coverage/test/debug/`. `./gradlew verifyCoverage` (part of `check`) fails if line coverage of hand-written code (generated Room/Hilt/Dagger classes excluded) drops below the floor set in `app/build.gradle.kts`; raise the floor when coverage improves, never lower it to make a change pass.

## Architecture & Tech Stack
- **UI Framework:** Jetpack Compose with **Material 3 Expressive** (`androidx.compose.material3` 1.4.0-alpha / 1.5.0+ Expressive APIs, `MaterialExpressiveTheme`, spring physics motion tokens, emphasized typography, and dynamic color).
- **Networking:** OkHttp + Retrofit + `kotlinx-serialization-json`. Enforce HTTPS/TLS by default and disable cleartext traffic except when explicitly configured by the user for local development.
- **XML/RSS Parsing:** Custom streaming parser using `org.xmlpull.v1.XmlPullParser` (for unit testability, inject `XmlPullParserFactory`). Harden against XXE attacks.
- **Media & Downloads:** AndroidX Media3 (`media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`) with `SilenceSkippingAudioProcessor` and PlaybackParameters for speed control.

## Security & Performance Guidelines
- Store Nextcloud app tokens using Android Keystore backed encryption or `DataStore` with Tink encryption.
- Stream all XML/RSS and OPML payloads via `InputStream`; never load entire RSS feeds into memory strings before parsing.
- Use database transactions (`@Transaction`) when reconciling Nextcloud subscription and episode action diffs.

## Architecture
See `docs/ARCHITECTURE.md` for the layers, data flow and subsystems; read it before changing cross-cutting code.
