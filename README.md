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
artifact, then install it on the phone.

## License

MIT; see [LICENSE](LICENSE).
