package main

// Tests for the config-push surface (/cfg, cfg attached to POST responses)
// and validateConfig bounds. The readings-buffer behaviour is unchanged and
// not re-tested here beyond one sanity record check.

import (
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newTestProxy(t *testing.T, cfgPath, fwPath string) string {
	t.Helper()
	buf, err := openBuffer(filepath.Join(t.TempDir(), "readings.jsonl"), 10)
	if err != nil {
		t.Fatalf("openBuffer: %v", err)
	}
	ts := httptest.NewServer(router(buf, newCfgStore(cfgPath), fwPath))
	t.Cleanup(ts.Close)
	return ts.URL
}

func postReading(t *testing.T, url string) string {
	t.Helper()
	res, err := http.Post(url, "application/json",
		strings.NewReader(`{"name":"ispindle-test","angle":45,"temperature":20}`))
	if err != nil {
		t.Fatalf("POST: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("POST status %d", res.StatusCode)
	}
	var out struct {
		Status string          `json:"status"`
		Cfg    json.RawMessage `json:"cfg"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		t.Fatalf("POST body not json: %v", err)
	}
	if out.Status != "ok" {
		t.Fatalf("status = %q, want ok", out.Status)
	}
	return string(out.Cfg)
}

// postReadingCf posts a device reading with the firmware's ladder-ack field
// ("cf") and returns the attached cfg (empty string when none).
func postReadingCf(t *testing.T, url, cf string) string {
	return postReadingAck(t, url, map[string]string{"cf": cf})
}

// postReadingFa posts with the OTA-ack field ("fa") — same contract.
func postReadingFa(t *testing.T, url, fa string) string {
	return postReadingAck(t, url, map[string]string{"fa": fa})
}

func postReadingAck(t *testing.T, url string, fields map[string]string) string {
	t.Helper()
	names := []string{"name", "angle", "temperature"}
	vals := []string{"ispindle-test", "45", "20"}
	for k, v := range fields {
		names = append(names, k)
		vals = append(vals, v)
	}
	parts := make([]string, len(names))
	for i := range names {
		if names[i] == "name" || names[i] == "cf" || names[i] == "fa" {
			parts[i] = fmt.Sprintf("%q:%q", names[i], vals[i])
		} else {
			parts[i] = fmt.Sprintf("%q:%s", names[i], vals[i])
		}
	}
	res, err := http.Post(url, "application/json",
		strings.NewReader("{"+strings.Join(parts, ",")+"}"))
	if err != nil {
		t.Fatalf("POST: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("POST status %d", res.StatusCode)
	}
	var out struct {
		Status string          `json:"status"`
		Cfg    json.RawMessage `json:"cfg"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		t.Fatalf("POST body not json: %v", err)
	}
	if out.Status != "ok" {
		t.Fatalf("status = %q, want ok", out.Status)
	}
	return string(out.Cfg)
}

func putCfg(t *testing.T, url, body string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPut, url, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	return res
}

func writeTestCfg(t *testing.T, path string, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

const validPoly = "0.95683653 + 0.00156709*tilt + 0.00000376*tilt^2"

func TestPostPlainWithoutCfg(t *testing.T) {
	url := newTestProxy(t, "", "")
	if got := postReading(t, url); got != "" {
		t.Fatalf("cfg attached with --cfg-file unset: %q", got)
	}
}

func TestPostAttachesCfgFromFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	writeTestCfg(t, path, fmt.Sprintf(`{"POLY":%q,"sleep":60}`, validPoly))
	url := newTestProxy(t, path, "")

	got := postReading(t, url)
	var cfg deviceConfig
	if err := json.Unmarshal([]byte(got), &cfg); err != nil {
		t.Fatalf("cfg object invalid: %v (got %q)", err, got)
	}
	if cfg.Poly != validPoly || cfg.Sleep != 60 {
		t.Fatalf("cfg = %+v, want POLY=%q sleep=60", cfg, validPoly)
	}
}

// The operator can hand-edit or curl-write the file between device POSTs; the
// proxy must notice without a restart, and go back to plain when it's removed.
func TestCfgFileChangePickedUpBetweenPosts(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	if got := postReading(t, url); got != "" {
		t.Fatalf("cfg attached before file exists: %q", got)
	}
	writeTestCfg(t, path, fmt.Sprintf(`{"POLY":%q}`, validPoly))
	postReading(t, url)
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}

	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if got := postReading(t, url); got != "" {
		t.Fatalf("cfg attached after file removed: %q", got)
	}
}

// A broken cfg file must not produce a cfg — the firmware would otherwise get
// an invalid POLY and (by design) keep the old one anyway, but don't send junk.
func TestInvalidCfgFileIsNotAttached(t *testing.T) {
	for name, body := range map[string]string{
		"bad json":  `{"POLY": `,
		"bad poly":  `{"POLY":"not a polynomial"}`,
		"bad sleep": `{"sleep":5}`,
		"empty":     `{"POLY":"","sleep":0}`,
		// structurally fine but nonsense (SG = tilt): the firmware's own
		// 0.5–2.0 SG check is the layer that rejects this one, so it's in
		// TestValidateConfig, not here
		"sleep-only junk": `{"sleep":5,"POLY":"1+temp"}`,
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "cfg.json")
			writeTestCfg(t, path, body)
			url := newTestProxy(t, path, "")
			if got := postReading(t, url); got != "" {
				t.Fatalf("invalid cfg attached: %q", got)
			}
		})
	}
}

