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

**1. Get the builds.** Everything is built by GitHub Actions. Open the repository's **Releases** page → *Latest build*, which has `EasyRemote.apk` (phone), `easyremote_<version>_amd64.deb` (Linux Mint / Ubuntu / Debian), `easyremote-linux-x86_64.tar.gz` (other distros) and `easyremote-windows-x86_64.zip`.

**2. Linux Mint (and Ubuntu / Debian).** Download the `.deb`, then either double-click it (Software Manager) or:

```
sudo apt install ./easyremote_0.1.0_amd64.deb
easyremote autostart on      # run as your normal user; starts now and at every login
easyremote pair              # shows the QR code
```

The package loads the `uinput` module and adds a udev rule so your desktop user can create the virtual mouse/keyboard (it works on Cinnamon/X11 and on Wayland). If Mint's firewall (ufw) is switched on, the package opens TCP 47821 and mDNS for you. The binary is built against glibc 2.35, so it runs on Mint 21.x and 22.x. For other distributions use the `.tar.gz` and run `./install.sh`.

**3. Windows.** Unzip, right-click `install.ps1` → *Run with PowerShell* (run as administrator once if you want the firewall rule added automatically; otherwise Windows asks the first time). It installs to `%LOCALAPPDATA%\EasyRemote`, starts at login and shows a pairing QR code.

**4. Phone.** Install the APK, tap **+**, **Scan QR code**. Done. (No camera? Choose the manual fields and type the address and the code shown below the QR.)

## Using it

* **Touchpad** (bottom, or right side in landscape): one finger moves, tap = left click, tap-and-hold-and-move = drag, two fingers scroll, two-finger tap = right click. Left / Right buttons below it.
* **Top (left in landscape):** arrow keys (hold to repeat), Enter in the middle, volume − / + (one step = 5 %, shows the current %), mute, **Esc**, play/pause, fullscreen (F11) and the **keyboard** button, which opens your phone's normal keyboard in any language.
* The phone's own volume buttons also control the PC volume while connected (can be turned off in Settings).

## Agent commands

```
easyremote pair                 show a pairing QR code (valid 5 min, one phone)
easyremote devices              list paired phones
easyremote revoke <id|name|all> remove a phone
easyremote status               name, address, certificate fingerprint
easyremote autostart on|off     start at login
easyremoted --mock              run without touching the PC (prints events; for testing)
```

State lives in `%APPDATA%\EasyRemote` or `~/.config/EasyRemote` (certificate, paired devices as token hashes, `agent.log`).

## Build it yourself

```
cd agent   && cargo test && cargo build --release
cd android && ./gradlew testDebugUnitTest assembleDebug    # JDK 17, Android SDK 35
```

## Status and known limits

* The agent's protocol, pairing and security logic is covered by automated tests (unit + end-to-end over real TLS/WebSocket, run on Linux).
* **Not yet run on real hardware:** Windows input injection and volume (Core Audio), Linux `/dev/uinput` injection (including on Mint), and the whole Android app. They were written against the platform APIs and are compiled by CI, but the first real-device run may need small fixes. The shared protocol/pairing vectors are asserted on both the Rust and the Kotlin side.
* Linux typing of non-ASCII characters (e.g. Ukrainian) pastes through the clipboard (Ctrl+V), so `xclip` or `xsel` (X11; Mint ships `xsel`) or `wl-clipboard` (Wayland) must be installed, and terminal windows, which paste with Ctrl+Shift+V, will not receive such characters. Plain English letters and digits are typed as real key presses everywhere. Windows types every character directly.
* The QR scanner uses Google Play services' code scanner; without Play services use the manual entry.
* The release APK is signed with the debug key so it installs directly; use your own keystore before distributing it.
* Only IPv4 addresses are used. Guest Wi-Fi or "client isolation" networks block phone-to-PC traffic by design.
