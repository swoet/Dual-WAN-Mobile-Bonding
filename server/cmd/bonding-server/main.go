package main

import (
	"crypto/tls"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"time"
)

func main() {
	certFile := getenv("TLS_CERT", "")
	keyFile := getenv("TLS_KEY", "")
	listen := getenv("LISTEN_ADDR", ":443")
	metricsListen := getenv("METRICS_ADDR", ":8080")
	insecure := getenv("INSECURE", "") != ""

	go func() {
		http.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200); _, _ = w.Write([]byte("ok")) })
		log.Printf("metrics/health on %s", metricsListen)
		_ = http.ListenAndServe(metricsListen, nil)
	}()

	var ln net.Listener
	var err error
	if insecure {
		ln, err = net.Listen("tcp", listen)
		if err != nil { log.Fatal(err) }
		log.Printf("listening INSECURE on %s", listen)
	} else {
		if certFile == "" || keyFile == "" {
			log.Fatal("TLS_CERT and TLS_KEY must be set or INSECURE=1 for plaintext")
		}
		cert, err := tls.LoadX509KeyPair(certFile, keyFile)
		if err != nil { log.Fatal(err) }
		cfg := &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13}
		ln, err = tls.Listen("tcp", listen, cfg)
		if err != nil { log.Fatal(err) }
		log.Printf("listening TLS on %s", listen)
	}

	for {
		conn, err := ln.Accept()
		if err != nil { continue }
		go handleConn(conn)
	}
}

func handleConn(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(30 * time.Second))
	buf := make([]byte, 4096)
	n, err := c.Read(buf)
	if err != nil { return }
	// very small protocol: client sends "CONNECT host port\n"
	var host string
	var port string
	_, err = fmt.Sscanf(string(buf[:n]), "CONNECT %s %s\n", &host, &port)
	if err != nil { return }
	backend, err := net.Dial("tcp", net.JoinHostPort(host, port))
	if err != nil { return }
	_ = c.SetDeadline(time.Time{})
	_ = backend.SetDeadline(time.Time{})
	// simple bidirectional copy
	go func() { defer backend.Close(); defer c.Close(); ioCopy(backend, c) }()
	ioCopy(c, backend)
}

func ioCopy(dst net.Conn, src net.Conn) {
	buf := make([]byte, 32*1024)
	for {
		n, err := src.Read(buf)
		if n > 0 {
			if _, werr := dst.Write(buf[:n]); werr != nil { return }
		}
		if err != nil { return }
	}
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" { return v }
	return def
}
