# Third-party notices

- Vosk Android (`com.alphacephei:vosk-android:0.3.47`) — Apache License 2.0. Used only for the optional offline wake-word experiment.
- JUnit 4.13.2 — Eclipse Public License 1.0. Test dependency only.

No third-party API secret is bundled in the source or APK by design.

## whisper.cpp (optional offline command recognition, v1.9.7)
Source: https://github.com/ggml-org/whisper.cpp (v1.8.4, MIT license).
The build downloads the official pinned release source at build time; the app's
native library is built from it. The Whisper model is a separate user-selected
file and is not bundled in the APK. Consult the upstream repository for its
license and model licensing details.
