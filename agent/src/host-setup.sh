#!/bin/sh
# EasyRemote: one-time setup of the computer (needs root).
# Lets your desktop user create a virtual mouse/keyboard through /dev/uinput, and opens the firewall.
# Run:   easyremote host-setup | sudo sh
set -e

echo 'KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"' \
    > /etc/udev/rules.d/60-easyremote-uinput.rules
echo uinput > /etc/modules-load.d/easyremote-uinput.conf
modprobe uinput || true
udevadm control --reload-rules
udevadm trigger --subsystem-match=misc --sysname-match=uinput || true

# Linux Mint ships ufw switched off; if it (or firewalld) is on, let the phone in.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow 47821/tcp
    ufw allow 5353/udp
fi
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=47821/tcp
    firewall-cmd --permanent --add-service=mdns || true
    firewall-cmd --reload
fi

echo "EasyRemote: computer setup done. If input still does not work, log out and in once."
