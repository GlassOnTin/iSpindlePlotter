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

`id` is a monotonic cursor (never resets, survives restart). Pass the last
`cursor` you received as `?since=` to get only newer readings. `ts` is the
proxy's receive time in epoch-ms — the canonical reading timestamp.

## Flags

| Flag    | Default          | Meaning                                           |
|---------|------------------|---------------------------------------------------|
| `--addr`| `:9501`          | Listen address. 9501 is what the firmware targets |
| `--db`  | `readings.jsonl` | Append-only buffer file                           |
| `--max` | `10000`          | Prune to the newest N records when exceeded       |

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