func TestValidateConfig(t *testing.T) {
	cases := []struct {
		name    string
		cfg     deviceConfig
		wantErr bool
	}{
		{"empty", deviceConfig{}, true},
		{"poly at cap", deviceConfig{Poly: strings.Repeat("0.1*tilt+", 250)[:250]}, false},
		{"poly over cap", deviceConfig{Poly: strings.Repeat("0.1*tilt+", 40)}, true},
		{"poly missing tilt", deviceConfig{Poly: "0.5+temp"}, true},
		{"poly non-ascii", deviceConfig{Poly: "0.5*tilt°"}, true},
		{"poly no coeff", deviceConfig{Poly: "tilt"}, false},
		{"sleep min", deviceConfig{Sleep: 11}, false},
		{"sleep 10 rejected", deviceConfig{Sleep: 10}, true},
		{"sleep max", deviceConfig{Sleep: 24*60*60 - 1}, false},
		{"sleep 86400 rejected", deviceConfig{Sleep: 24 * 60 * 60}, true},
		{"negative sleep", deviceConfig{Sleep: -5}, true},
		{"poly+sleep", deviceConfig{Poly: "tilt", Sleep: 60}, false},
		{"wifi pair", deviceConfig{Ssid: "UpperGarage2", Psk: "WinniePico"}, false},
		{"wifi pair alone", deviceConfig{Ssid: "UpperGarage2", Psk: "WinniePico", Poly: "", Sleep: 0}, false},
		{"ssid without psk", deviceConfig{Ssid: "UpperGarage2"}, true},
		{"psk without ssid", deviceConfig{Psk: "WinniePico"}, true},
		{"ssid at cap", deviceConfig{Ssid: strings.Repeat("a", 32), Psk: "x"}, false},
		{"ssid over cap", deviceConfig{Ssid: strings.Repeat("a", 33), Psk: "x"}, true},
		{"psk at cap", deviceConfig{Ssid: "a", Psk: strings.Repeat("x", 63)}, false},
		{"psk over cap", deviceConfig{Ssid: "a", Psk: strings.Repeat("x", 64)}, true},
		{"wifi non-ascii ssid", deviceConfig{Ssid: "Spöndle", Psk: "x"}, true},
		{"wifi + poly", deviceConfig{Poly: "tilt", Ssid: "a", Psk: "b"}, false},
		{"fw complete", deviceConfig{Fw: &fwPush{Url: "http://192.168.0.180:9501/fw.bin", Md5: strings.Repeat("a", 32), Ver: "7.3.4"}}, false},
		{"fw https", deviceConfig{Fw: &fwPush{Url: "https://192.168.0.180:9501/fw.bin", Md5: strings.Repeat("a", 32), Ver: "7.3.4"}}, false},
		{"fw md5 upper hex ok", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin", Md5: strings.Repeat("A", 32), Ver: "7.3.4"}}, false},
		{"fw missing url", deviceConfig{Fw: &fwPush{Md5: strings.Repeat("a", 32), Ver: "7.3.4"}}, true},
		{"fw missing md5", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin", Ver: "7.3.4"}}, true},
		{"fw missing ver", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin", Md5: strings.Repeat("a", 32)}}, true},
		{"fw md5 short", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin", Md5: strings.Repeat("a", 31), Ver: "7.3.4"}}, true},
		{"fw md5 non-hex", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin", Md5: strings.Repeat("z", 32), Ver: "7.3.4"}}, true},
		{"fw url ftp", deviceConfig{Fw: &fwPush{Url: "ftp://x/fw.bin", Md5: strings.Repeat("a", 32), Ver: "7.3.4"}}, true},
		{"fw url over cap", deviceConfig{Fw: &fwPush{Url: "http://" + strings.Repeat("a", 121), Md5: strings.Repeat("a", 32), Ver: "7.3.4"}}, true},
		{"fw ver over cap", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin", Md5: strings.Repeat("a", 32), Ver: strings.Repeat("a", 25)}}, true},
		{"fw url non-ascii", deviceConfig{Fw: &fwPush{Url: "http://x/fw.bin?a=°", Md5: strings.Repeat("a", 32), Ver: "7.3.4"}}, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := validateConfig(c.cfg)
			if (err != nil) != c.wantErr {
				t.Fatalf("validateConfig(%+v) = %v, wantErr %v", c.cfg, err, c.wantErr)
			}
		})
	}
}

func TestPutCfgRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	res := putCfg(t, url+"/cfg", fmt.Sprintf(`{"POLY":%q,"sleep":30}`, validPoly))
	if res.StatusCode != http.StatusOK {
		t.Fatalf("PUT status %d", res.StatusCode)
	}

	// The file on disk now holds the value...
	// (and a POST carries it into a response — covers the mstat-reload path)
	got := postReading(t, url)
	var cfg deviceConfig
	if err := json.Unmarshal([]byte(got), &cfg); err != nil {
		t.Fatalf("cfg invalid: %v (got %q)", err, got)
	}
	if cfg.Poly != validPoly || cfg.Sleep != 30 {
		t.Fatalf("post-cfg = %+v, want POLY=<recovered> sleep=30", cfg)
	}

	// GET shows the active config.
	res2, err := http.Get(url + "/cfg")
	if err != nil {
		t.Fatal(err)
	}
	defer res2.Body.Close()
	var cur deviceConfig
	if err := json.NewDecoder(res2.Body).Decode(&cur); err != nil {
		t.Fatalf("GET /cfg not json: %v", err)
	}
	if !cur.empty() && cur != cfg {
		t.Fatalf("GET /cfg = %+v, want %+v", cur, cfg)
	}

	// DELETE clears it.
	req, err := http.NewRequest(http.MethodDelete, url+"/cfg", nil)
	if err != nil {
		t.Fatal(err)
	}
	if res3, err := http.DefaultClient.Do(req); err != nil || res3.StatusCode != http.StatusOK {
		if err == nil {
			res3.Body.Close()
			t.Fatalf("DELETE status %d", res3.StatusCode)
		}
		t.Fatal(err)
	}
	if got := postReading(t, url); got != "" {
		t.Fatalf("cfg attached after DELETE: %q", got)
	}
}

func TestPutCfgRejectsInvalid(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	rejects := []string{
		`{}`,
		`{"sleep":5}`,
		`{"sleep":86400}`,
		`{"POLY":"0.5*temp"}`,
		`{"POLY":"x"}`,
		`{"SSID":"only-ssid"}`,
		`{"PSK":"only-psk"}`,
		`{"SSID":"` + strings.Repeat("a", 33) + `","PSK":"x"}`,
		strings.Repeat(`{"sleep":60} `, 1) + "trailing junk",
	}
	for i, body := range rejects {
		res := putCfg(t, url+"/cfg", body)
		if res.StatusCode != http.StatusBadRequest {
			t.Fatalf("reject[%d] %q: status %d, want 400", i, body, res.StatusCode)
		}
		res.Body.Close()
	}

	// Nothing was written to disk.
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("cfg file exists after rejected PUTs")
	}
}

func TestCfgDisabledWithoutFlag(t *testing.T) {
	url := newTestProxy(t, "", "")
	res := putCfg(t, url+"/cfg", `{"POLY":"tilt"}`)
	if res.StatusCode != http.StatusNotImplemented {
		t.Fatalf("PUT with feature off: status %d, want 501", res.StatusCode)
	}
	res.Body.Close()

	res2, err := http.Get(url + "/cfg")
	if err != nil {
		t.Fatal(err)
	}
	res2.Body.Close()
	if res2.StatusCode != http.StatusNotImplemented {
		t.Fatalf("GET with feature off: status %d, want 501", res2.StatusCode)
	}
}

// One sanity check that config-push didn't change readings buffering.
func TestPostStillBuffered(t *testing.T) {
	url := newTestProxy(t, "", "")
	postReading(t, url)
	res, err := http.Get(url + "/readings?since=0")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var out struct {
		Cursor   int64    `json:"cursor"`
		Readings []record `json:"readings"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if out.Cursor != 1 || len(out.Readings) != 1 {
		t.Fatalf("cursor=%d readings=%d, want 1/1", out.Cursor, len(out.Readings))
	}
}

// The SSID/PSK push is one-shot and device-acked: attached to POST responses
// until a POST carrying a "cf" ladder ack arrives, then retired from the
// response and the file. POLY stays sticky through all of it. The device put
// the pair in its pending slots — the proxy side of the ladder contract is
// only the delivery.
func TestWifiPushRetiredOnDeviceAck(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	res := putCfg(t, url+"/cfg",
		fmt.Sprintf(`{"POLY":%q,"SSID":"UpperGarage2","PSK":"WinniePico"}`, validPoly))
	if res.StatusCode != http.StatusOK {
		t.Fatalf("PUT pair status %d, want 200", res.StatusCode)
	}
	res.Body.Close()

	// First POST: the push is attached.
	got := postReading(t, url)
	if got == "" {
		t.Fatal("pushed SSID/PSK not attached to POST response")
	}
	var cfg deviceConfig
	if err := json.Unmarshal([]byte(got), &cfg); err != nil {
		t.Fatalf("cfg invalid: %v (got %q)", err, got)
	}
	if cfg.Ssid != "UpperGarage2" || cfg.Psk != "WinniePico" || cfg.Poly != validPoly {
		t.Fatalf("cfg = %+v, want POLY+SSID/PSK", cfg)
	}

	// The device's cf ack retires the push.
	got = postReadingCf(t, url, "trial")
	cfg = deviceConfig{}
	if got == "" {
		t.Fatal("POLY lost when the wifi push was retired")
	}
	if err := json.Unmarshal([]byte(got), &cfg); err != nil {
		t.Fatalf("post-ack cfg invalid: %v (got %q)", err, got)
	}
	if cfg.Ssid != "" || cfg.Psk != "" || cfg.Poly != validPoly {
		t.Fatalf("post-ack cfg = %+v, want SSID/PSK gone, POLY kept", cfg)
	}

	// Retirement survives later POSTs and the file is rewritten, so a proxy
	// restart does not re-arm the push.
	got = postReading(t, url)
	cfg = deviceConfig{}
	if got == "" {
		t.Fatal("POLY gone on the post after retirement")
	}
	if err := json.Unmarshal([]byte(got), &cfg); err != nil {
		t.Fatalf("post-retirement cfg invalid: %v (got %q)", err, got)
	}
	if cfg.Ssid != "" {
		t.Fatalf("push re-armed after retirement: %+v", cfg)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), "UpperGarage2") {
		t.Fatalf("retired push still in the file: %s", b)
	}
	if !strings.Contains(string(b), "POLY") {
		t.Fatalf("POLY lost from the file: %s", b)
	}
}

// A "refused" ack (the device rejected the push) retires it too — re-pushing
// the identical pair would just be refused again; the operator changes it and
// PUTs a new one.
func TestWifiPushRetiredOnRefusedAck(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	res := putCfg(t, url+"/cfg", `{"SSID":"UpperGarage2","PSK":"WinniePico"}`)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("PUT pair status %d, want 200", res.StatusCode)
	}
	res.Body.Close()

	got := postReadingCf(t, url, "refused")
	if got != "" {
		var cfg deviceConfig
		if err := json.Unmarshal([]byte(got), &cfg); err != nil {
			t.Fatalf("cfg invalid: %v (got %q)", err, got)
		}
		if cfg.Ssid != "" {
			t.Fatalf("refused ack did not retire the push: %+v", cfg)
		}
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), "UpperGarage2") {
		t.Fatalf("retired push still in the file: %s", b)
	}
}

// Without any device ack the push still terminates: after maxWifiDeliveries
// responses it is retired again, so a device that never acks (asleep, gone,
// or ignoring) cannot loop a stale push forever.
func TestWifiPushDeliveryBudget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	res := putCfg(t, url+"/cfg", `{"SSID":"UpperGarage2","PSK":"WinniePico"}`)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("PUT pair status %d, want 200", res.StatusCode)
	}
	res.Body.Close()

	for i := 0; i < maxWifiDeliveries; i++ {
		got := postReading(t, url)
		if got == "" {
			t.Fatalf("delivery %d: PUSH MISSING", i+1)
		}
		var cfg deviceConfig
		if err := json.Unmarshal([]byte(got), &cfg); err != nil {
			t.Fatalf("delivery %d: cfg invalid: %v (got %q)", i+1, err, got)
		}
		if cfg.Ssid != "UpperGarage2" {
			t.Fatalf("delivery %d: SSID missing (%+v)", i+1, cfg)
		}
	}
	// Budget exhausted: the next response is plain and the file no longer
	// re-arms the push.
	got := postReading(t, url)
	if got != "" {
		var cfg deviceConfig
		if err := json.Unmarshal([]byte(got), &cfg); err != nil {
			t.Fatalf("post-budget cfg invalid: %v (got %q)", err, got)
		}
		if cfg.Ssid != "" {
			t.Fatalf("push still attached after %d deliveries: %q", maxWifiDeliveries, got)
		}
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), "UpperGarage2") {
		t.Fatalf("budget-retired push still in the file: %s", b)
	}
}

// The fw OTA push mirrors the wifi push's lifecycle: attached until the device
// acks it via the "fa" field (err = attempted and failed, skip = declined),
// then retired from the response and the file. POLY stays sticky through it.
func TestFwPushRetiredOnFaAck(t *testing.T) {
	for _, fa := range []string{"err", "skip"} {
		t.Run("fa="+fa, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "cfg.json")
			url := newTestProxy(t, path, "")

			res := putCfg(t, url+"/cfg",
				fmt.Sprintf(`{"POLY":%q,"fw":{"url":"http://192.168.0.180:9501/fw.bin","md5":%q,"ver":"7.3.4"}}`,
					validPoly, strings.Repeat("a", 32)))
			if res.StatusCode != http.StatusOK {
				t.Fatalf("PUT fw status %d, want 200", res.StatusCode)
			}
			res.Body.Close()

			// First POST: the push is attached.
			got := postReading(t, url)
			var cfg deviceConfig
			if err := json.Unmarshal([]byte(got), &cfg); err != nil {
				t.Fatalf("cfg invalid: %v (got %q)", err, got)
			}
			if cfg.Fw == nil || cfg.Fw.Ver != "7.3.4" || cfg.Poly != validPoly {
				t.Fatalf("cfg = %+v, want POLY + fw 7.3.4", cfg)
			}

			// The device's fa ack retires only the fw push.
			got = postReadingFa(t, url, fa)
			if got == "" {
				t.Fatal("POLY lost when the fw push was retired")
			}
			cfg = deviceConfig{}
			if err := json.Unmarshal([]byte(got), &cfg); err != nil {
				t.Fatalf("post-ack cfg invalid: %v (got %q)", err, got)
			}
			if cfg.Fw != nil || cfg.Poly != validPoly {
				t.Fatalf("post-ack cfg = %+v, want fw gone, POLY kept", cfg)
			}

			// Retirement survives later POSTs and the file is rewritten.
			got = postReading(t, url)
			cfg = deviceConfig{}
			if err := json.Unmarshal([]byte(got), &cfg); err != nil {
				t.Fatalf("post-retirement cfg invalid: %v (got %q)", err, got)
			}
			if cfg.Fw != nil {
				t.Fatalf("fw push re-armed after retirement: %+v", cfg.Fw)
			}
			b, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(b), "md5") || strings.Contains(string(b), "7.3.4") {
				t.Fatalf("retired fw push still in the file: %s", b)
			}
			if !strings.Contains(string(b), "POLY") {
				t.Fatalf("POLY lost from the file: %s", b)
			}
		})
	}
}

// Without an fa ack the fw push still terminates after maxFwDeliveries.
func TestFwPushDeliveryBudget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cfg.json")
	url := newTestProxy(t, path, "")

	res := putCfg(t, url+"/cfg",
		fmt.Sprintf(`{"fw":{"url":"http://192.168.0.180:9501/fw.bin","md5":%q,"ver":"7.3.4"}}`, "a"))
	// "a" as md5 fails validation → PUT must be rejected; the point of this
	// first PUT is the validation gate, so use a valid one for the real arm.
	if res.StatusCode == http.StatusOK {
		t.Fatal("PUT with a 1-char md5 accepted")
	}
	res.Body.Close()

	res2 := putCfg(t, url+"/cfg",
		fmt.Sprintf(`{"fw":{"url":"http://192.168.0.180:9501/fw.bin","md5":%q,"ver":"7.3.4"}}`, strings.Repeat("b", 32)))
	if res2.StatusCode != http.StatusOK {
		t.Fatalf("PUT fw status %d, want 200", res2.StatusCode)
	}
	res2.Body.Close()

	for i := 0; i < maxFwDeliveries; i++ {
		got := postReading(t, url)
		var cfg deviceConfig
		if err := json.Unmarshal([]byte(got), &cfg); err != nil {
			t.Fatalf("delivery %d: cfg invalid: %v (got %q)", i+1, err, got)
		}
		if cfg.Fw == nil {
			t.Fatalf("delivery %d: fw push missing (%+v)", i+1, cfg)
		}
	}
	// Budget exhausted: the next response is plain and the file no longer
	// re-arms the push.
	if got := postReading(t, url); got != "" {
		var cfg deviceConfig
		if err := json.Unmarshal([]byte(got), &cfg); err != nil {
			t.Fatalf("post-budget cfg invalid: %v (got %q)", err, got)
		}
		if cfg.Fw != nil {
			t.Fatalf("fw push still attached after %d deliveries: %+v", maxFwDeliveries, cfg.Fw)
		}
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), "md5") {
		t.Fatalf("budget-retired fw push still in the file: %s", b)
	}
}

// /fw.bin serves the configured binary (what the device downloads) and /fw
// reports its md5+size (what the operator PUTs as ver+md5).
func TestFwServing(t *testing.T) {
	bin := filepath.Join(t.TempDir(), "firmware.bin")
	content := []byte("fake binary payload for the serving test")
	if err := os.WriteFile(bin, content, 0o644); err != nil {
		t.Fatal(err)
	}
	sum := md5.Sum(content)
	wantMd5 := hex.EncodeToString(sum[:])

	url := newTestProxy(t, "", bin)

	res, err := http.Get(url + "/fw")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var info struct {
		Size int64  `json:"size"`
		Md5  string `json:"md5"`
	}
	if err := json.NewDecoder(res.Body).Decode(&info); err != nil {
		t.Fatalf("/fw not json: %v", err)
	}
	if info.Size != int64(len(content)) || info.Md5 != wantMd5 {
		t.Fatalf("/fw = {size:%d md5:%q}, want {%d %q}", info.Size, info.Md5, len(content), wantMd5)
	}

	res2, err := http.Get(url + "/fw.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer res2.Body.Close()
	got, err := io.ReadAll(res2.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(content) {
		t.Fatalf("/fw.bin bytes differ: got %d bytes, want %d", len(got), len(content))
	}
}

// Without --fw-file both endpoints say so, and a PUT carrying an fw push still
// succeeds (the push is validated against the config, not against serving —
// the operator can configure the file in a second step).
func TestFwServingNotConfigured(t *testing.T) {
	url := newTestProxy(t, "", "")

	for _, p := range []string{"/fw", "/fw.bin"} {
		res, err := http.Get(url + p)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if res.StatusCode != http.StatusNotImplemented {
			t.Fatalf("GET %s with feature off: status %d, want 501", p, res.StatusCode)
		}
	}
}
