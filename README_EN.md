# Voyah Music

**Interface languages:** English, Russian, Kazakh · [Русский README](README.md)

A companion app for [Open Voyah Controls](https://github.com/siarheisinelnikau/open-voyah-controls). **Voyah Music** plays music on the car display, and **Voyah Music Transfer** sends tracks from an Android phone.

- Pair the phone with a QR code and send music over Wi-Fi.
- Organize tracks into folders and search by title, artist, or album.
- Listen to saved music offline and use system controls for background playback.

![Voyah Music library and playback controls on the head unit](docs/screenshots/player.png)
![Voyah Music appearance and update settings on the head unit](docs/screenshots/player-settings.png)

<p>
  <img src="docs/screenshots/transfer.png" alt="Voyah Music Transfer connection and file selection" width="280">
  <img src="docs/screenshots/transfer-settings.png" alt="Voyah Music Transfer appearance and update settings" width="280">
</p>

![Voyah Music pairing QR on the car display in the test environment](docs/screenshots/receive.png)

## Build

Use JDK 17 or newer and Android SDK 35. Build the head unit and phone APKs from a single `:app` module, with one activity per variant.

```sh
./gradlew :app:assemblePlayerDebug :app:assembleTransferDebug
./gradlew :app:testPlayerDebugUnitTest :app:testTransferDebugUnitTest
```

The Kotlin source sets keep the shared transfer and update code in `app/src/main` and the Compose screens, ViewModels, and platform integrations for each app in `app/src/player` and `app/src/transfer`.

The interface uses Compose layouts and Canvas artwork based on the original screens.

## Transfer protocol

The current wire protocol is version **2**. The version appears in both the pairing QR code and the request header. Version 2 clients and receivers use the same request framing and credentials as before. The receiver rejects an unsupported version before processing an upload, and the phone displays a compatibility error for an unsupported QR version or a receiver's version-rejection response. Use compatible versions of both apps.

[GPLv3](LICENSE)
