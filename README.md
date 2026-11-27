# Android Remote

An Android Bluetooth HID keyboard remote for PowerPoint. Use the phone's volume
buttons to move between slides: volume up advances, and volume down goes back.

## Requirements

- Android 9 (API 28) or newer.
- A phone that supports the Bluetooth HID Device profile. This support depends
  on the phone manufacturer and Android build.
- A Windows PC with Bluetooth.

The app does not need a companion program on the PC. It presents as a Bluetooth
keyboard; Windows and PowerPoint receive standard Page Up/Page Down keystrokes.
Keep the app open in the foreground while presenting.

## Pair and connect

1. Open the app and grant the requested nearby Bluetooth permissions.
2. Tap **Make phone discoverable**, then use Windows Bluetooth settings to add
   the phone as a Bluetooth device.
3. Return to the app and tap the paired PC in the device list to connect.
4. Open a PowerPoint slideshow and use the phone's volume buttons.

If the phone never appears in Windows Bluetooth settings, its Android build may
not support the HID Device profile. Pairing availability and behavior vary by
device.

## Build

The project uses Gradle and Android Gradle Plugin. With JDK 17 and Android SDK
installed, run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions also builds the debug APK on pushes to `main`, pull requests,
and manual dispatch. Download it from the workflow run's **android-remote-apk**
artifact, then install it on the phone. For a direct APK download, use the
`app-debug.apk` asset in the repository's [Releases](../../releases).

To publish a release, tag a commit and push the tag:

```sh
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions builds that commit and attaches its debug APK to a new release.
Debug APKs use a temporary signing key on GitHub runners, so a later build may
require uninstalling the previous app before installing it.

## License

MIT; see [LICENSE](LICENSE).
