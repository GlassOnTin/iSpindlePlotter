// ispindle-proxy buffers iSpindel "Generic HTTP" readings so a phone that is
// off, asleep, or off-network doesn't lose them. The device POSTs each reading
// here (same JSON it would POST to the phone); the phone polls
// GET /readings?since=<cursor> and replays everything it missed.
//
// Storage is an append-only JSONL file — one record per line:
//
//	{"id":<seq>,"ts":<recvMillis>,"ip":"<peer>","payload":{...device json...}}
//
// `id` is a monotonic cursor that never resets, so the phone can ask for
// "everything after id N". Pure stdlib, no cgo: one static binary runs on a
// Linux box, a Pi, or an OpenWrt router.
package main

import (
	"bufio"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/grandcat/zeroconf"
)

type record struct {
	ID      int64           `json:"id"`
	Ts      int64           `json:"ts"`
	IP      string          `json:"ip"`
	Payload json.RawMessage `json:"payload"`
}

type buffer struct {
	mu    sync.Mutex
	path  string
	max   int
	seq   int64
	count int
}

// open scans the existing file once to recover the last id and line count so
// the cursor stays monotonic across restarts.
func openBuffer(path string, max int) (*buffer, error) {
	b := &buffer{path: path, max: max}
	f, err := os.Open(path)
	if os.IsNotExist(err) {
		return b, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		var r record
		if json.Unmarshal(sc.Bytes(), &r) != nil {
			continue
		}
		if r.ID > b.seq {
			b.seq = r.ID
		}
		b.count++
	}
	return b, sc.Err()
}

func (b *buffer) append(payload json.RawMessage, ip string) (record, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.seq++
	rec := record{ID: b.seq, Ts: time.Now().UnixMilli(), IP: ip, Payload: payload}
	line, err := json.Marshal(rec)
	if err != nil {
		return record{}, err
	}
	f, err := os.OpenFile(b.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return record{}, err
	}
	defer f.Close()
	if _, err := f.Write(append(line, '\n')); err != nil {
		return record{}, err
	}
	if err := f.Sync(); err != nil { // durable across power loss / reboot
		return record{}, err
	}
	b.count++
	if b.count > b.max {
		b.pruneLocked()
	}
	return rec, nil
}

// pruneLocked rewrites the file keeping only the newest max records. ids are
// left untouched, so a phone cursor below the oldest retained id simply
// replays the retained buffer. Caller holds b.mu.
func (b *buffer) pruneLocked() {
	lines, err := b.tail(b.max)
	if err != nil {
		log.Printf("prune: read failed, keeping file as-is: %v", err)
		return
	}
	dropped := b.count - len(lines)
	tmp := b.path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		log.Printf("prune: create temp failed: %v", err)
		return
	}
	for _, l := range lines {
		f.Write(l)
		f.Write([]byte{'\n'})
	}
	f.Sync()
	f.Close()
	if err := os.Rename(tmp, b.path); err != nil {
		log.Printf("prune: rename failed: %v", err)
		return
	}
	b.count = len(lines)
	log.Printf("pruned %d old record(s), keeping newest %d", dropped, b.count)
}

// tail returns the last n raw lines of the buffer file.
func (b *buffer) tail(n int) ([][]byte, error) {
	f, err := os.Open(b.path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	ring := make([][]byte, 0, n)
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		cp := append([]byte(nil), sc.Bytes()...)
		if len(ring) == n {
			ring = ring[1:]
		}
		ring = append(ring, cp)
	}
	return ring, sc.Err()
}

// since returns every record with id > n plus the highest id seen (or n when
// nothing is newer).
func (b *buffer) since(n int64) ([]record, int64, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	f, err := os.Open(b.path)
	if os.IsNotExist(err) {
		return nil, n, nil
	}
	if err != nil {
		return nil, n, err
	}
	defer f.Close()
	out := []record{}
	cursor := n
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		var r record
		if json.Unmarshal(sc.Bytes(), &r) != nil {
			continue
		}
		if r.ID > cursor {
			cursor = r.ID
		}
		if r.ID > n {
			out = append(out, r)
		}
	}
	return out, cursor, sc.Err()
}

