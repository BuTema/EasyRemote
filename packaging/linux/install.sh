#!/usr/bin/env bash
# Installs EasyRemote for the current user. Needs sudo once, for /dev/uinput access and the firewall.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
bin="$HOME/.local/bin"
mkdir -p "$bin"
install -m 0755 "$here/easyremote" "$here/easyremoted" "$bin/"
echo "Installed to $bin (make sure it is on your PATH)."

echo "--- /dev/uinput access (lets the agent create a virtual mouse + keyboard)"
sudo tee /etc/modules-load.d/easyremote-uinput.conf >/dev/null <<<"uinput"
sudo tee /etc/udev/rules.d/60-easyremote-uinput.rules >/dev/null <<<'KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"'
sudo modprobe uinput || true
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=misc --sysname-match=uinput || true

echo "--- firewall (TCP 47821 for the phone; mDNS so the phone can find this PC)"
if command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --state >/dev/null 2>&1; then
    sudo firewall-cmd --permanent --add-port=47821/tcp
    sudo firewall-cmd --permanent --add-service=mdns || true
    sudo firewall-cmd --reload
elif command -v ufw >/dev/null 2>&1 && sudo ufw status | grep -q "Status: active"; then
    sudo ufw allow 47821/tcp
    sudo ufw allow 5353/udp
else
    echo "No active firewalld/ufw detected, skipping."
fi

echo "--- clipboard helper (used to type non-English letters)"
if ! command -v wl-copy >/dev/null 2>&1 && ! command -v xclip >/dev/null 2>&1; then
    echo "Tip: install wl-clipboard (Wayland) or xclip (X11) so typing Cyrillic and other non-ASCII text works."
fi

echo
echo "Done. If this is the first time uinput access was configured, log out and in once (or reboot)."
echo "Then run:   $bin/easyremote autostart on   and   $bin/easyremote pair"
