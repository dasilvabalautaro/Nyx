// Entrega de "me gusta" (Fase 3). Cifrado extremo a extremo como el buzón —el nodo solo ve
// bytes opacos—, pero **con almacenamiento y cuota propios**, y esa separación es el punto
// entero de este fichero.
//
// El problema que resuelve: publicar una tarjeta en el tablón hace tu PeerID público. Si los
// Likes viajaran por el buzón normal, cualquiera podría llenar tu cuota de buzón
// (200 mensajes / 5 MiB por destinatario) a base de Likes y **bloquear la entrega de tus
// mensajes reales**. El mecanismo anti-acoso se convertiría en un DoS de mensajería. Con un
// bucket propio, un tablón inundado de Likes no le quita ni un byte al buzón.
//
// La segunda mitad de la mitigación es la clave de almacenamiento: `likedir/<to>/<from>.json`,
// **un Like vivo por emisor y destinatario**. Insistir no acumula, sobreescribe — igual que
// una tarjeta del tablón. Así el coste que un abusador puede imponer no crece con el número
// de intentos, solo con el número de identidades distintas que se moleste en crear.
//
// Protocolo (JSON por líneas, mismo estilo que mailbox.go):
//
//	/nyx/like/put/1.0.0  cliente → {"v":1,"to":"<peerid>","blob":"<b64>"}\n
//	                        nodo → {"ok":true} | {"err":"…"}
//	/nyx/like/get/1.0.0     nodo → {"from","ts","blob"}\n … {"done":true}\n
//	                     cliente → {"ack":["<from>",…]}\n   (el nodo borra solo lo ack'eado)
package main

import (
	"bufio"
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
	likePutProtocol = protocol.ID("/nyx/like/put/1.0.0")
	likeGetProtocol = protocol.ID("/nyx/like/get/1.0.0")

	// likeDefaultTTL: más largo que el del buzón (7 días) a propósito. Un Like caducado es un
	// match que nunca llega a formarse, y el destinatario puede tardar en volver a abrir la
	// app; 30 días es holgado sin que el tablón acumule para siempre.
	likeDefaultTTL = 30 * 24 * time.Hour

	// likeDefaultMaxBlob: un sobre `L` cifrado son unas decenas de bytes. 4 KiB es ya
	// generosísimo, y mantenerlo bajo es parte de la defensa: aquí no cabe una carga útil.
	likeDefaultMaxBlob = 4 << 10

	// likeDefaultMaxPending: emisores distintos con un Like pendiente para un mismo
	// destinatario. Con sobreescritura por emisor, esto es el techo real de lo que ocupa un
	// destinatario: 500 * 4 KiB ≈ 2 MiB en el peor caso.
	likeDefaultMaxPending = 500
)

type likebox struct {
	dir        string
	ttl        time.Duration
	maxBlob    int
	maxPending int
	mu         sync.Mutex

	// notify (opcional) avisa al destinatario por el wake, igual que hace el buzón.
	notify func(to string)
}

type likePutReq struct {
	V    int    `json:"v"`
	To   string `json:"to"`
	Blob string `json:"blob"`
}

type likeEnvelope struct {
	From string `json:"from"` // lo fija el nodo desde el stream: no suplantable
	Ts   int64  `json:"ts"`
	Blob string `json:"blob"`
	Done bool   `json:"done,omitempty"`
}

type likeAck struct {
	Ack []string `json:"ack"` // PeerIDs de emisor, que son la clave de almacenamiento
}

func newLikebox(dir string) *likebox {
	return &likebox{
		dir:        dir,
		ttl:        likeDefaultTTL,
		maxBlob:    likeDefaultMaxBlob,
		maxPending: likeDefaultMaxPending,
	}
}

func (l *likebox) attach(h host.Host) {
	h.SetStreamHandler(likePutProtocol, l.handlePut)
	h.SetStreamHandler(likeGetProtocol, l.handleGet)
}