// advertise publishes the proxy as _ispindle-proxy._tcp over mDNS so the phone
// app can discover it. With ifaceName set, only that interface's IPv4 address
// is announced — use it on a multi-homed host (e.g. a router with LAN+WAN) so
// the app doesn't resolve to an unreachable subnet. Empty = announce on all
// interfaces, fine for a single-homed box.
func advertise(port int, ifaceName string) (*zeroconf.Server, error) {
	const instance, service, domain = "iSpindle Proxy", "_ispindle-proxy._tcp", "local."
	txt := []string{"app=ispindle-proxy"}

	if ifaceName == "" {
		s, err := zeroconf.Register(instance, service, domain, port, txt, nil)
		if err == nil {
			log.Printf("advertising %s on :%d (all interfaces)", service, port)
		}
		return s, err
	}

	iface, err := net.InterfaceByName(ifaceName)
	if err != nil {
		return nil, err
	}
	addrs, err := iface.Addrs()
	if err != nil {
		return nil, err
	}
	var ips []string
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil {
			ips = append(ips, ipn.IP.String())
		}
	}
	if len(ips) == 0 {
		return nil, &net.AddrError{Err: "no IPv4 address", Addr: ifaceName}
	}
	// Dedicated SRV target (not the box's real hostname) so resolution returns
	// only the LAN IP we published — a co-resident avahi/mdnsd advertising the
	// real hostname with all interfaces won't pollute the answer.
	s, err := zeroconf.RegisterProxy(instance, service, domain, port, "ispindle-proxy", ips, txt, []net.Interface{*iface})
	if err == nil {
		log.Printf("advertising %s on :%d via %s (%v)", service, port, ifaceName, ips)
	}
	return s, err
}

// deviceConfig is the delta attached to device POST responses:
// {"status":"ok","cfg":{"POLY":"...","sleep":N}}. Key names match the
// firmware's /config.json on LittleFS (POLY = gravity polynominal, sleep =
// interval in seconds); the firmware picks it up via processResponse.
// deviceConfig is the config delta attached to POST responses. POLY and sleep
// are sticky: attached until edited out of the file. SSID/PSK are one-shot:
// the pair is attached once for the device to try as its *pending* credential
// (promoted on its first successful POST, dropped after 3 failed wakes), and
// the push is retired as soon as the device acks any POST with a "cf" field —
// or after maxWifiDeliveries unacked deliveries. The device never saves
// pushed credentials without that trial.
//
// fw is also one-shot (separate "fa" ack, so a stale cf can never retire it or
// vice versa): the device downloads the binary from url in the wake that
// receives the push — md5-verified before adoption, so a corrupt or wrong
// file can never be flashed. A successful update reboots the device into the
// new firmware without acking; the push retires on maxFwDeliveries unacked
// deliveries or the next POST's fa ack.
type deviceConfig struct {
	Poly  string  `json:"POLY,omitempty"`
	Sleep int     `json:"sleep,omitempty"`
	Ssid  string  `json:"SSID,omitempty"`
	Psk   string  `json:"PSK,omitempty"`
	Fw    *fwPush `json:"fw,omitempty"`
}

// fwPush tells the device to OTA onto a binary served by this proxy at /fw.bin
// (--fw-file). ver must differ from the device's running FIRMWAREVERSION —
// the device skips and acks otherwise; md5 is the digest of the binary (GET
// /fw reports it) and is checked by the device after download and again by
// the bootloader's eboot header check at flash adoption.
type fwPush struct {
	Url string `json:"url"`
	Md5 string `json:"md5"`
	Ver string `json:"ver"`
}

func (c deviceConfig) empty() bool {
	return c.Poly == "" && c.Sleep == 0 && c.Ssid == "" && c.Psk == "" && c.Fw == nil
}

func isHex32(s string) bool {
	if len(s) != 32 {
		return false
	}
	for _, r := range s {
		if !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F')) {
			return false
		}
	}
	return true
}

