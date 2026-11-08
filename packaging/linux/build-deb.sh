#!/usr/bin/env bash
# Usage: build-deb.sh <version> <dir-with-easyremote-and-easyremoted> <output-dir>
set -euo pipefail
version="${1:?version}"; bins="${2:?binary dir}"; out="${3:?output dir}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
pkg="$(mktemp -d)/easyremote_${version}_amd64"

install -Dm0755 "$bins/easyremote"  "$pkg/usr/bin/easyremote"
install -Dm0755 "$bins/easyremoted" "$pkg/usr/bin/easyremoted"
install -Dm0644 "$here/deb/60-easyremote-uinput.rules" "$pkg/lib/udev/rules.d/60-easyremote-uinput.rules"
install -d "$pkg/usr/lib/modules-load.d"
echo uinput > "$pkg/usr/lib/modules-load.d/easyremote-uinput.conf"
install -Dm0644 "$root/README.md"   "$pkg/usr/share/doc/easyremote/README.md"
install -Dm0644 "$root/PROTOCOL.md" "$pkg/usr/share/doc/easyremote/PROTOCOL.md"
install -Dm0644 "$root/LICENSE"     "$pkg/usr/share/doc/easyremote/copyright"

install -d "$pkg/DEBIAN"
install -m0755 "$here/deb/postinst" "$pkg/DEBIAN/postinst"
install -m0755 "$here/deb/postrm"   "$pkg/DEBIAN/postrm"
size_kb=$(du -sk "$pkg" | cut -f1)
cat > "$pkg/DEBIAN/control" <<CONTROL
Package: easyremote
Version: ${version}
Section: utils
Priority: optional
Architecture: amd64
Installed-Size: ${size_kb}
Depends: libc6 (>= 2.35)
Recommends: xclip | xsel | wl-clipboard
Maintainer: BuTema <BuTema@users.noreply.github.com>
Homepage: https://github.com/BuTema/EasyRemote
Description: Control this PC from your phone over the local network
 Lightweight background agent for the EasyRemote Android app: touchpad,
 keyboard, volume and media control with no cloud and no account.
 Pair once with a QR code (easyremote pair); the phone then finds this
 computer automatically on the local network.
CONTROL

mkdir -p "$out"
dpkg-deb --root-owner-group --build "$pkg" "$out/easyremote_${version}_amd64.deb" >/dev/null
echo "built $out/easyremote_${version}_amd64.deb"
