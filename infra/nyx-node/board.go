// Tablón de tarjetas de perfil (Fase 3). Al revés que el buzón, aquí el contenido va **en
// claro**: ser descubrible es justo el punto. El nodo no lo interpreta —guarda bytes opacos—
// así que el formato de la tarjeta puede evolucionar en el cliente sin tocar el nodo.
//
// Protocolo (JSON por líneas sobre streams libp2p, mismo estilo que mailbox.go):
//
//	/nyx/board/publish/1.0.0  cliente → {"v":1,"cat":"<categoría>","card":"<b64>"}\n
//	                             nodo → {"ok":true} | {"err":"…"}
//	/nyx/board/query/1.0.0    cliente → {"v":1,"cat":"<categoría>","limit":50}\n
//	                             nodo → {"peer","ts","card"}\n … {"done":true}\n
//	/nyx/board/delete/1.0.0   cliente → {"v":1,"cat":"<categoría>"}\n  (cat vacía = todas)
//	                             nodo → {"ok":true}
//
// Dos propiedades que salen gratis de libp2p y sostienen el diseño:
//
//   - El **autor** de una tarjeta lo fija el nodo desde la identidad del stream, así que no se
//     puede publicar en nombre de otro.
//   - Por eso mismo `delete` solo puede borrar lo tuyo: la ruta se construye con el PeerID
//     autenticado, nunca con uno que venga en la petición.
//
// Almacenamiento `boarddir/<categoría>/<peerId>.json`: **una tarjeta activa por autor y
// categoría, que se sobreescribe al republicar**. No es append-only como el buzón, y eso
// limita el flood de una sola identidad sin necesidad de cuota por autor. Como la tarjeta no
// es anónima —quien la lee necesita el PeerID de todas formas para poder mandar un Like— no
// hace falta más.
package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const (
	boardPublishProtocol = protocol.ID("/nyx/board/publish/1.0.0")
	boardQueryProtocol   = protocol.ID("/nyx/board/query/1.0.0")
	boardDeleteProtocol  = protocol.ID("/nyx/board/delete/1.0.0")

	// boardDefaultTTL: una tarjeta caduca sola. 48h es el valor recomendado para el beta —
	// corto a propósito, para que el tablón refleje a quien sigue activo y para que una
	// tarjeta olvidada no quede publicada indefinidamente.
	boardDefaultTTL = 48 * time.Hour

	// boardDefaultMaxCard: el avatar va hasta 58 KiB (mismo tope que las imágenes en línea
	// del chat) más el texto; 96 KiB deja holgura sin invitar a usar el tablón de almacén.
	boardDefaultMaxCard = 96 << 10

	// boardDefaultMaxCards: tarjetas vivas por categoría. Al ser una por autor, esto es
	// también el número de autores distintos que caben en una categoría.
	boardDefaultMaxCards = 5000

	// boardDefaultQueryLimit / boardMaxQueryLimit: cuántas tarjetas devuelve una consulta.
	boardDefaultQueryLimit = 50
	boardMaxQueryLimit     = 200
)

// categoryPattern es deliberadamente estricto: la categoría se usa como **nombre de
// directorio**, así que cualquier cosa que no sea de este alfabeto (empezando por `.` y `/`)
// sería una vía de escape del boarddir.
var categoryPattern = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)

type board struct {
	dir      string
	bans     *banlist // expulsados; nil = sin expulsión (tests que no la ejercitan)
	ttl      time.Duration
	maxCard  int // bytes de tarjeta
	maxCards int // tarjetas vivas por categoría
	mu       sync.Mutex
}

type boardPublishReq struct {
	V    int    `json:"v"`
	Cat  string `json:"cat"`
	Card string `json:"card"` // base64; el nodo no lo interpreta
}

type boardQueryReq struct {
	V     int    `json:"v"`
	Cat   string `json:"cat"`
	Limit int    `json:"limit"`
}

type boardDeleteReq struct {
	V   int    `json:"v"`
	Cat string `json:"cat"` // vacía = borrar mis tarjetas de todas las categorías
}

