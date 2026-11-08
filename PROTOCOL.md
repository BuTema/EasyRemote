# EasyRemote protocol v1

The phone talks **directly** to the PC agent. No relay, no cloud, no account.

```
phone ──(mDNS: find PC)──► LAN ◄── PC agent advertises _easyremote._tcp
phone ══ WebSocket over TLS (wss), port 47821 by default ══► PC agent
```

## 1. Transport & trust

* The agent generates a self-signed ECDSA P-256 certificate on first run and keeps it forever.
* The phone **never trusts a CA**. It pins the SHA-256 fingerprint of the certificate (lowercase hex of the DER bytes), which it learns from the pairing QR code (or via the HMAC proof below when the code is typed by hand).
* Hostname verification is disabled on purpose (the PC is addressed by LAN IP); the pinned fingerprint is the identity.
* mDNS is used **only to find host and port**. Nothing learned from mDNS is trusted.
* Service type `_easyremote._tcp`, TXT records: `v=1`, `id=<first 16 hex chars of the cert fingerprint>`. The instance name is the PC name.

## 2. Handshake (JSON text frames)

Every message is a JSON object with a `"t"` (type) field. Protocol version is `1`.

Server → client, immediately after the WebSocket opens:

```json
{"t":"hello","v":1,"name":"MY-PC","os":"windows","nonce":"<32 hex chars>"}
```

Client → server, **first** frame, one of:

### 2a. Pair (once per phone)

```json
{"t":"pair","v":1,"device":"Pixel 8","proof":"<64 hex chars>"}
```

`proof = hex(HMAC-SHA256(key = secret, msg = "pair|" + nonce + "|" + fingerprint))`

* `secret` is the one-time pairing secret, **normalised**: upper-case, remove `-` and spaces, map `0→O`, `1→I`, `8→B`. It is 16 Base32 characters (80 bits), shown as `ABCD-EFGH-IJKL-MNOP` and embedded in the QR code.
* `nonce` is the string from `hello`.
* `fingerprint` is the fingerprint of the certificate **the phone actually saw** during the TLS handshake. If somebody sits in the middle with a different certificate, the proof will not match and pairing fails. The secret itself never goes over the wire.

The pairing window lasts 5 minutes, works once, and is closed after 5 wrong proofs.

Success reply:

```json
{"t":"paired","id":"<16 hex>","token":"<64 hex>","name":"MY-PC"}
```

The phone stores `id`, `token`, the fingerprint and the PC name. The agent stores only `SHA-256(token)`.

### 2b. Auth (every later connection)

```json
{"t":"auth","v":1,"id":"<16 hex>","token":"<64 hex>"}
```

(The token is sent only inside the pinned TLS channel.)

### Result

On success the server sends `{"t":"ready"}` and then, if it can read the audio state, `{"t":"state","vol":45,"mute":false}`.

On failure the server sends and closes:

```json
{"t":"error","code":"<code>","msg":"<human readable>"}
```

Codes: `version`, `bad_request`, `no_pairing_window`, `pairing_failed`, `bad_token`.

## 3. Input frames (binary WebSocket frames, client → server, only after `ready`)

First byte is the opcode, multi-byte integers are **big-endian**.

| Op | Name   | Payload | Meaning |
|----|--------|---------|---------|
| 0x01 | MOVE   | `i16 dx, i16 dy` | Relative pointer movement in pixels. +x right, +y down. |
| 0x02 | SCROLL | `i16 dx, i16 dy` | Wheel in hi-res units, **120 = one notch**. +dy = scroll up, +dx = scroll right. |
| 0x03 | BUTTON | `u8 button, u8 action` | button: 0 left, 1 right, 2 middle. action: 0 release, 1 press, 2 click. |
| 0x04 | KEY    | `u8 key, u8 action` | See key table. action as above. |
| 0x05 | VOLUME | `i8 steps` | One step = 5 % of master volume. Clamped to 0–100. |
| 0x06 | MUTE   | – | Toggle mute. |
| 0x07 | MEDIA  | `u8 action` | 1 play/pause, 2 next, 3 previous, 4 stop. |
| 0x08 | TEXT   | UTF-8, ≤ 256 bytes | Type these characters (`\n` = Enter). |

Key table: 1 Left, 2 Right, 3 Up, 4 Down, 5 Esc, 6 Enter, 7 Backspace, 8 Space, 9 Tab, 10 Delete, 11 Home, 12 End, 13 PageUp, 14 PageDown, 15 F11.

Unknown opcodes and malformed frames are ignored. When a connection closes for any reason the agent releases every button/key that is still held.

After a VOLUME or MUTE frame the server answers with a `state` text frame (when the platform allows reading the volume).

## 4. Keep-alive

The phone sends a WebSocket ping every 15 s while a PC screen is open; the agent drops a connection that has been silent for 45 s. The phone closes the socket when the screen leaves the foreground, so there is no background traffic and no wake-locks.
