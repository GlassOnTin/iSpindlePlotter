# ispindle-proxy

An always-on buffer between an iSpindel and the iSpindlePlotter phone app.

The iSpindel wakes every ~15 min, POSTs one reading, and sleeps. If the phone
is off/asleep/off-network at that moment the reading is lost — the device has
no retry. This proxy catches every POST into an append-only file; the phone
polls for everything it missed since its last cursor.

```
iSpindel ──POST──▶ ispindle-proxy ──append──▶ readings.jsonl
                       ▲                           │
                       └──GET /readings?since=N─────┘ ◀── phone app
```

LAN-only by design — no auth, no TLS. To reach it while away from home, use the
router's WireGuard VPN rather than forwarding a port.

## HTTP API

| Method | Path                  | Purpose                                            |
|--------|-----------------------|----------------------------------------------------|
| POST   | `/` (any path)        | iSpindel posts its JSON reading here → `200`/`400` |
| GET    | `/readings?since=N`   | `{"cursor":M,"readings":[{id,ts,ip,payload},…]}`   |
| GET    | `/health`             | `ok`                                               |
| GET    | `/cfg`                | Config-push delta currently active → `{}` or cfg   |
| PUT    | `/cfg`                | Replace the config-push delta (`{"POLY":…,"sleep":…,"SSID":…,"fw":…}`) |
| DELETE | `/cfg`                | Clear it (no more cfg on responses)                |
| GET    | `/fw`                 | `{size,md5}` of the OTA firmware binary            |
| GET    | `/fw.bin`             | The OTA binary itself (what the device downloads)  |

`id` is a monotonic cursor (never resets, survives restart). Pass the last
`cursor` you received as `?since=` to get only newer readings. `ts` is the
proxy's receive time in epoch-ms — the canonical reading timestamp.

## Config push

With `--cfg-file <path>` set, a valid cfg file is attached to every device
POST response:

```json
{"status":"ok","cfg":{"POLY":"0.95683653 + 0.00156709*tilt + 0.00000376*tilt^2","sleep":60}}
```

The patched firmware (see `../firmware/firmware-config-push.patch`) parses this
in `processResponse` on the data it already reads from each POST — no extra
radio time — and commits changed values to `/config.json` on LittleFS. Keys:

- `POLY` — gravity polynominal in TinyExpr form (`tilt`, `temp` variables, ≤ 250
  chars). The device re-validates it with its own evaluator before saving.
  Sticky: attached until removed from the file.
- `sleep` — upload interval in seconds; device bounds 10 < sleep < 86400.
  Sticky.
- `SSID`/`PSK` — one-shot pair, tried as *pending* credentials (tried on the
  next wakes before the saved SSID; promoted on the first successful POST and
  dropped after 3 failed ones). The device acks each POST with a `cf` field
  (`trial`/`reverted`/`refused`); any ack retires the push and it is also
  removed from the file. After 5 responses with no ack it is retired too.
- `fw` — one-shot OTA push (see below). Acked via a `fa` field
  (`err` = attempted and failed, `skip` = declined); 5-response budget if the
  device never acks (a successful OTA reboots without acking, so the budget
  retires the push in the happy path).

The file is the source of truth: edit it on disk (the proxy re-reads on
mtime/size change) or use the `/cfg` endpoints. Invalid JSON or a failed
validation → nothing is attached (logged), so a broken file can't push junk.
DELETE removes the file and the device returns to its saved config.

## Remote OTA

With `--fw-file <path>` (a firmware `.bin`) the proxy serves it at `/fw.bin`;
`GET /fw` reports its `{size,md5}`. Pushing an update:

```sh
md5=$(curl -s http://<proxy>:9501/fw | sed -E 's/.*"md5":"([0-9a-f]+)".*/\1/')
curl -X PUT http://<proxy>:9501/cfg -d \
  '{"fw":{"url":"http://<proxy>:9501/fw.bin","md5":"'$md5'","ver":"7.3.4"}}'
```

The device downloads in the wake that receives the push, checks the md5 and
version, and reboots into the new image on success — silently, no ack. A
failed attempt (or a deliberately skipped one: weak link at RSSI < −75 dBm, a
ver matching the running firmware, or a malformed push) acks via `fa` on the
next POST and the proxy retires the push. The firmware-side update code uses
the same eboot/md5 checks as the config portal's upload page, and only
operator-initiated pushes are ever armed; the running bin is never overwritten
without one. Requires the same patched firmware build as the config push —
the first OTA-capable build still has to go on via the config portal's
Maintenance `/update` page.

## Flags

| Flag         | Default          | Meaning                                           |
|--------------|------------------|---------------------------------------------------|
| `--addr`     | `:9501`          | Listen address. 9501 is what the firmware targets |
| `--db`       | `readings.jsonl` | Append-only buffer file                           |
| `--max`      | `10000`          | Prune to the newest N records when exceeded       |
| `--cfg-file` | *(off)*          | Config-push delta file; attached to POST responses |
| `--fw-file`  | *(off)*          | OTA firmware binary; served at `/fw.bin`, inventoried at `/fw` |

## Build & install

### Linux box / Raspberry Pi (systemd)

```sh
go build -o ispindle-proxy .
sudo install -m755 ispindle-proxy /usr/local/bin/
sudo cp ispindle-proxy.service /etc/systemd/system/
sudo systemctl enable --now ispindle-proxy
```

### GL.iNet AX6000 / OpenWrt (arm64, procd)

```sh
GOOS=linux GOARCH=arm64 go build -o ispindle-proxy .
scp ispindle-proxy root@192.168.0.2:/root/
scp ispindle-proxy.init root@192.168.0.2:/etc/init.d/ispindle-proxy
ssh root@192.168.0.2 'chmod +x /etc/init.d/ispindle-proxy && /etc/init.d/ispindle-proxy enable && /etc/init.d/ispindle-proxy start'
```

Then point the iSpindel's Generic HTTP server at the proxy's IP:9501 (the app's
Configure flow does this for you when a Proxy URL is set), and set the Proxy URL
in the app to `http://<proxy-ip>:9501`.
