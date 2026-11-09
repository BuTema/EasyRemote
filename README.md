# EasyRemote

Control your PC from your phone: touchpad, arrow keys, volume, mute, play/pause, Esc and the phone's normal keyboard. No cloud, no account, no extra server — the phone talks straight to a tiny agent on the PC over your Wi-Fi.

```
EasyRemote/
├── agent/       PC agent, Rust (Windows + Linux). Two binaries: easyremoted (background) and easyremote (CLI)
├── android/     Phone app, Kotlin + Jetpack Compose
├── packaging/   Install scripts (Windows PowerShell, Linux bash)
├── PROTOCOL.md  Wire protocol (read this before changing either side)
└── .github/workflows/build.yml   CI: tests + builds for Linux, Windows and Android
```

## How it works

* The agent runs in the background (about 4 MB RAM, no CPU while idle) and advertises itself on the LAN with mDNS.
* The phone finds it automatically and connects over TLS (WebSocket, port 47821).
* **Pair once** with a QR code. The phone then remembers the PC; later connections need nothing from you.
* Trust is a pinned certificate fingerprint plus a per-phone token. Nothing leaves your network, and nobody else on the Wi-Fi can control the PC.
* The phone only holds a connection while the app is on screen. In the background it sends nothing, so it costs no battery.

## Install

**1. Get the builds.** Everything is built by GitHub Actions. Open the repository's **Releases** page → *Latest build*, which has `EasyRemote.apk` (phone), `easyremote.flatpak` (Linux, recommended), `easyremote_<version>_amd64.deb` (Mint / Ubuntu / Debian) and `easyremote-linux-x86_64.tar.gz` (other distros) and `easyremote-windows-x86_64.zip`.

**2. Linux (Linux Mint and any other distro): Flatpak.** Mint has Flatpak built in. Download `easyremote.flatpak` from the release, then in a terminal:

```
flatpak install --user easyremote.flatpak                       # also fetches the runtime from Flathub
flatpak run io.github.butema.EasyRemote host-setup | sudo sh    # once: lets your user create the virtual mouse/keyboard, opens the firewall if it is on
# log out and in once, then:
flatpak run io.github.butema.EasyRemote autostart on            # starts now and at every login
flatpak run io.github.butema.EasyRemote pair                    # shows the QR code (also in the menu as "EasyRemote – pair phone")
```

Tip: `alias easyremote='flatpak run io.github.butema.EasyRemote'` makes the commands below shorter. To stop the agent: `flatpak kill io.github.butema.EasyRemote`.

*Why the permissions:* the Flatpak asks for network (phone connection), `/dev/uinput` (the virtual input device), permission to write the login-autostart entry, and to run a few fixed host commands (`pactl` for volume; `xclip`/`xsel`/`wl-copy` for typing non-English text) through `flatpak-spawn --host`. A Flatpak cannot install the udev rule that gives your user access to `/dev/uinput`, which is what the one-time `host-setup` step does.

*Alternative for Mint / Ubuntu / Debian without Flatpak:* `sudo apt install ./easyremote_0.1.0_amd64.deb`, then `easyremote autostart on` and `easyremote pair` (the package does the host setup itself). The binary is built against glibc 2.35, so it runs on Mint 21.x and 22.x. Other distributions: use the `.tar.gz` and `./install.sh`.

**3. Windows.** Unzip, right-click `install.ps1` → *Run with PowerShell* (run as administrator once if you want the firewall rule added automatically; otherwise Windows asks the first time). It installs to `%LOCALAPPDATA%\EasyRemote`, starts at login and shows a pairing QR code.

**4. Phone.** Install the APK, tap **+**, **Scan QR code**. Done. (No camera? Choose the manual fields and type the address and the code shown below the QR.)

## Using it

