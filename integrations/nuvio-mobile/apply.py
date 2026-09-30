"""Apply the Umbra Android adapter to the pinned NuvioMobilePlus checkout."""
import argparse
from pathlib import Path
import shutil
import subprocess

PIN = 'a8b0df7784696a09b426bca7218fde5f905c76e0'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=Path)
    parser.add_argument('--sdk-licenses', type=Path, required=True, help='licenses directory from the extracted Runtime SDK ZIP')
    args = parser.parse_args()
    target = args.checkout.resolve()
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=target, text=True).strip() != PIN:
        parser.error('Nuvio source revision differs from the reviewed adapter target')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=target, text=True).strip():
        parser.error('Apply to a clean checkout; commit or move existing changes first')
    here = Path(__file__).resolve().parent
    sdk_licenses = args.sdk_licenses.resolve()
    required_licenses = ['LICENSE', 'LICENSE-NOTICE', 'THIRD_PARTY_NOTICES.md', 'Umbra-Ghost-Kodi-LICENSE.txt']
    if not all((sdk_licenses/name).is_file() for name in required_licenses):
        parser.error('The SDK license directory is incomplete')
    common = target / 'composeApp/src/commonMain/kotlin'
    android = target / 'composeApp/src/androidMain/kotlin'
    ios = target / 'composeApp/src/iosMain/kotlin'
    destinations = [(here/'UmbraEngineEntry.common.kt', common/'com/nuvio/app/umbra/UmbraEngineEntry.kt'),
                    (here/'UmbraEngineEntry.android.kt', android/'com/nuvio/app/umbra/UmbraEngineEntry.android.kt'),
                    (here/'UmbraEngineEntry.ios.kt', ios/'com/nuvio/app/umbra/UmbraEngineEntry.ios.kt'),
                    (here/'UmbraHostActivity.kt', target/'androidApp/src/main/kotlin/com/nuvio/app/umbra/UmbraHostActivity.kt'),
                    (here/'NuvioPlaybackPolicy.kt', target/'androidApp/src/main/kotlin/com/nuvio/app/umbra/NuvioPlaybackPolicy.kt'),
                    (here/'UmbraEngineDeviceTest.kt', target/'androidApp/src/androidTest/kotlin/com/nuvio/android/UmbraEngineDeviceTest.kt')]
    edits = {}
    def replace(relative, before, after):
        p = target/relative
        text = edits.get(p, p.read_text())
        if text.count(before) != 1:
            raise ValueError(f'Expected exactly one integration anchor in {relative}')
        edits[p] = text.replace(before, after)
    replace('settings.gradle.kts', 'dependencyResolutionManagement {', '''dependencyResolutionManagement {
    repositories {
        maven {
            url = uri(providers.gradleProperty("umbraSdkRepository").get())
            content { includeGroup("com.umbra.runtime") }
        }
    }''')
    replace('androidApp/build.gradle.kts', 'dependencies {', '''dependencies {
    implementation("com.umbra.runtime:runtime-client-android:0.1.0-SNAPSHOT")
    implementation("com.umbra.runtime:runtime-service-android:0.1.0-SNAPSHOT")
    implementation("com.umbra.runtime:python-host-android:0.1.0-SNAPSHOT")''')
    manifest = target/'androidApp/src/main/AndroidManifest.xml'
    if manifest.read_text().count('</application>') != 1:
        raise ValueError('Expected an application manifest')
    edits[manifest] = manifest.read_text().replace('</application>', '<activity android:name="com.nuvio.app.umbra.UmbraHostActivity" android:exported="false" />\n    </application>')
    relative = 'composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsFullScreenPages.kt'
    replace(relative, 'fun AddonsSettingsScreen(\n    onBack: () -> Unit,', 'fun AddonsSettingsScreen(\n    onBack: () -> Unit,\n    onUmbraPlayback: (com.nuvio.app.features.player.PlayerLaunch) -> Unit = {},')
    replace(relative, '        addonsSettingsContent()', '        item { com.nuvio.app.umbra.UmbraEngineEntry(onUmbraPlayback) }\n        addonsSettingsContent()')
    replace('composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt', '                        AddonsSettingsScreen(onBack = onBack)', '''                        AddonsSettingsScreen(onBack = onBack, onUmbraPlayback = { launch ->
                            val current = launch.copy(profileId = activePlaybackProfileId)
                            val id = PlayerLaunchStore.put(current)
                            navController.navigate(PlayerRoute(launchId = id, title = current.title))
                        })''')
    # Validate all anchors before writing any file.
    for source, destination in destinations:
        if destination.exists():
            raise ValueError(f'Integration destination already exists: {destination}')
    for p, text in edits.items():
        p.write_text(text)
    for source, destination in destinations:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    licenses = target/'androidApp/src/main/assets/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for name in required_licenses:
        shutil.copyfile(sdk_licenses/name, licenses/('Umbra-'+name))
    print('Umbra adapter applied; use the exported SDK repository for the Android build.')

if __name__ == '__main__': main()
