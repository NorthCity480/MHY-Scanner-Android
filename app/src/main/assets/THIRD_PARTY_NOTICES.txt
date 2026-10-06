# Attribution & licensing

This is an unofficial Android rewrite, changed on 2026-10-05, of behavior and official-server request payloads described by:

- DSVVA/MHY_Scanner: https://github.com/DSVVA/MHY_Scanner
- Current community-login protocol reference: loqwe/MHY_Scanner2 commit `d07fc8018414e2b54962264517d8344601d825f2`, https://github.com/loqwe/MHY_Scanner2 (GPL-3.0)
- Original project attributed in its README: Theresa-0328/MHY_Scanner, https://github.com/Theresa-0328/MHY_Scanner
- Adapted references: `src/Core/ApiDefs.hpp`, `src/Core/MhyApi.hpp`, `src/Core/ScannerBase.hpp`, `src/Core/LiveStreamLink.cpp`.

The upstream GPL-3.0 license is reproduced verbatim in `LICENSE`. The Android rewrite is distributed under GPL-3.0. No warranty. This is not endorsed by miHoYo/HoYoverse or the live platforms. Game names and marks belong to their respective owners. No game artwork, OpenCV models, upstream embedded live cookies, Bilibili game channel credentials, or third-party dispatch services are included.

Included dependencies, resolved by Gradle:

- ZXing core 3.5.3 — Apache License 2.0; https://github.com/zxing/zxing
- ZXing-C++ Android 2.3.0 — Apache License 2.0; https://github.com/zxing-cpp/zxing-cpp
- AndroidX Media3 ExoPlayer / HLS 1.5.1 and AndroidX dependencies — Apache License 2.0; https://github.com/androidx/media
- Guava Android and failureaccess — Apache License 2.0; https://github.com/google/guava
- Transitive annotation libraries retain their original notices/license files in dependencies/APK metadata.
- JUnit 4.13.2 (test only) — EPL 1.0; https://github.com/junit-team/junit4
- JSON-java 20240303 (test only) — public domain; https://github.com/stleary/JSON-java

When redistributing the APK, provide the corresponding source, build files, GPL-3.0 license, and this notice. Debug signing is for local testing; release distributions need a securely controlled signing key and the same source-access requirements.
