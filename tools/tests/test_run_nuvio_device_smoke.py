import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from tools import run_nuvio_device_smoke as smoke

class NuvioDeviceSmokeDriverTest(unittest.TestCase):
    def run_fixture(self, *, checksum_bad=False, cleanup=True):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root/'bundle'; bundle.mkdir()
            apks = {}
            for name in ('Nuvio-Umbra-debug.apk', 'Nuvio-Umbra-tests.apk'):
                (bundle/name).write_bytes(name.encode())
                apks[name] = smoke.digest(bundle/name)
            if checksum_bad: apks['Nuvio-Umbra-debug.apk'] = '0'*64
            (bundle/'build-manifest.json').write_text(json.dumps({'kind':'umbra_nuvio_device_test_build',
                'engineVersion':'0.3.6', 'wheelSha256':'a'*64, 'applicationId':'com.nuviodebug.com',
                'testApplicationId':'com.nuviodebug.com.test', 'apks':apks}))
            receipt = {'kind':'umbra_nuvio_device_smoke','engineVersion':'0.3.6'}
            for key in ('passed','startupVerified','connectionVerified','installVerified','browseVerified',
                        'resolveVerified','nativeUiHandoffVerified','nuvioLaunchContractVerified','cleanupVerified'):
                receipt[key] = True
            receipt['cleanupVerified'] = cleanup
            calls = []
            def execute(command, **kwargs):
                calls.append(command)
                if command[-1] == 'get-state': text = 'device\n'
                elif 'instrument' in command: text = 'OK (1 test)\n'
                elif 'run-as' in command: text = json.dumps(receipt)
                elif 'getprop' in command: text = 'test-device\n'
                else: text = 'Success\n'
                return subprocess.CompletedProcess(command, 0, text)
            output = root/'evidence'
            with patch('sys.argv',['driver','--bundle',str(bundle),'--serial','selected-device','--output',str(output)]), patch.object(smoke.subprocess,'run',side_effect=execute):
                status = smoke.main()
            return status, json.loads((output/'manifest.json').read_text()), calls

    def test_changed_apk_is_rejected_before_device_actions(self):
        status, report, calls = self.run_fixture(checksum_bad=True)
        self.assertEqual(1, status)
        self.assertFalse(report['passed'])
        self.assertEqual([], calls)

    def test_instrumentation_success_without_cleanup_is_not_a_pass(self):
        status, report, _ = self.run_fixture(cleanup=False)
        self.assertEqual(1, status)
        self.assertFalse(report['passed'])

    def test_smoke_success_never_claims_playback_or_release_qualification(self):
        status, report, calls = self.run_fixture()
        self.assertEqual(0, status)
        self.assertTrue(report['passed'])
        self.assertFalse(report['playbackVerified'])
        self.assertFalse(report['releaseQualified'])
        self.assertFalse(any('uninstall' in command or 'clear' in command for command in calls))

if __name__ == '__main__': unittest.main()
