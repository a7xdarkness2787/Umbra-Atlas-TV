# Umbra Atlas

Umbra Atlas is the Nuvio-derived media-player application maintained separately from the reusable Umbra engine platform. It is TV-first; phone and tablet support need their own verified consumer integration.

| Repository | Responsibility |
| --- | --- |
| [Umbra-Ghost-Kodi](https://github.com/a7xdarkness2787/Umbra-Ghost-Kodi) | Python engine and add-on compatibility |
| [Umbra-Runtime](https://github.com/a7xdarkness2787/Umbra-Runtime) | Application-neutral Android SDK and qualified engine packaging |
| Umbra-Atlas-TV | Application UI, navigation, catalogue, profiles, playback and Runtime adapter |

Atlas consumes exported Maven binaries. Runtime and Ghost do not compile Atlas source or depend on Atlas tests. Other compatible applications can use the same Runtime API with their own UI and player.

## Current status

This repository is still a bootstrap: NuvioTV application source has not been imported. The previous import workflow references the old repository name and depends on GitHub Actions. It is not a working import path for this repository. Local Git development is the intended path while Actions billing is unavailable.

The [NuvioMobilePlus Android adapter](integrations/nuvio-mobile/README.md) is preserved here as a build reference. It targets a pinned mobile upstream commit, not NuvioTV. Its app and instrumentation APKs compiled previously; physical-device smoke execution and actual playback remain unverified. Atlas owns its adapter, playback policy and device driver.

Run driver unit checks independently of Runtime source:

```sh
python3 -m unittest discover -s tools/tests -v
```

Next, import a pinned NuvioTV revision retaining source history and license notices, then implement the TV adapter against SDK binaries. Do not apply the mobile patch script to NuvioTV.

## License

Existing project notices remain authoritative. The SDK supplies its own notices; the adapter requires the extracted SDK license directory and copies it into the consumer. Preserve the selected upstream application's license and attribution during import.
