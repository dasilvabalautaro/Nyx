package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
)

// newTestHost crea un host TCP en loopback (nodo o cliente del buzón).
func newTestHost(t *testing.T) host.Host {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatalf("host: %v", err)
	}
	t.Cleanup(func() { _ = h.Close() })
	return h
}

func connect(t *testing.T, from, to host.Host) {
	t.Helper()
	err := from.Connect(context.Background(), peer.AddrInfo{ID: to.ID(), Addrs: to.Addrs()})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
}

// mbxPut habla /krypta/mbx/put como lo hará el móvil.
func mbxPut(t *testing.T, from host.Host, node peer.ID, to string, blob []byte) error {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, mbxPutProtocol)
	if err != nil {
		t.Fatalf("put stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(mbxPutReq{V: 1, To: to, Blob: base64.StdEncoding.EncodeToString(blob)})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()
	line, err := bufio.NewReader(s).ReadString('\n')
	if err != nil {
		t.Fatalf("put response: %v", err)
	}
	var resp map[string]any
	if err := json.Unmarshal([]byte(line), &resp); err != nil {
		t.Fatalf("put response json: %v", err)
	}
	if e, ok := resp["err"].(string); ok {
		return fmt.Errorf("%s", e)
	}
	return nil
}

// mbxGet habla /krypta/mbx/get: retira los sobres y ack'ea los ids en ackIDs (nil = todos).
func mbxGet(t *testing.T, from host.Host, node peer.ID, ackIDs []string) []mbxEnvelope {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, mbxGetProtocol)
	if err != nil {
		t.Fatalf("get stream: %v", err)
	}
	defer s.Close()
	r := bufio.NewReader(s)
	var envs []mbxEnvelope
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("get read: %v", err)
		}
		var env mbxEnvelope
		if err := json.Unmarshal([]byte(line), &env); err != nil {
			t.Fatalf("get json: %v", err)
		}
		if env.Done {
			break
		}
		envs = append(envs, env)
	}
	if ackIDs == nil {
		for _, e := range envs {
			ackIDs = append(ackIDs, e.ID)
		}
	}
	ack, _ := json.Marshal(mbxAck{Ack: ackIDs})
	fmt.Fprintf(s, "%s\n", ack)
	_ = s.CloseWrite()
	return envs
}

// TestMailboxStoreAndForward valida el ciclo completo: A deposita para B (offline),
// B retira autenticado por su identidad de stream, ack'ea y el nodo borra; sin ack se
// reentrega (dedup en cliente por id); el sobre lleva el remitente fijado por el nodo.
func TestMailboxStoreAndForward(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	mbx := newMailbox(t.TempDir())
	mbx.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	msg := []byte("ciphertext opaco para B")
	if err := mbxPut(t, a, node.ID(), b.ID().String(), msg); err != nil {
		t.Fatalf("put: %v", err)
	}

	// A no puede leer el buzón de B: su GET (autenticado como A) llega vacío.
	if got := mbxGet(t, a, node.ID(), nil); len(got) != 0 {
		t.Fatalf("A no debería ver el buzón de B, obtuvo %d sobres", len(got))
	}

	// B retira SIN ack'ear: el mensaje debe seguir disponible (reentrega).
	got := mbxGet(t, b, node.ID(), []string{})
	if len(got) != 1 || string(mustB64(t, got[0].Blob)) != string(msg) {
		t.Fatalf("primer get: %+v", got)
	}
	if got[0].From != a.ID().String() {
		t.Fatalf("from suplantable: %s != %s", got[0].From, a.ID())
	}

	// Segundo GET con ack: retira y borra.
	got = mbxGet(t, b, node.ID(), nil)
	if len(got) != 1 {
		t.Fatalf("reentrega esperada, obtuve %d", len(got))
	}
	if got = mbxGet(t, b, node.ID(), nil); len(got) != 0 {
		t.Fatalf("tras el ack el buzón debe quedar vacío, obtuve %d", len(got))
	}
}

// TestMailboxQuotaAndTTL valida la cuota por destinatario y el barrido por TTL.
func TestMailboxQuotaAndTTL(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	mbx := newMailbox(dir)
	mbx.maxMsgs = 2
	mbx.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	to := b.ID().String()
	for i := 0; i < 2; i++ {
		if err := mbxPut(t, a, node.ID(), to, []byte("x")); err != nil {
			t.Fatalf("put %d: %v", i, err)
		}
	}
	if err := mbxPut(t, a, node.ID(), to, []byte("x")); err == nil ||
		!strings.Contains(err.Error(), "lleno") {
		t.Fatalf("la cuota debía rechazar el tercer put, err=%v", err)
	}

	// Un blob demasiado grande también se rechaza.
	if err := mbxPut(t, a, node.ID(), to, make([]byte, mbx.maxBlob+1)); err == nil {
		t.Fatal("blob > maxBlob debía rechazarse")
	}

	// TTL: envejecer los archivos por mtime y barrer.
	old := time.Now().Add(-mbx.ttl - time.Hour)
	files, _ := os.ReadDir(filepath.Join(dir, to))
	for _, f := range files {
		p := filepath.Join(dir, to, f.Name())
		if err := os.Chtimes(p, old, old); err != nil {
			t.Fatalf("chtimes: %v", err)
		}
	}
	mbx.sweep()
	if got := mbxGet(t, b, node.ID(), nil); len(got) != 0 {
		t.Fatalf("tras el TTL el buzón debe quedar vacío, obtuve %d", len(got))
	}
}

func mustB64(t *testing.T, s string) []byte {
	t.Helper()
	data, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatalf("b64: %v", err)
	}
	return data
}
