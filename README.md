<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable-nodpi/sonora_logo_on_dark.png">
    <img alt="Sonora" src="app/src/main/res/drawable-nodpi/sonora_logo_on_light.png" width="420">
  </picture>
</p>

<p align="center">An open-source podcast app for Android, built with Material 3 Expressive.</p>

## Features

- Subscribe to podcasts, browse your library as a grid or list, and keep an inbox of new episodes.
- Find shows through Apple's podcast directory, PodcastIndex (with your own free API key), a pasted feed address, or an OPML import.
- Playback with variable speed, silence skipping, a sleep timer and an up-next queue, from a mini player that expands into the full player.
- Offline downloads, with options to download on unmetered networks only and to delete episodes once played.
- Subscriptions and listening progress sync through the Nextcloud GPodder app. You approve access in the browser with Nextcloud's Login Flow v2, so Sonora never sees your password.
- Light, dark and AMOLED themes, with colours drawn from the playing episode's artwork.

## Privacy and security

- HTTPS only. Cleartext (HTTP) is off unless you turn it on in Settings, and `http://` episode, download and artwork addresses are fetched over HTTPS instead.
- No Google Play Services, Firebase or other proprietary dependencies, so it is suitable for F-Droid.
- Nextcloud app tokens are kept in encrypted storage.

## Building

You need JDK 17 and the Android SDK.

```sh
./gradlew assembleDebug             # build the debug APK
./gradlew testDebugUnitTest         # unit tests and Compose UI tests on the JVM (Robolectric)
./gradlew connectedDebugAndroidTest # the Compose UI tests on a connected device
./gradlew lintDebug                 # lint
```

## Contributing

The project is developed test-first: write a failing test, make it pass, then tidy up. Kotlin files carry an `SPDX-License-Identifier: GPL-3.0-or-later` header. See [CLAUDE.md](CLAUDE.md) for the full conventions.

## Licence

Sonora is free software, released under the [GNU General Public License v3.0 or later](LICENSE).