// validateConfig enforces the structural half of the checks. The firmware
// additionally compiles POLY and bounds its SG output with the same TinyExpr
// evaluator it uses for gravity, and applies the same sleep window — so a
// value accepted here can still be rejected on-device (it logs and keeps the
// previous value).
func validateConfig(c deviceConfig) error {
	if c.empty() {
		return errors.New("cfg: POLY, sleep and/or SSID+PSK required")
	}
	if c.Poly != "" {
		if len(c.Poly) > 250 { // the config portal's POLYN input cap
			return fmt.Errorf("cfg: POLY is %d chars, cap is 250", len(c.Poly))
		}
		if !utf8.ValidString(c.Poly) {
			return errors.New("cfg: POLY is not valid UTF-8")
		}
		if !strings.Contains(c.Poly, "tilt") {
			return errors.New(`cfg: POLY must reference the "tilt" variable`)
		}
		for _, r := range c.Poly {
			if r < 0x20 || r > 0x7e {
				return errors.New("cfg: POLY must be printable ASCII")
			}
		}
	}
	if c.Sleep != 0 && (c.Sleep <= 10 || c.Sleep >= 24*60*60) {
		return errors.New("cfg: sleep must be > 10 and < 86400 seconds")
	}
	// WiFi credentials go as a pair and pass through to the firmware's pending
	// slots; the device-side ladder enforces the promotion/dropback policy.
	if (c.Ssid == "") != (c.Psk == "") {
		return errors.New("cfg: SSID and PSK go together")
	}
	if c.Ssid != "" {
		if len(c.Ssid) > 32 { // 802.11 SSID byte cap
			return fmt.Errorf("cfg: SSID is %d chars, cap is 32", len(c.Ssid))
		}
		if len(c.Psk) > 63 { // WPA2 passphrase cap
			return fmt.Errorf("cfg: PSK is %d chars, cap is 63", len(c.Psk))
		}
		if !utf8.ValidString(c.Ssid) || !utf8.ValidString(c.Psk) {
			return errors.New("cfg: SSID/PSK is not valid UTF-8")
		}
		for _, r := range c.Ssid + c.Psk {
			if r < 0x20 || r > 0x7e {
				return errors.New("cfg: SSID/PSK must be printable ASCII")
			}
		}
	}
	if c.Fw != nil {
		if c.Fw.Url == "" || c.Fw.Md5 == "" || c.Fw.Ver == "" {
			return errors.New("cfg: fw requires url, md5 and ver")
		}
		if len(c.Fw.Url) > 127 {
			return fmt.Errorf("cfg: fw url is %d chars, cap is 127", len(c.Fw.Url))
		}
		if !strings.HasPrefix(c.Fw.Url, "http://") && !strings.HasPrefix(c.Fw.Url, "https://") {
			return errors.New(`cfg: fw url must start with "http(s)://"`)
		}
		if !isHex32(c.Fw.Md5) {
			return errors.New("cfg: fw md5 must be a 32-char hex digest")
		}
		if len(c.Fw.Ver) > 24 { // FIRMWAREVERSION-sized string
			return fmt.Errorf("cfg: fw ver is %d chars, cap is 24", len(c.Fw.Ver))
		}
		for _, r := range c.Fw.Url + c.Fw.Md5 + c.Fw.Ver {
			if r < 0x20 || r > 0x7e {
				return errors.New("cfg: fw fields must be printable ASCII")
			}
		}
	}
	return nil
}

// maxWifiDeliveries bounds the one-shot SSID/PSK push: after this many POST
// responses carried the pair without the device acking it (cf field), the
// push is retired from the response and the file. Re-arm with PUT /cfg.
const maxWifiDeliveries = 5

// maxFwDeliveries is the same budget for the fw OTA push (device ack field:
// "fa"). A successful OTA reboots the device without acking, so the budget is
// what retires the push in the happy path.
const maxFwDeliveries = 5

// cfgStore holds the pushed-config file's current contents, reloading it when
// its size+mtime change (checked on each device POST — one stat, no polling
// goroutine). The file is the source of truth: present+valid → the cfg delta
// is attached to POST responses; missing or invalid → plain responses (logged
// once per state change). SSID/PSK and fw are the exception: they are retired
// from the response and the file on device ack (cf / fa) or after their
// delivery budgets run out unacked. path == "" disables the feature entirely.
type cfgStore struct {
	mu             sync.Mutex
	path           string
	raw            []byte // exact POST-response bytes, nil when nothing is attached
	cur            deviceConfig
	sig            fileSig
	seen           bool // a stat of the current path has been processed
	wifiDeliveries int  // POSTs since the current SSID/PSK push was armed
	fwDeliveries   int  // POSTs since the current fw push was armed
}

type fileSig struct {
	size int64
	mod  time.Time
}

func newCfgStore(path string) *cfgStore {
	c := &cfgStore{path: path}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reloadLocked()
	return c
}

