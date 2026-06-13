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
	"encoding/json"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

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

func main() {
	addr := flag.String("addr", ":9501", "listen address (same port the firmware already targets)")
	db := flag.String("db", "readings.jsonl", "append-only buffer file")
	max := flag.Int("max", 10000, "max buffered records before old ones are pruned")
	mdnsIface := flag.String("mdns-iface", "", "advertise mDNS on this interface only (e.g. br-lan); empty = all interfaces")
	flag.Parse()

	buf, err := openBuffer(*db, *max)
	if err != nil {
		log.Fatalf("open buffer %s: %v", *db, err)
	}
	log.Printf("ispindle-proxy on %s, buffer=%s (%d records, last id %d), max=%d",
		*addr, *db, buf.count, buf.seq, *max)

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

	mux := http.NewServeMux()

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

	// Catch-all: the firmware's POST path is user-configured, so every POST
	// funnels in here (mirrors the phone's IspindleHttpServer). A GET to "/"
	// is just an info probe.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Write([]byte("iSpindle proxy — device POSTs JSON here; phone GETs /readings?since=N\n"))
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
		w.Write([]byte(`{"status":"ok"}`))
	})

	log.Fatal(http.ListenAndServe(*addr, mux))
}
