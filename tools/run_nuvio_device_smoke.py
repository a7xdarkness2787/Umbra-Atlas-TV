"""Run the pinned Nuvio/Umbra instrumentation smoke test on one authorized device."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True, help='Extracted device-test bundle')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    output = args.output.resolve()
    if output.exists():
        parser.error('Choose a fresh evidence directory')
    output.mkdir(parents=True)
    report = {'schemaVersion': 1, 'kind': 'umbra_nuvio_device_smoke_run', 'passed': False,
              'playbackVerified': False, 'releaseQualified': False, 'serial': args.serial}
    adb = [args.adb, '-s', args.serial]
    def run(label: str, commands: list[str], timeout: int = 180) -> str:
        result = subprocess.run(adb+commands, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        (output/(label+'.log')).write_text(result.stdout)
        if result.returncode:
            raise ValueError(label+' failed; inspect its log')
        return result.stdout
    try:
        build = json.loads((bundle/'build-manifest.json').read_text())
        if build.get('kind') != 'umbra_nuvio_device_test_build' or build.get('engineVersion') != '0.3.6':
            raise ValueError('Unsupported or missing device-test build identity')
        if set(build.get('apks', {})) != {'Nuvio-Umbra-debug.apk', 'Nuvio-Umbra-tests.apk'}:
            raise ValueError('Build manifest must identify both APKs')
        for name, expected in build['apks'].items():
            if Path(name).name != name or digest(bundle/name) != expected:
                raise ValueError('APK checksum differs from the build manifest')
        package = build['applicationId']
        test_package = build['testApplicationId']
        # Identifiers are passed as adb arguments, never shell-expanded command text.
        allowed = set('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._')
        if not package or not test_package or any(c not in allowed for c in package+test_package):
            raise ValueError('Invalid package identity')
        if run('device-state', ['get-state']).strip() != 'device':
            raise ValueError('Selected device is not authorized and connected')
        report.update(applicationId=package, testApplicationId=test_package, apks=build['apks'],
                      engineVersion=build['engineVersion'], wheelSha256=build['wheelSha256'])
        report['device'] = {name: run(name, ['shell', 'getprop', prop]).strip() for name, prop in
                            [('model', 'ro.product.model'), ('android-release', 'ro.build.version.release'), ('abi', 'ro.product.cpu.abi')]}
        run('install-app', ['install', '-r', str(bundle/'Nuvio-Umbra-debug.apk')])
        run('install-tests', ['install', '-r', '-t', str(bundle/'Nuvio-Umbra-tests.apk')])
        result = run('instrumentation', ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     'com.nuvio.android.UmbraEngineDeviceTest', '-e', 'timeout_msec', '240000',
                     test_package+'/androidx.test.runner.AndroidJUnitRunner'], timeout=300)
        raw = run('device-receipt', ['shell', 'run-as', package, 'cat', 'files/umbra-device-smoke.json'])
        receipt = json.loads(raw)
        required = ('passed', 'startupVerified', 'connectionVerified', 'installVerified', 'browseVerified',
                    'resolveVerified', 'nativeUiHandoffVerified', 'nuvioLaunchContractVerified', 'cleanupVerified')
        if 'OK (1 test)' not in result or any(receipt.get(key) is not True for key in required):
            raise ValueError('Instrumentation or its device receipt did not pass all smoke checks')
        if receipt.get('kind') != 'umbra_nuvio_device_smoke' or receipt.get('engineVersion') != build['engineVersion']:
            raise ValueError('Device receipt identity differs from the tested build')
        report['deviceReceipt'] = receipt
        report['passed'] = True
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        report['failure'] = str(error) if isinstance(error, ValueError) else type(error).__name__
    (output/'manifest.json').write_text(json.dumps(report, indent=2, sort_keys=True)+'\n')
    print('Device smoke '+('passed' if report['passed'] else 'failed'))
    return 0 if report['passed'] else 1


if __name__ == '__main__': sys.exit(main())