// reloadLocked re-reads the cfg file when its stat signature changed.
// Caller holds c.mu.
func (c *cfgStore) reloadLocked() {
	if c.path == "" {
		return
	}
	var sig fileSig
	fi, err := os.Stat(c.path)
	if err == nil {
		sig = fileSig{size: fi.Size(), mod: fi.ModTime()}
	}
	if c.seen && sig == c.sig {
		return
	}
	c.sig = sig
	c.seen = true

	if err != nil { // missing (the normal steady state) or unreadable
		c.setRaw(nil, fmt.Sprintf("no cfg attached — %s: %v", c.path, err))
		return
	}

	b, err := os.ReadFile(c.path)
	var dc deviceConfig
	if err == nil {
		err = json.Unmarshal(b, &dc)
	}
	if err == nil {
		err = validateConfig(dc)
	}
	if err != nil {
		c.setRaw(nil, fmt.Sprintf("%s invalid, not attached to responses: %v", c.path, err))
		return
	}
	c.setRaw([]byte(`{"status":"ok","cfg":`+mustJSON(dc)+`}`),
		fmt.Sprintf("cfg attached: POLY=%q sleep=%d SSID=%q (PSK %d chars) fw-ver=%q from %s",
			dc.Poly, dc.Sleep, dc.Ssid, len(dc.Psk), fwVerOf(dc.Fw), c.path))
	if c.cur.Ssid != "" {
		c.wifiDeliveries = 0 // fresh push: full delivery budget from this arm
	}
	if c.cur.Fw != nil {
		c.fwDeliveries = 0
	}
}

func fwVerOf(f *fwPush) string {
	if f == nil {
		return ""
	}
	return f.Ver
}

// setRaw swaps the response body, logging only on transitions.
func (c *cfgStore) setRaw(raw []byte, msg string) {
	unchanged := string(raw) == string(c.raw)
	c.raw = raw
	c.cur = deviceConfig{}
	if raw != nil {
		json.Unmarshal(raw[len(`{"status":"ok","cfg":`):len(raw)-1], &c.cur) // valid by construction
	}
	if unchanged {
		return
	}
	log.Printf("cfg: %s", msg)
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err) // impossible for deviceConfig
	}
	return string(b)
}

// snapshot returns the exact bytes to write as the POST response body,
// re-reading the cfg file first when its stat signature changed. An armed
// SSID/PSK or fw push burns one delivery budget unit per snapshot and is
// retired once its budget runs out unacked.
func (c *cfgStore) snapshot() []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reloadLocked()
	if c.raw == nil {
		return []byte(`{"status":"ok"}`)
	}
	if c.cur.Ssid != "" {
		c.wifiDeliveries++
		if c.wifiDeliveries > maxWifiDeliveries {
			log.Printf("cfg: SSID/PSK push delivered %d times without device ack — retiring", maxWifiDeliveries)
			c.dropWifiLocked()
			if c.raw == nil {
				return []byte(`{"status":"ok"}`)
			}
		}
	}
	if c.cur.Fw != nil {
		c.fwDeliveries++
		if c.fwDeliveries > maxFwDeliveries {
			log.Printf("cfg: fw push delivered %d times without device ack — retiring", maxFwDeliveries)
			c.dropFwLocked()
			if c.raw == nil {
				return []byte(`{"status":"ok"}`)
			}
		}
	}
	return c.raw
}

// noteCf reads the device's ladder ack from a POST ("cf" field): "trial" =
// the POST went out over the armed pending credentials, "reverted" = they
// failed and it fell back to the saved SSID, "refused" = the push was
// rejected on-device. Any ack retires the one-shot SSID/PSK push; POLY and
// sleep stay sticky until removed from the file.
func (c *cfgStore) noteCf(cf string) {
	if cf == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reloadLocked() // cur may be stale: the device can ack its FIRST post after arming
	if c.cur.Ssid == "" {
		log.Printf("cfg: device cf=%q (no SSID/PSK push armed)", cf)
		return
	}
	log.Printf("cfg: device cf=%q — retiring SSID/PSK push", cf)
	c.dropWifiLocked()
}

// noteFa reads the device's OTA ack from a POST ("fa" field): "err" = the
// device attempted the pushed update and it failed, "skip" = it declined to
// attempt (weak link, malformed push, or a ver matching the running
// firmware). Any ack retires the one-shot fw push; POLY/sleep/SSID+PSK stay
// as they were. The device does NOT ack a successful OTA — it reboots into
// the new image instead, and the budget handles that case.
func (c *cfgStore) noteFa(fa string) {
	if fa == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reloadLocked() // cur may be stale: the device can ack its FIRST post after arming
	if c.cur.Fw == nil {
		log.Printf("cfg: device fa=%q (no fw push armed)", fa)
		return
	}
	log.Printf("cfg: device fa=%q — retiring fw push", fa)
	c.dropFwLocked()
}

