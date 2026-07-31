// Buzón E2EE store-and-forward (Fase 1/4): el emisor deposita blobs cifrados para un
// destinatario offline y este los retira al conectarse. El nodo NUNCA puede descifrar:
// solo guarda ciphertext opaco, con TTL y cuotas anti-abuso.
//
// Protocolo (JSON por líneas sobre streams libp2p, interop v0.38 nodo ↔ v0.48 móviles):
//
//	/krypta/mbx/put/1.0.0  cliente → {"v":1,"to":"<peerid>","blob":"<b64>"}\n
//	                       nodo    → {"ok":true} | {"err":"…"}
//	/krypta/mbx/get/1.0.0  nodo    → {"id","from","ts","blob"}\n … {"done":true}\n
//	                       cliente → {"ack":["id",…]}\n   (el nodo borra solo lo ack'eado)
//
// Autenticación gratis por libp2p: en GET solo se entregan los blobs cuyo `to` es el
// PeerID remoto del stream, y en PUT el `from` del sobre lo fija el nodo desde el stream
// (no se puede suplantar al remitente). Si el ack se pierde, el mensaje se reentrega y el
// cliente deduplica por `id`.
package main

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const (
	mbxPutProtocol = protocol.ID("/krypta/mbx/put/1.0.0")
	mbxGetProtocol = protocol.ID("/krypta/mbx/get/1.0.0")
)

type mailbox struct {
	dir      string
	ttl      time.Duration
	maxBlob  int   // bytes de ciphertext por mensaje
	maxMsgs  int   // mensajes pendientes por destinatario
	maxBytes int64 // bytes pendientes por destinatario
	mu       sync.Mutex

	// notify (opcional) se invoca tras cada depósito con el PeerID del destinatario —
	// el wake integrado (wake.go) le avisa al instante si está suscrito.
	notify func(to string)
}

type mbxPutReq struct {
	V    int    `json:"v"`
	To   string `json:"to"`
	Blob string `json:"blob"`
}

type mbxEnvelope struct {
	ID   string `json:"id"`
	From string `json:"from"`
	Ts   int64  `json:"ts"` // unix millis del depósito
	Blob string `json:"blob"`
	Done bool   `json:"done,omitempty"`
}

type mbxAck struct {
	Ack []string `json:"ack"`
}

func newMailbox(dir string) *mailbox {
	return &mailbox{
		dir:      dir,
		ttl:      7 * 24 * time.Hour,
		maxBlob:  64 << 10,
		maxMsgs:  200,
		maxBytes: 5 << 20,
	}
}

// attach registra los handlers del buzón en el host.
func (m *mailbox) attach(h host.Host) {
	h.SetStreamHandler(mbxPutProtocol, m.handlePut)
	h.SetStreamHandler(mbxGetProtocol, m.handleGet)
}

func (m *mailbox) handlePut(s network.Stream) {
	defer s.Close()
	reply := func(errMsg string) {
		if errMsg == "" {
			fmt.Fprintln(s, `{"ok":true}`)
		} else {
			out, _ := json.Marshal(map[string]string{"err": errMsg})
			fmt.Fprintf(s, "%s\n", out)
		}
	}

	// Límite de lectura: blob de 64 KiB → ~87 KiB en base64 + el sobre JSON.
	line, err := readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req mbxPutReq
	if err := json.Unmarshal(line, &req); err != nil {
		reply("JSON inválido: " + err.Error())
		return
	}
	to, err := peer.Decode(req.To)
	if err != nil {
		reply("destinatario inválido: " + err.Error())
		return
	}
	blob, err := base64.StdEncoding.DecodeString(req.Blob)
	if err != nil {
		reply("blob no es base64: " + err.Error())
		return
	}
	if len(blob) == 0 || len(blob) > m.maxBlob {
		reply(fmt.Sprintf("blob fuera de límite (1..%d bytes)", m.maxBlob))
		return
	}

	env := mbxEnvelope{
		ID:   newMbxID(),
		From: s.Conn().RemotePeer().String(), // identidad verificada del stream, no suplantable
		Ts:   time.Now().UnixMilli(),
		Blob: req.Blob,
	}
	if err := m.store(to.String(), env); err != nil {
		reply(err.Error())
		return
	}
	reply("")
	if m.notify != nil {
		m.notify(to.String())
	}
}

