package main

import (
	"crypto/tls"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
)

func main() {
	certFile := getenv("TLS_CERT", "")
	keyFile := getenv("TLS_KEY", "")
	listen := getenv("LISTEN_ADDR", ":443")
	insecure := getenv("INSECURE", "") != ""

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200); _, _ = w.Write([]byte("ok")) })
	mux.HandleFunc("/fetch", handleFetch)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect {
			handleHTTPConnect(w, r)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	if insecure {
		log.Printf("listening HTTP (insecure) on %s", listen)
		log.Fatal(http.ListenAndServe(listen, mux))
	} else {
		if certFile == "" || keyFile == "" { log.Fatal("TLS_CERT and TLS_KEY must be set or INSECURE=1 for plaintext") }
		cfg := &tls.Config{MinVersion: tls.VersionTLS13}
		server := &http.Server{Addr: listen, Handler: mux, TLSConfig: cfg}
		log.Printf("listening HTTPS on %s", listen)
		log.Fatal(server.ListenAndServeTLS(certFile, keyFile))
	}
}

func handleHTTPConnect(w http.ResponseWriter, r *http.Request) {
	hj, ok := w.(http.Hijacker)
	if !ok { http.Error(w, "hijacking not supported", http.StatusInternalServerError); return }
	backend, err := net.Dial("tcp", r.Host)
	if err != nil { http.Error(w, err.Error(), http.StatusBadGateway); return }
	w.WriteHeader(http.StatusOK)
	conn, _, err := hj.Hijack()
	if err != nil { backend.Close(); return }
	go func() { defer backend.Close(); defer conn.Close(); io.Copy(backend, conn) }()
	io.Copy(conn, backend)
}

func handleFetch(w http.ResponseWriter, r *http.Request) {
	t := r.URL.Query().Get("url")
	if t == "" { http.Error(w, "missing url", http.StatusBadRequest); return }
	u, err := url.Parse(t)
	if err != nil { http.Error(w, "bad url", http.StatusBadRequest); return }
	req, err := http.NewRequest("GET", u.String(), nil)
	if err != nil { http.Error(w, err.Error(), http.StatusBadRequest); return }
	if rng := r.Header.Get("Range"); rng != "" { req.Header.Set("Range", rng) }
	resp, err := http.DefaultClient.Do(req)
	if err != nil { http.Error(w, err.Error(), http.StatusBadGateway); return }
	defer resp.Body.Close()
	for k, vv := range resp.Header { for _, v := range vv { w.Header().Add(k, v) } }
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}

func getenv(k, def string) string { if v := os.Getenv(k); v != "" { return v }; return def }