// dropWifiLocked removes SSID/PSK from the current push and persists the file
// so a proxy restart does not re-arm it. Caller holds c.mu.
func (c *cfgStore) dropWifiLocked() {
	c.cur.Ssid = ""
	c.cur.Psk = ""
	c.wifiDeliveries = 0
	c.persistRetiredLocked("SSID/PSK push")
}

// dropFwLocked is dropWifiLocked for the fw push.
func (c *cfgStore) dropFwLocked() {
	c.cur.Fw = nil
	c.fwDeliveries = 0
	c.persistRetiredLocked("fw push")
}

// persistRetiredLocked rebuilds the response from whatever is still armed
// (nothing → plain responses) and rewrites the cfg file so a proxy restart
// cannot re-arm the retired push. Caller holds c.mu.
func (c *cfgStore) persistRetiredLocked(msg string) {
	if c.cur.empty() {
		c.setRaw(nil, msg+" retired; nothing else armed")
	} else {
		c.setRaw([]byte(`{"status":"ok","cfg":`+mustJSON(c.cur)+`}`), msg+" retired")
	}
	if err := writeCfgFile(c.path, c.cur); err != nil {
		log.Printf("cfg: persisting retired push failed (still retired in memory): %v", err)
	}
}

// curValue returns the parsed config (zero value when none is attached).
func (c *cfgStore) curValue() deviceConfig {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reloadLocked()
	return c.cur
}

// writeCfgFile atomically replaces the cfg file (used by PUT /cfg).
func writeCfgFile(path string, dc deviceConfig) error {
	body, err := json.MarshalIndent(dc, "", "  ")
	if err != nil {
		return err
	}
	body = append(body, '\n')
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".cfg-*.tmp")
	if err != nil {
		return err
	}
	tname := tmp.Name()
	if _, err := tmp.Write(body); err != nil {
		tmp.Close()
		os.Remove(tname)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tname)
		return err
	}
	return os.Rename(tname, path)
}

func main() {
	addr := flag.String("addr", ":9501", "listen address (same port the firmware already targets)")
	db := flag.String("db", "readings.jsonl", "append-only buffer file")
	max := flag.Int("max", 10000, "max buffered records before old ones are pruned")
	mdnsIface := flag.String("mdns-iface", "", "advertise mDNS on this interface only (e.g. br-lan); empty = all interfaces")
	cfgFile := flag.String("cfg-file", "", "config-push file: JSON {POLY, sleep, one-shot SSID+PSK/fw} attached to POST responses; editable via /cfg. Empty = feature off")
	fwFile := flag.String("fw-file", "", "firmware binary served at /fw.bin for remote OTA (PUT /cfg {\"fw\":{url,md5,ver}} arms it; GET /fw reports size+md5). Empty = feature off")
	flag.Parse()

	buf, err := openBuffer(*db, *max)
	if err != nil {
		log.Fatalf("open buffer %s: %v", *db, err)
	}
	log.Printf("ispindle-proxy on %s, buffer=%s (%d records, last id %d), max=%d",
		*addr, *db, buf.count, buf.seq, *max)

	cfgs := newCfgStore(*cfgFile)

	// Advertise over mDNS so the phone app can discover us without a
	// hand-typed URL. Non-fatal: if it fails the proxy still serves, the app
	// just needs the manual URL fallback.
	port := 9501
	if _, p, e := net.SplitHostPort(*addr); e == nil {
		if pi, e := strconv.Atoi(p); e == nil {
			port = pi
		}
	}
	if mdns, e := advertise(port, *mdnsIface); e != nil {
		log.Printf("mDNS advertise failed (continuing, use manual URL): %v", e)
	} else {
		defer mdns.Shutdown()
	}

	log.Fatal(http.ListenAndServe(*addr, router(buf, cfgs, *fwFile)))
}

