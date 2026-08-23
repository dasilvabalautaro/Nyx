// Denuncias (plan 3.13 / 4.4) y expulsión del tablón.
//
// Es la pieza que hace que Nyx cumpla la política de contenido generado por usuarios de Play
// **sin** montar un servidor de contenido. La política exige dos cosas: que exista un sistema
// dentro de la app para denunciar y bloquear, y que el desarrollador **pueda actuar** sobre lo
// denunciado. Lo primero es UI; lo segundo necesita que la denuncia llegue a alguien y que ese
// alguien tenga una palanca. Esto es esa palanca.
//
// # Qué ve el nodo, y qué no
//
// La denuncia llega como un **sobre cifrado a la clave pública del operador**, así que el nodo
// almacena bytes opacos: ni quien opera la caja ni quien la comprometa puede leer denuncias en
// reposo. Solo puede quien tenga la clave privada, que vive fuera del nodo.
//
// Lo que el nodo **sí** ve, y conviene no fingir lo contrario, es **quién denuncia**: el PeerID
// del denunciante sale de la identidad del stream de libp2p y no se puede ocultar. Se guarda a
// propósito — sin él no hay forma de limitar a quien inunde de denuncias falsas.
//
// # Cómo se recogen, y por qué no hay protocolo para eso
//
// No hay `/nyx/report/fetch`. El operador entra por SSH a su propia caja y lee `reportdir/`.
// Un protocolo de recogida necesitaría autenticar al operador (otra identidad, otro secreto,
// otra superficie que puede fallar abierta) para resolver un problema que SSH ya resuelve. Con
// una caja y un operador, el protocolo sería complejidad sin ganancia.
//
// # Expulsión
//
// Misma idea: la lista de expulsados es un **fichero de texto**, un PeerID por línea, que el
// operador edita por SSH. El tablón lo consulta al publicar **y al consultar**, esto último es
// lo que importa — si solo se mirara al publicar, expulsar a alguien no retiraría la tarjeta que
// ya está puesta, y "actuar sobre lo denunciado" se quedaría en un gesto. El barrido borra
// además sus tarjetas, para que la expulsión libere espacio y no solo esconda.
//
// Protocolo (JSON por líneas, mismo estilo que mailbox.go y like.go):
//
//	/nyx/report/1.0.0  cliente → {"v":1,"blob":"<b64>"}\n
//	                      nodo → {"ok":true} | {"err":"…"}
package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const (
	reportProtocol = protocol.ID("/nyx/report/1.0.0")

	// reportDefaultTTL: mucho más largo que el del buzón (7 días) y que el de los likes
	// (30). Una denuncia es justo lo que no debe caducar antes de que alguien la mire: el
	// operador puede tardar, y borrarla sola sería perder la prueba. 180 días es el
	// compromiso entre eso y no guardar para siempre datos de una persona.
	reportDefaultTTL = 180 * 24 * time.Hour

	// reportDefaultMaxBlob: la denuncia lleva PeerID denunciado, nota libre y un **fragmento
	// de conversación**, así que necesita bastante más sitio que un like (4 KiB). 64 KiB es
	// el mismo tope que un sobre del buzón: suficiente para un fragmento largo, y lejos de
	// permitir subir un archivo disfrazado de denuncia.
	reportDefaultMaxBlob = 64 << 10

	// reportDefaultMaxPerReporter: denuncias vivas de un mismo denunciante. Aquí NO se
	// sobreescribe como en los likes, porque dos denuncias del mismo usuario son dos hechos
	// distintos y perder la primera sería perder una prueba. El precio es que el tope tiene
	// que existir: sin él, una identidad podría llenar el disco. 50 es holgado para un uso
	// legítimo — quien necesite denunciar 50 veces tiene un problema que se resuelve
	// bloqueando, no denunciando más.
	reportDefaultMaxPerReporter = 50
)

type reports struct {
	dir            string
	ttl            time.Duration
	maxBlob        int
	maxPerReporter int
	mu             sync.Mutex
}

type reportReq struct {
	V    int    `json:"v"`
	Blob string `json:"blob"` // base64; cifrado a la clave del operador, opaco para el nodo
}

// reportRecord es lo que queda en disco. `From` lo fija el nodo desde la identidad del stream,
// nunca desde la petición: igual que el remitente del buzón, no es suplantable.
type reportRecord struct {
	From string `json:"from"`
	Ts   int64  `json:"ts"`
	Blob string `json:"blob"`
}

func newReports(dir string) *reports {
	return &reports{
		dir:            dir,
		ttl:            reportDefaultTTL,
		maxBlob:        reportDefaultMaxBlob,
		maxPerReporter: reportDefaultMaxPerReporter,
	}
}

func (r *reports) attach(h host.Host) {
	h.SetStreamHandler(reportProtocol, r.handleReport)
}