* **Touchpad** (bottom, or right side in landscape): one finger moves, tap = left click, tap-and-hold-and-move = drag, two fingers scroll, two-finger tap = right click. Left / Right buttons below it. **Straight-line assist** (Settings, on by default) smooths out the small sideways wobble of a finger moving in a straight line; turns, curves and slow movement are left alone.
* **Control panel** (directly above the touchpad, the whole group sits at the bottom of the screen for one-handed use): on the left `Esc` · arrows (hold to repeat) · `Enter`; on the right, top row **Play/Pause**, **Fullscreen** (sends the letter **F**, which most video players use; not F11) and the **keyboard** button (opens your phone's normal keyboard in any language), bottom row **volume −**, **mute** and **volume +** (one step = 5 %). Mute turns red while the PC is muted, and either volume button unmutes. The current volume is a bar under the volume buttons, and the connection status sits under the arrow keys; changes made on the PC itself show up on the phone within a couple of seconds.
* The phone's own volume buttons also control the PC volume while connected (can be turned off in Settings).

**Updating the agent:** install the new package or Flatpak over the old one, then run `easyremote autostart on`: it stops the old agent and starts the new one (an agent that finds itself replaced on disk also restarts itself the next time a phone connects). `easyremote status` shows the build you have.

## Agent commands

```
easyremote pair                 show a pairing QR code (valid 5 min, one phone)
easyremote devices              list paired phones
easyremote revoke <id|name|all> remove a phone
easyremote status               name, address, certificate fingerprint
easyremote autostart on|off     start at login
easyremote audio-debug          (Linux) what the audio system reports, for volume / mute problems
easyremote host-setup           (Linux) print the one-time setup script: easyremote host-setup | sudo sh
easyremoted --mock              run without touching the PC (prints events; for testing)
```

State lives in `%APPDATA%\EasyRemote` or `~/.config/EasyRemote` (certificate, paired devices as token hashes, `agent.log`).

## Build it yourself

```
cd agent   && cargo test && cargo build --release
cd android && ./gradlew testDirectDebugUnitTest assembleDirectDebug    # JDK 17, Android SDK 36
```

## Status and known limits

* The agent's protocol, pairing and security logic is covered by automated tests (unit + end-to-end over real TLS/WebSocket, run on Linux).
* **Not yet run on real hardware:** Windows input injection and volume (Core Audio), Linux `/dev/uinput` injection (including on Mint), the Flatpak itself, and the whole Android app. They were written against the platform APIs and are compiled by CI, but the first real-device run may need small fixes. The shared protocol/pairing vectors are asserted on both the Rust and the Kotlin side.
* Linux typing of non-ASCII characters (e.g. Ukrainian) pastes through the clipboard (Ctrl+V), so `xclip` or `xsel` (X11; Mint ships `xsel`) or `wl-clipboard` (Wayland) must be installed, and terminal windows, which paste with Ctrl+Shift+V, will not receive such characters. Plain English letters and digits are typed as real key presses everywhere. Windows types every character directly.
* The QR scanner uses Google Play services' code scanner; without Play services use the manual entry.
* The release APK is signed with your own key when the `EASYREMOTE_KEYSTORE_B64`, `EASYREMOTE_KEYSTORE_PASSWORD`, `EASYREMOTE_KEY_ALIAS` and `EASYREMOTE_KEY_PASSWORD` repository secrets exist (see README "Signing"); otherwise with the debug key.
* Only IPv4 addresses are used. Guest Wi-Fi or "client isolation" networks block phone-to-PC traffic by design.

## Signing the APK with your own key

Android only requires that an APK is signed, and updates only install over an app signed with the *same* key. Make the key once and keep it safe (if you lose it, users must uninstall and reinstall):

```
keytool -genkeypair -v -keystore easyremote.jks -alias easyremote -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 easyremote.jks      # copy the output
```

Then in the GitHub repo: Settings → Secrets and variables → Actions → New repository secret:
`EASYREMOTE_KEYSTORE_B64` (the base64 text), `EASYREMOTE_KEYSTORE_PASSWORD`, `EASYREMOTE_KEY_ALIAS` (`easyremote`), `EASYREMOTE_KEY_PASSWORD`. The next build signs `EasyRemote.apk` with it. Never commit the `.jks` file. `EasyRemote-debug.apk` always uses the debug key.