// boardCard es lo que se guarda y lo que se devuelve en una consulta.
type boardCard struct {
	Peer string `json:"peer"` // autor, fijado por el nodo desde el stream
	Ts   int64  `json:"ts"`   // unix millis de la publicación
	Card string `json:"card"` // base64 opaco
	Done bool   `json:"done,omitempty"`
}

func newBoard(dir string) *board {
	return &board{
		dir:      dir,
		ttl:      boardDefaultTTL,
		maxCard:  boardDefaultMaxCard,
		maxCards: boardDefaultMaxCards,
	}
}

func (b *board) attach(h host.Host) {
	h.SetStreamHandler(boardPublishProtocol, b.handlePublish)
	h.SetStreamHandler(boardQueryProtocol, b.handleQuery)
	h.SetStreamHandler(boardDeleteProtocol, b.handleDelete)
}

func (b *board) handlePublish(s network.Stream) {
	defer s.Close()
	reply := replier(s)

	// Tarjeta de 96 KiB → ~128 KiB en base64; 192 KiB deja sitio al sobre JSON.
	line, err := readLine(io.LimitReader(s, 192<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req boardPublishReq
	if err := json.Unmarshal(line, &req); err != nil {
		reply("JSON inválido: " + err.Error())
		return
	}
	if !categoryPattern.MatchString(req.Cat) {
		reply("categoría inválida (a-z, 0-9, _ y -, hasta 32)")
		return
	}
	card, err := base64.StdEncoding.DecodeString(req.Card)
	if err != nil {
		reply("tarjeta no es base64: " + err.Error())
		return
	}
	if len(card) == 0 || len(card) > b.maxCard {
		reply(fmt.Sprintf("tarjeta fuera de límite (1..%d bytes)", b.maxCard))
		return
	}

	author := s.Conn().RemotePeer().String() // identidad verificada, no suplantable
	if b.bans.isBanned(author) {
		// Mensaje deliberadamente escueto: no se le explica al expulsado por qué ni desde
		// cuándo. Basta con que no pueda publicar.
		reply("no puedes publicar en el tablón")
		return
	}
	if err := b.store(req.Cat, author, req.Card); err != nil {
		reply(err.Error())
		return
	}
	reply("")
}

func (b *board) handleQuery(s network.Stream) {
	defer s.Close()

	line, err := readLine(io.LimitReader(s, 8<<10))
	if err != nil {
		return
	}
	var req boardQueryReq
	if err := json.Unmarshal(line, &req); err != nil {
		return
	}
	if !categoryPattern.MatchString(req.Cat) {
		return
	}
	limit := req.Limit
	if limit <= 0 {
		limit = boardDefaultQueryLimit
	}
	if limit > boardMaxQueryLimit {
		limit = boardMaxQueryLimit
	}

	cards := b.list(req.Cat, limit)
	w := bufio.NewWriter(s)
	for _, c := range cards {
		out, err := json.Marshal(c)
		if err != nil {
			continue
		}
		fmt.Fprintf(w, "%s\n", out)
	}
	fmt.Fprintln(w, `{"done":true}`)
	_ = w.Flush()
}

// handleDelete quita la tarjeta del autor. Es la mitad que faltaba para poder "desaparecer
// del tablón" sin esperar al TTL — con TTL de 48h, sin esto quitarse el perfil significaría
// seguir siendo visible dos días, que además de mal producto es justo el tipo de control que
// exige el RGPD.
func (b *board) handleDelete(s network.Stream) {
	defer s.Close()
	reply := replier(s)

	line, err := readLine(io.LimitReader(s, 8<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req boardDeleteReq
	if err := json.Unmarshal(line, &req); err != nil {
		reply("JSON inválido: " + err.Error())
		return
	}
	if req.Cat != "" && !categoryPattern.MatchString(req.Cat) {
		reply("categoría inválida")
		return
	}
	b.delete(req.Cat, s.Conn().RemotePeer().String())
	reply("")
}

// store escribe boarddir/<cat>/<autor>.json, sobreescribiendo la tarjeta anterior del autor.
// La cuota de categoría solo frena a autores **nuevos**: republicar no añade ficheros, así
// que a quien ya está publicado nunca se le rechaza una actualización por cuota.
func (b *board) store(cat, author, cardB64 string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	dir := filepath.Join(b.dir, cat)
	path := filepath.Join(dir, author+".json")

	if _, err := os.Stat(path); os.IsNotExist(err) {
		if n := countJSON(dir); n >= b.maxCards {
			return fmt.Errorf("categoría llena (%d tarjetas)", n)
		}
	}
	data, err := json.Marshal(boardCard{
		Peer: author,
		Ts:   time.Now().UnixMilli(),
		Card: cardB64,
	})
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// tmp + rename: una consulta concurrente nunca ve una tarjeta a medio escribir.
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// list devuelve hasta `limit` tarjetas vivas de la categoría, de la más reciente a la más
// antigua. Las caducadas se saltan aunque el barrido aún no haya pasado, para que el TTL sea
// una garantía y no "lo que quede tras el próximo sweep".
func (b *board) list(cat string, limit int) []boardCard {
	b.mu.Lock()
	defer b.mu.Unlock()
	dir := filepath.Join(b.dir, cat)
	files, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	cutoff := time.Now().Add(-b.ttl).UnixMilli()
	cards := make([]boardCard, 0, len(files))
	for _, f := range files {
		if !strings.HasSuffix(f.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, f.Name()))
		if err != nil {
			continue
		}
		var c boardCard
		if err := json.Unmarshal(data, &c); err != nil || c.Ts < cutoff {
			continue
		}
		// La expulsión se aplica **al leer**, no solo al publicar. Sin esto, expulsar a
		// alguien no retiraría la tarjeta que ya tiene puesta y "actuar sobre lo denunciado"
		// se quedaría en un gesto hasta que caducara sola (48 h). El barrido la borra
		// después; esto la hace invisible ya.
		if b.bans.isBanned(c.Peer) {
			continue
		}
		cards = append(cards, c)
	}
	sort.Slice(cards, func(i, j int) bool { return cards[i].Ts > cards[j].Ts })
	if len(cards) > limit {
		cards = cards[:limit]
	}
	return cards
}

// delete borra las tarjetas del autor: la de `cat`, o las de todas si `cat` está vacía.
// La ruta se construye siempre con el PeerID autenticado del stream.
func (b *board) delete(cat, author string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if cat != "" {
		_ = os.Remove(filepath.Join(b.dir, cat, author+".json"))
		return
	}
	cats, err := os.ReadDir(b.dir)
	if err != nil {
		return
	}
	for _, c := range cats {
		if c.IsDir() {
			_ = os.Remove(filepath.Join(b.dir, c.Name(), author+".json"))
		}
	}
}

// sweep borra las tarjetas caducadas (por el `ts` de dentro, no por mtime: el mtime cambiaría
// con cualquier toqueteo del fichero y el TTL debe contar desde que se publicó) y las
// categorías que se quedan vacías.
func (b *board) sweep() {
	b.mu.Lock()
	defer b.mu.Unlock()
	cutoff := time.Now().Add(-b.ttl).UnixMilli()
	cats, err := os.ReadDir(b.dir)
	if err != nil {
		return
	}
	for _, cat := range cats {
		if !cat.IsDir() {
			continue
		}
		dir := filepath.Join(b.dir, cat.Name())
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
			var c boardCard
			if err := json.Unmarshal(data, &c); err != nil || c.Ts < cutoff || b.bans.isBanned(c.Peer) {
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

// replier devuelve la función de respuesta {"ok":true}/{"err":…} común a publish y delete.
func replier(s network.Stream) func(errMsg string) {
	return func(errMsg string) {
		if errMsg == "" {
			fmt.Fprintln(s, `{"ok":true}`)
			return
		}
		out, _ := json.Marshal(map[string]string{"err": errMsg})
		fmt.Fprintf(s, "%s\n", out)
	}
}