func (m *mailbox) handleGet(s network.Stream) {
	defer s.Close()
	// Solo se entrega el buzón del peer autenticado en el stream.
	to := s.Conn().RemotePeer().String()

	envs, err := m.list(to)
	if err != nil {
		return
	}
	w := bufio.NewWriter(s)
	for _, env := range envs {
		out, err := json.Marshal(env)
		if err != nil {
			continue
		}
		fmt.Fprintf(w, "%s\n", out)
	}
	fmt.Fprintln(w, `{"done":true}`)
	if err := w.Flush(); err != nil {
		return
	}

	// El cliente confirma lo procesado; solo eso se borra (si el ack se pierde, reentrega).
	line, err := readLine(io.LimitReader(s, 64<<10))
	if err != nil {
		return
	}
	var ack mbxAck
	if err := json.Unmarshal(line, &ack); err != nil {
		return
	}
	m.delete(to, ack.Ack)
}

// store guarda el sobre en mailboxdir/<to>/<id>.json aplicando cuotas por destinatario.
func (m *mailbox) store(to string, env mbxEnvelope) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	dir := filepath.Join(m.dir, to)
	count, size := 0, int64(0)
	if files, err := os.ReadDir(dir); err == nil {
		for _, f := range files {
			if info, err := f.Info(); err == nil {
				count++
				size += info.Size()
			}
		}
	}
	if count >= m.maxMsgs || size >= m.maxBytes {
		return fmt.Errorf("buzón del destinatario lleno (%d msgs, %d bytes)", count, size)
	}
	data, err := json.Marshal(env)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, env.ID+".json"), data, 0o600)
}

// list devuelve los sobres pendientes de un destinatario en orden de depósito.
func (m *mailbox) list(to string) ([]mbxEnvelope, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	dir := filepath.Join(m.dir, to)
	files, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(files))
	for _, f := range files {
		if strings.HasSuffix(f.Name(), ".json") {
			names = append(names, f.Name())
		}
	}
	sort.Strings(names) // el id empieza por unix-nano con ceros → orden cronológico
	var envs []mbxEnvelope
	for _, name := range names {
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		var env mbxEnvelope
		if err := json.Unmarshal(data, &env); err == nil {
			envs = append(envs, env)
		}
	}
	return envs, nil
}

// delete borra los sobres ack'eados de un destinatario (nunca fuera de su directorio).
func (m *mailbox) delete(to string, ids []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	dir := filepath.Join(m.dir, to)
	for _, id := range ids {
		if id == "" || id != filepath.Base(id) || strings.Contains(id, "..") {
			continue
		}
		_ = os.Remove(filepath.Join(dir, id+".json"))
	}
}

// sweep borra los sobres más viejos que el TTL (por mtime) y los buzones vacíos.
func (m *mailbox) sweep() {
	m.mu.Lock()
	defer m.mu.Unlock()
	cutoff := time.Now().Add(-m.ttl)
	boxes, err := os.ReadDir(m.dir)
	if err != nil {
		return
	}
	for _, box := range boxes {
		if !box.IsDir() {
			continue
		}
		dir := filepath.Join(m.dir, box.Name())
		files, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		remaining := 0
		for _, f := range files {
			info, err := f.Info()
			if err == nil && info.ModTime().Before(cutoff) {
				_ = os.Remove(filepath.Join(dir, f.Name()))
			} else {
				remaining++
			}
		}
		if remaining == 0 {
			_ = os.Remove(dir)
		}
	}
}

// newMbxID genera un id único ordenable cronológicamente: unix-nano con ceros + azar.
func newMbxID() string {
	var r [4]byte
	_, _ = rand.Read(r[:])
	return fmt.Sprintf("%020d-%x", time.Now().UnixNano(), r)
}

// readLine lee hasta '\n' o EOF (los clientes pueden cerrar la escritura sin salto final).
func readLine(r io.Reader) ([]byte, error) {
	line, err := bufio.NewReader(r).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return nil, err
	}
	return line, nil
}