func (r *reports) handleReport(s network.Stream) {
	defer s.Close()
	reply := replier(s)

	// 64 KiB de sobre → ~88 KiB en base64; 128 KiB deja sitio al JSON que lo envuelve.
	line, err := readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req reportReq
	if err := json.Unmarshal(line, &req); err != nil {
		reply("JSON inválido: " + err.Error())
		return
	}
	blob, err := base64.StdEncoding.DecodeString(req.Blob)
	if err != nil {
		reply("denuncia no es base64: " + err.Error())
		return
	}
	if len(blob) == 0 {
		reply("denuncia vacía")
		return
	}
	if len(blob) > r.maxBlob {
		reply(fmt.Sprintf("denuncia demasiado grande (máx %d KiB)", r.maxBlob>>10))
		return
	}

	from := s.Conn().RemotePeer().String() // identidad verificada, no suplantable
	if err := r.store(from, req.Blob); err != nil {
		reply(err.Error())
		return
	}
	reply("")
}

func (r *reports) store(from, blob string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	dir := filepath.Join(r.dir, sanitizePeer(from))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("no se pudo guardar: %w", err)
	}
	existing, err := os.ReadDir(dir)
	if err == nil && len(existing) >= r.maxPerReporter {
		return fmt.Errorf("demasiadas denuncias pendientes (máx %d)", r.maxPerReporter)
	}

	now := time.Now()
	record, err := json.Marshal(reportRecord{From: from, Ts: now.UnixMilli(), Blob: blob})
	if err != nil {
		return fmt.Errorf("no se pudo serializar: %w", err)
	}
	// Nombre por marca de tiempo con nanosegundos: dos denuncias seguidas del mismo usuario
	// no deben pisarse (ver el comentario de maxPerReporter: cada una es un hecho distinto).
	name := fmt.Sprintf("%d.json", now.UnixNano())
	tmp := filepath.Join(dir, name+".tmp")
	if err := os.WriteFile(tmp, record, 0o600); err != nil {
		return fmt.Errorf("no se pudo escribir: %w", err)
	}
	return os.Rename(tmp, filepath.Join(dir, name))
}

// sweep borra las denuncias más viejas que el TTL y los directorios que quedan vacíos.
func (r *reports) sweep() {
	r.mu.Lock()
	defer r.mu.Unlock()
	cutoff := time.Now().Add(-r.ttl).UnixMilli()
	reporters, err := os.ReadDir(r.dir)
	if err != nil {
		return
	}
	for _, rep := range reporters {
		if !rep.IsDir() {
			continue
		}
		dir := filepath.Join(r.dir, rep.Name())
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
			var rec reportRecord
			if err := json.Unmarshal(data, &rec); err != nil || rec.Ts < cutoff {
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

// sanitizePeer evita que un PeerID raro se convierta en una ruta: es un nombre de directorio,
// igual que la categoría del tablón, y sin esto un "../.." se escaparía del reportdir. Los
// PeerID reales son base58 (alfanumérico), así que esto no toca ninguno legítimo.
func sanitizePeer(peer string) string {
	safe := strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
			return r
		default:
			return '_'
		}
	}, peer)
	if len(safe) > 96 {
		safe = safe[:96]
	}
	if safe == "" {
		safe = "desconocido"
	}
	return safe
}

// --- Expulsión del tablón --------------------------------------------------------------

// banlist es la lista de PeerID expulsados: un fichero de texto, un PeerID por línea, que el
// operador edita por SSH. Las líneas vacías y las que empiezan por '#' se ignoran, para poder
// anotar por qué se expulsó a alguien junto a su PeerID.
//
// Se relee cuando cambia el mtime del fichero, no en cada consulta: el tablón lo pregunta una
// vez por tarjeta y leer el fichero cada vez haría del disco el cuello de botella de una
// consulta grande.
type banlist struct {
	path   string
	mu     sync.RWMutex
	loaded time.Time
	set    map[string]bool
}

func newBanlist(path string) *banlist {
	return &banlist{path: path, set: map[string]bool{}}
}

func (b *banlist) isBanned(peer string) bool {
	if b == nil || b.path == "" {
		return false
	}
	b.reloadIfChanged()
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.set[peer]
}

func (b *banlist) reloadIfChanged() {
	info, err := os.Stat(b.path)
	if err != nil {
		// Sin fichero no hay expulsados. Es el caso normal, no un error.
		b.mu.Lock()
		if len(b.set) > 0 {
			b.set = map[string]bool{}
		}
		b.mu.Unlock()
		return
	}
	b.mu.RLock()
	fresh := info.ModTime().Equal(b.loaded)
	b.mu.RUnlock()
	if fresh {
		return
	}

	f, err := os.Open(b.path)
	if err != nil {
		return
	}
	defer f.Close()
	set := map[string]bool{}
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		// Permite "PeerID  # motivo" en la misma línea.
		if i := strings.IndexAny(line, " \t#"); i > 0 {
			line = strings.TrimSpace(line[:i])
		}
		set[line] = true
	}
	b.mu.Lock()
	b.set = set
	b.loaded = info.ModTime()
	b.mu.Unlock()
}

// count es para el mensaje de arranque: decir cuántos expulsados hay evita la duda de si el
// fichero se está leyendo de verdad.
func (b *banlist) count() int {
	if b == nil || b.path == "" {
		return 0
	}
	b.reloadIfChanged()
	b.mu.RLock()
	defer b.mu.RUnlock()
	return len(b.set)
}
