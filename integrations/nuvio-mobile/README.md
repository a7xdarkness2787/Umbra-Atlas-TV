# NuvioMobilePlus Android adapter

This is a mobile integration reference, not an implemented TV adapter. This source adapter targets `prneut/NuvioMobilePlus` revision `a8b0df7784696a09b426bca7218fde5f905c76e0` on `optimal-mobile-testing`. It adds an **Umbra engine** entry to add-on settings. The native host manages inspection, owner approval, installation, repositories, folders, and host dialogs through the Runtime SDK. Resolved media returns to Nuvio's existing `PlayerLaunchStore` and `PlayerRoute`; Nuvio owns actual playback.

Ghost remains independently maintained in Umbra-Ghost-Kodi. Runtime is exported as Maven libraries, including its embedded Python runtime and checksum-locked wheel. Nuvio uses those libraries without adopting Runtime's Gradle or Android plugin versions.

## Build and apply

In the Runtime checkout, use JDK 17 and a Python 3.11 build executable:

```sh
python3 -m tools.export_runtime_sdk --output ../runtime-sdk-output --build-python python3.11
```

The command publishes the SDK into `build/sdk-maven`, assembles a binary-only Android consumer probe, checks Python packaging, and writes a ZIP and hash manifest. It does not publish to a remote Maven server.

Use a clean Nuvio checkout at the pinned revision:

```sh
git clone https://github.com/prneut/NuvioMobilePlus.git NuvioMobilePlus
cd NuvioMobilePlus
git checkout a8b0df7784696a09b426bca7218fde5f905c76e0
cd ../Umbra-Atlas-TV
python3 integrations/nuvio-mobile/apply.py ../NuvioMobilePlus --sdk-licenses /absolute/path/extracted-sdk/licenses
cd ../NuvioMobilePlus
./gradlew :androidApp:assembleFullDebug -PumbraSdkRepository=/absolute/path/extracted-sdk/maven
```

Nuvio's pinned build uses JDK 21, Gradle 9.4.1, Android SDK 37.0, and its existing native libraries. Keep its own requirements and source licenses. The source adapter is Android-only; its iOS entry intentionally renders nothing. The apply tool refuses another revision or a dirty checkout and checks all edit anchors before writing.

## Supported playback subset

This adapter advertises HTTPS with no DRM. It passes media URI, title, headers, user agent, subtitles, and start position to the existing Nuvio player. It rejects DRM, separate audio, custom data sources, distinct manifest/segment headers, Range headers, custom cache keys, cross-protocol redirects, paused launches, and subtitle selection/role flags instead of silently losing requirements. Keep the policy aligned with the selected consumer player.

The initial adapter opens a native management screen inside the Nuvio application. It does not add Ghost results to Nuvio's TMDB catalogue, search, or next-episode system. Runtime's engine workspace is currently shared across Nuvio profiles; setting the playback profile does not isolate add-on credentials or installations. There is no runtime wheel download or replacement inside the installed app. Engine upgrades require a qualified SDK export and consumer rebuild.

## Verification

Runtime independently verifies a generic consumer against published AAR/JAR binaries. The adapter policy tests belong to this consumer repository; Runtime no longer compiles them. These checks do not prove actual Nuvio playback or physical-device qualification. Verify the full Nuvio APK, source approval, repository browsing, dialog handling, playback, app backgrounding, and reconnects on the target device before release or engine promotion.

## Verified local build

The full Android debug app at the pinned revision assembled successfully with this adapter using JDK 21 and Gradle 9.4.1. APK inspection confirmed the private native activity, embedded Python 3.11 libraries, Ghost 0.3.6 package metadata, and source license assets. The APK uses Nuvio's existing debug application ID `com.nuviodebug.com`; installing it may update another Nuvio debug build with the same signing key or conflict with a different signing key. It has not been started or played on a physical device in this environment.

The packaged qualified engine does not advertise repository refresh or repository package installation. Those controls report their unavailable status. Installing an authorized video add-on ZIP and resolving its supported HTTPS media is the current integration test path. Repository support still needs exact-artifact Android qualification before enabling it.

## Physical-device smoke test

The source adapter also installs `UmbraEngineDeviceTest` in the pinned Nuvio Android test source set. Assemble both the application and instrumentation packages:

```sh
./gradlew :androidApp:assembleFullDebug :androidApp:assembleFullDebugAndroidTest -PumbraSdkRepository=/absolute/path/extracted-sdk/maven
```

Use the generated device-test bundle with the Atlas driver. Run the driver from the Atlas checkout. Connect the intended device to ADB, check its serial with `adb devices`, then run:

```sh
python3 tools/run_nuvio_device_smoke.py --bundle /absolute/path/extracted-bundle --serial YOUR_DEVICE_SERIAL --output ../nuvio-device-evidence
```

The driver verifies APK hashes, installs the debug app and instrumentation package, runs only the Umbra smoke test, and checks a private app receipt. It never uninstalls the application or clears user data to solve a signing conflict. The fixture has a unique add-on ID and is removed afterward; its package is generated locally by the test and explicitly approved for that test. No third-party provider is installed or executed. Main application startup still runs Nuvio's normal startup behavior.

The smoke test checks startup, Runtime connection, inspect/approve/install, browse, resolve, native management-screen handoff, Nuvio `PlayerLaunchStore` compatibility, and cleanup. It uses an `example.invalid` media address and never starts media playback. A passing receipt is **not** release qualification or permission to enable additional engine capabilities. A separate real playback and lifecycle check remains necessary. If cleanup fails, the test fails and reports that condition; the unique fixture may need removal from the engine's add-on workspace.