func (l *likebox) handlePut(s network.Stream) {
	defer s.Close()
	reply := replier(s)

	line, err := readLine(io.LimitReader(s, 16<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req likePutReq
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
	if len(blob) == 0 || len(blob) > l.maxBlob {
		reply(fmt.Sprintf("blob fuera de límite (1..%d bytes)", l.maxBlob))
		return
	}

	from := s.Conn().RemotePeer().String()
	if err := l.store(to.String(), likeEnvelope{From: from, Ts: time.Now().UnixMilli(), Blob: req.Blob}); err != nil {
		reply(err.Error())
		return
	}
	reply("")
	if l.notify != nil {
		l.notify(to.String())
	}
}

func (l *likebox) handleGet(s network.Stream) {
	defer s.Close()
	// Solo se entregan los Likes del peer autenticado en el stream.
	to := s.Conn().RemotePeer().String()

	envs := l.list(to)
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

	line, err := readLine(io.LimitReader(s, 64<<10))
	if err != nil {
		return
	}
	var ack likeAck
	if err := json.Unmarshal(line, &ack); err != nil {
		return
	}
	l.delete(to, ack.Ack)
}

// store guarda likedir/<to>/<from>.json. Sobreescribe si ese emisor ya tenía uno pendiente,
// así que la cuota solo se consulta cuando el emisor es nuevo para ese destinatario.
func (l *likebox) store(to string, env likeEnvelope) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	dir := filepath.Join(l.dir, to)
	path := filepath.Join(dir, env.From+".json")

	if _, err := os.Stat(path); os.IsNotExist(err) {
		if n := countJSON(dir); n >= l.maxPending {
			return fmt.Errorf("bandeja de likes llena (%d pendientes)", n)
		}
	}
	data, err := json.Marshal(env)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// list devuelve los Likes vivos de un destinatario, del más reciente al más antiguo.
func (l *likebox) list(to string) []likeEnvelope {
	l.mu.Lock()
	defer l.mu.Unlock()
	dir := filepath.Join(l.dir, to)
	files, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	cutoff := time.Now().Add(-l.ttl).UnixMilli()
	envs := make([]likeEnvelope, 0, len(files))
	for _, f := range files {
		if !strings.HasSuffix(f.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, f.Name()))
		if err != nil {
			continue
		}
		var env likeEnvelope
		if err := json.Unmarshal(data, &env); err != nil || env.Ts < cutoff {
			continue
		}
		envs = append(envs, env)
	}
	sort.Slice(envs, func(i, j int) bool { return envs[i].Ts > envs[j].Ts })
	return envs
}

// delete borra los Likes ack'eados. La clave es el PeerID del emisor, que llega del cliente,
// así que se valida que sea un nombre de fichero y no una ruta (mismo cuidado que el buzón).
func (l *likebox) delete(to string, from []string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	dir := filepath.Join(l.dir, to)
	for _, id := range from {
		if id == "" || id != filepath.Base(id) || strings.Contains(id, "..") {
			continue
		}
		_ = os.Remove(filepath.Join(dir, id+".json"))
	}
}

// sweep borra los Likes caducados (por el `ts` de dentro) y las bandejas vacías.
func (l *likebox) sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	cutoff := time.Now().Add(-l.ttl).UnixMilli()
	boxes, err := os.ReadDir(l.dir)
	if err != nil {
		return
	}
	for _, box := range boxes {
		if !box.IsDir() {
			continue
		}
		dir := filepath.Join(l.dir, box.Name())
		files, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		remaining := 0
		for _, f := range files {
			path := filepath.Join(dir, f.Name())
			data, err := os.ReadFile(path)
			if err != nil {
				continue
			}
			var env likeEnvelope
			if err := json.Unmarshal(data, &env); err != nil || env.Ts < cutoff {
				_ = os.Remove(path)
				continue
			}
			remaining++
		}
		if remaining == 0 {
			_ = os.Remove(dir)
		}
	}
}

func countJSON(dir string) int {
	files, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, f := range files {
		if strings.HasSuffix(f.Name(), ".json") {
			n++
		}
	}
	return n
}