// router assembles the HTTP surface; split out of main for tests.
func router(buf *buffer, cfgs *cfgStore, fwPath string) *http.ServeMux {
	mux := http.NewServeMux()

	// Remote-OTA firmware: the device downloads /fw.bin when an fw push arms
	// it; the operator GETs /fw for the md5+size to PUT as ver+md5. Empty
	// --fw-file disables both.
	mux.HandleFunc("/fw", func(w http.ResponseWriter, r *http.Request) {
		if fwPath == "" {
			http.Error(w, "firmware serving not configured (start with --fw-file)", http.StatusNotImplemented)
			return
		}
		if r.Method != http.MethodGet {
			http.Error(w, "GET only", http.StatusMethodNotAllowed)
			return
		}
		f, err := os.Open(fwPath)
		if err != nil {
			http.Error(w, "firmware file unreadable", http.StatusNotFound)
			return
		}
		defer f.Close()
		h := md5.New()
		n, err := io.Copy(h, f)
		if err != nil {
			http.Error(w, "firmware read error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(struct {
			Size int64  `json:"size"`
			Md5  string `json:"md5"`
		}{n, hex.EncodeToString(h.Sum(nil))})
	})
	mux.HandleFunc("/fw.bin", func(w http.ResponseWriter, r *http.Request) {
		if fwPath == "" {
			http.Error(w, "firmware serving not configured (start with --fw-file)", http.StatusNotImplemented)
			return
		}
		http.ServeFile(w, r, fwPath)
	})

	// Phone catch-up endpoint.
	mux.HandleFunc("/readings", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "GET only", http.StatusMethodNotAllowed)
			return
		}
		since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		recs, cursor, err := buf.since(since)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(struct {
			Cursor   int64    `json:"cursor"`
			Readings []record `json:"readings"`
		}{cursor, recs})
	})

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})

	// Config push: the delta the device reads with its next POST. GET = view,
	// PUT = replace (atomic write), DELETE = clear. Empty --cfg-file disables.
	mux.HandleFunc("/cfg", func(w http.ResponseWriter, r *http.Request) {
		if cfgs == nil || cfgs.path == "" {
			http.Error(w, "config push not configured (start with --cfg-file)", http.StatusNotImplemented)
			return
		}
		switch r.Method {
		case http.MethodGet:
			w.Header().Set("Content-Type", "application/json")
			cur := cfgs.curValue()
			if cur.empty() {
				w.Write([]byte("{}\n"))
				return
			}
			json.NewEncoder(w).Encode(cur)
		case http.MethodPut:
			rawBody, err := io.ReadAll(io.LimitReader(r.Body, 4096))
			if err != nil {
				http.Error(w, "read error", http.StatusBadRequest)
				return
			}
			var dc deviceConfig
			// Unmarshal (like the POST path), not Decode: trailing junk is an error.
			if err := json.Unmarshal(rawBody, &dc); err != nil {
				http.Error(w, "bad json: "+err.Error(), http.StatusBadRequest)
				return
			}
			if err := validateConfig(dc); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			if err := writeCfgFile(cfgs.path, dc); err != nil {
				log.Printf("cfg: write failed: %v", err)
				http.Error(w, "cfg write failed", http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(dc)
		case http.MethodDelete:
			if err := os.Remove(cfgs.path); err != nil && !os.IsNotExist(err) {
				log.Printf("cfg: remove failed: %v", err)
				http.Error(w, "cfg remove failed", http.StatusInternalServerError)
				return
			}
			w.Write([]byte("{}\n"))
		default:
			http.Error(w, "GET/PUT/DELETE only", http.StatusMethodNotAllowed)
		}
	})

	// Catch-all: the firmware's POST path is user-configured, so every POST
	// funnels in here (mirrors the phone's IspindleHttpServer). A GET to "/"
	// is just an info probe.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Write([]byte("iSpindle proxy — device POSTs JSON here; phone GETs /readings?since=N, cfg via /cfg\n"))
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024))
		if err != nil {
			http.Error(w, "read error", http.StatusBadRequest)
			return
		}
		// Validate it's a JSON object before buffering (matches the phone's
		// BadRequest on junk). Store the raw bytes so we replay byte-for-byte.
		var probe map[string]json.RawMessage
		if json.Unmarshal(body, &probe) != nil {
			http.Error(w, `{"error":"bad json"}`, http.StatusBadRequest)
			return
		}
		var cf, fa string
		if v, ok := probe["cf"]; ok {
			json.Unmarshal(v, &cf) // best effort; junk → ""
		}
		if v, ok := probe["fa"]; ok {
			json.Unmarshal(v, &fa) // best effort; junk → ""
		}
		ip := r.RemoteAddr
		if host, _, e := net.SplitHostPort(ip); e == nil {
			ip = host
		}
		if _, err := buf.append(json.RawMessage(body), ip); err != nil {
			log.Printf("append failed: %v", err)
			http.Error(w, "buffer write failed", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		cfgs.noteCf(cf) // retire a one-shot SSID/PSK push on device ack, before the response is built
		cfgs.noteFa(fa) // same for the fw OTA push ("fa")
		w.Write(cfgs.snapshot())
	})

	return mux
}
