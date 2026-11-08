# Publishing EasyRemote on Google Play

## One-time account steps
1. Create a Play Console developer account (one-time $25 fee, identity verification).
2. If it is a *personal* account created after 13 Nov 2023: before you can publish to production you must run a **closed test with at least 12 testers opted in continuously for 14 days**, then apply for production access. (Organization accounts are not described by that rule.)
3. Create the app: name "EasyRemote", default language, App (not game), Free.

## Build
* The `build` workflow produces `EasyRemote.aab`, the file Play wants (APK is for sideloading).
* It must be signed with **your upload key** (README, "Signing the APK with your own key"). Play re-signs it with its own app-signing key (Play App Signing, accept the default on first upload). A debug-signed `.aab` is rejected.
* Every upload needs a higher `versionCode`; CI uses the run number automatically. `versionName` is in `android/app/build.gradle.kts`.
* Target API is 36, required for new apps since 31 Aug 2026.

## Store listing text (edit freely)
* **Short description (80):** Control your PC from your phone: touchpad, keys, volume and media. No cloud.
* **Full description:** EasyRemote turns your phone into a touchpad, arrow keys and media remote for your Windows or Linux PC. Pair once with a QR code; after that the phone finds your PC on your Wi-Fi. Touchpad with tap, right-click, drag and two-finger scroll; arrow keys, Esc, Enter, volume in 5% steps, mute, play/pause, fullscreen and your phone's normal keyboard for typing. No account, no cloud server, no ads, no tracking: your phone talks directly to a small free agent on your PC over an encrypted local connection. Get the PC agent (Windows, Linux) from https://github.com/BuTema/EasyRemote/releases
* **Category:** Tools. **Privacy policy URL:** https://github.com/BuTema/EasyRemote/blob/main/PRIVACY.md

## Assets you must prepare
* App icon 512×512 PNG, feature graphic 1024×500, at least 2 phone screenshots (take them from the real app).

## Forms in Play Console
* **Data safety:** no data collected, no data shared; data in transit is encrypted.
* **Content rating** questionnaire: no violence, ads, user-generated content or purchases.
* **Target audience:** 18+ / not directed at children. **Ads:** none. **App access:** all functionality needs a PC agent; give reviewers a short note and, ideally, a test video, because they cannot pair without a computer.
* Not a financial, health, news or government app.
