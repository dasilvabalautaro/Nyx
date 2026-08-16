package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
)

// --- utilidades: hablan el protocolo tal y como lo hará el móvil ---

func boardPublish(t *testing.T, from host.Host, node peer.ID, cat string, card []byte) error {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, boardPublishProtocol)
	if err != nil {
		t.Fatalf("publish stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(boardPublishReq{V: 1, Cat: cat, Card: base64.StdEncoding.EncodeToString(card)})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()
	return readOkErr(t, s)
}

func boardQuery(t *testing.T, from host.Host, node peer.ID, cat string, limit int) []boardCard {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, boardQueryProtocol)
	if err != nil {
		t.Fatalf("query stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(boardQueryReq{V: 1, Cat: cat, Limit: limit})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()

	var out []boardCard
	r := bufio.NewReader(s)
	for {
		line, err := r.ReadBytes('\n')
		if len(line) == 0 && err != nil {
			return out
		}
		var c boardCard
		if json.Unmarshal(line, &c) != nil {
			return out
		}
		if c.Done {
			return out
		}
		out = append(out, c)
	}
}

func boardDelete(t *testing.T, from host.Host, node peer.ID, cat string) error {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, boardDeleteProtocol)
	if err != nil {
		t.Fatalf("delete stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(boardDeleteReq{V: 1, Cat: cat})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()
	return readOkErr(t, s)
}

func readOkErr(t *testing.T, s interface{ Read([]byte) (int, error) }) error {
	t.Helper()
	line, err := bufio.NewReader(s).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		t.Fatalf("sin respuesta: %v", err)
	}
	var resp struct {
		Ok  bool   `json:"ok"`
		Err string `json:"err"`
	}
	if err := json.Unmarshal(line, &resp); err != nil {
		t.Fatalf("respuesta ilegible %q: %v", line, err)
	}
	if resp.Err != "" {
		return fmt.Errorf("%s", resp.Err)
	}
	return nil
}

// --- tests ---

// TestBoardPublishQuery: el ciclo básico. A y B publican en la misma categoría, cualquiera
// consulta y ve las dos tarjetas con su autor correcto y los bytes intactos.
func TestBoardPublishQuery(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	if err := boardPublish(t, a, node.ID(), "citas", []byte(`{"nick":"Ana"}`)); err != nil {
		t.Fatalf("A no pudo publicar: %v", err)
	}
	if err := boardPublish(t, b, node.ID(), "citas", []byte(`{"nick":"Bruno"}`)); err != nil {
		t.Fatalf("B no pudo publicar: %v", err)
	}

	cards := boardQuery(t, a, node.ID(), "citas", 50)
	if len(cards) != 2 {
		t.Fatalf("esperaba 2 tarjetas, hay %d", len(cards))
	}
	byPeer := map[string]string{}
	for _, c := range cards {
		raw, err := base64.StdEncoding.DecodeString(c.Card)
		if err != nil {
			t.Fatalf("tarjeta no es base64: %v", err)
		}
		byPeer[c.Peer] = string(raw)
	}
	if got := byPeer[a.ID().String()]; got != `{"nick":"Ana"}` {
		t.Errorf("la tarjeta de A no llegó intacta: %q", got)
	}
	if got := byPeer[b.ID().String()]; got != `{"nick":"Bruno"}` {
		t.Errorf("la tarjeta de B no llegó intacta: %q", got)
	}
}

/*
El autor lo pone el nodo desde la identidad del stream, así que no hay campo que falsear:
publicar deja SIEMPRE la tarjeta bajo tu propio PeerID. Este test lo comprueba por el efecto
observable — A publica dos veces y sigue habiendo una sola tarjeta suya, la última.
*/
func TestBoardOneCardPerAuthorOverwrites(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.attach(node)
	connect(t, a, node)

	_ = boardPublish(t, a, node.ID(), "citas", []byte("v1"))
	time.Sleep(2 * time.Millisecond) // que el ts cambie
	_ = boardPublish(t, a, node.ID(), "citas", []byte("v2"))

	cards := boardQuery(t, a, node.ID(), "citas", 50)
	if len(cards) != 1 {
		t.Fatalf("republicar debe sobreescribir, no acumular: hay %d tarjetas", len(cards))
	}
	raw, _ := base64.StdEncoding.DecodeString(cards[0].Card)
	if string(raw) != "v2" {
		t.Errorf("quedó la tarjeta vieja: %q", raw)
	}
	if cards[0].Peer != a.ID().String() {
		t.Errorf("el autor no es el del stream: %s", cards[0].Peer)
	}
}

// TestBoardDelete: quitarse del tablón es inmediato y solo afecta a lo propio.
func TestBoardDelete(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	_ = boardPublish(t, a, node.ID(), "citas", []byte("soy A"))
	_ = boardPublish(t, b, node.ID(), "citas", []byte("soy B"))
	_ = boardPublish(t, a, node.ID(), "amistad", []byte("soy A también aquí"))

	if err := boardDelete(t, a, node.ID(), "citas"); err != nil {
		t.Fatalf("delete: %v", err)
	}

	cards := boardQuery(t, b, node.ID(), "citas", 50)
	if len(cards) != 1 || cards[0].Peer != b.ID().String() {
		t.Fatalf("borrar lo de A no debe tocar lo de B: %+v", cards)
	}
	// La otra categoría de A sigue intacta: se borró solo la pedida.
	if got := boardQuery(t, a, node.ID(), "amistad", 50); len(got) != 1 {
		t.Errorf("delete con categoría no debe vaciar las demás: %+v", got)
	}

	// Sin categoría = desaparecer del tablón entero.
	if err := boardDelete(t, a, node.ID(), ""); err != nil {
		t.Fatalf("delete total: %v", err)
	}
	if got := boardQuery(t, a, node.ID(), "amistad", 50); len(got) != 0 {
		t.Errorf("delete sin categoría debe quitar todas: %+v", got)
	}
}

/*
Un peer solo puede borrar lo suyo. No hay parámetro de autor en la petición —la ruta se
construye con el PeerID del stream—, así que esto verifica que ese diseño se sostiene: B
borrando "citas" no puede llevarse por delante la tarjeta de A.
*/
func TestBoardDeleteOnlyRemovesYourOwn(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	_ = boardPublish(t, a, node.ID(), "citas", []byte("soy A"))
	_ = boardDelete(t, b, node.ID(), "") // B intenta vaciarlo todo

	cards := boardQuery(t, a, node.ID(), "citas", 50)
	if len(cards) != 1 || cards[0].Peer != a.ID().String() {
		t.Fatalf("B no puede borrar la tarjeta de A: %+v", cards)
	}
}

// TestBoardTTLAndSweep: una tarjeta caducada no se devuelve **aunque el barrido no haya
// pasado** —el TTL es una garantía, no "lo que quede tras el próximo sweep"— y el barrido la
// borra del disco.
func TestBoardTTLAndSweep(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.attach(node)
	connect(t, a, node)

	_ = boardPublish(t, a, node.ID(), "citas", []byte("efímera"))
	if len(boardQuery(t, a, node.ID(), "citas", 50)) != 1 {
		t.Fatal("preparación: la tarjeta debía estar")
	}

	brd.ttl = time.Nanosecond
	time.Sleep(2 * time.Millisecond)

	if got := boardQuery(t, a, node.ID(), "citas", 50); len(got) != 0 {
		t.Errorf("una tarjeta caducada no debe devolverse: %+v", got)
	}
	brd.sweep()
	if n := countJSON(brd.dir + "/citas"); n != 0 {
		t.Errorf("el barrido debe borrarla del disco, quedan %d", n)
	}
}

// TestBoardLimits: los dos topes que el plan pedía añadir — tamaño de tarjeta y cuota por
// categoría — más el saneado de la categoría, que es lo que impide escapar del boarddir.
func TestBoardLimits(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.maxCard = 128
	brd.attach(node)
	connect(t, a, node)

	if err := boardPublish(t, a, node.ID(), "citas", make([]byte, 129)); err == nil {
		t.Error("una tarjeta por encima del tope debe rechazarse")
	}
	if err := boardPublish(t, a, node.ID(), "citas", make([]byte, 128)); err != nil {
		t.Errorf("justo en el tope debe aceptarse: %v", err)
	}

	// Categorías que servirían para salir del directorio o para ensuciarlo.
	for _, bad := range []string{"../../etc", "Citas", "con espacio", "", "a/b", "."} {
		if err := boardPublish(t, a, node.ID(), bad, []byte("x")); err == nil {
			t.Errorf("categoría %q debería rechazarse", bad)
		}
	}
}

// TestBoardCategoryQuota: la cuota frena a autores nuevos, pero **nunca** impide a alguien ya
// publicado actualizar su tarjeta (republicar no añade ficheros).
func TestBoardCategoryQuota(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	brd := newBoard(t.TempDir())
	brd.maxCards = 1
	brd.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	if err := boardPublish(t, a, node.ID(), "citas", []byte("A")); err != nil {
		t.Fatalf("la primera debe entrar: %v", err)
	}
	if err := boardPublish(t, b, node.ID(), "citas", []byte("B")); err == nil {
		t.Error("con la categoría llena, un autor nuevo debe rechazarse")
	}
	if err := boardPublish(t, a, node.ID(), "citas", []byte("A v2")); err != nil {
		t.Errorf("quien ya está publicado debe poder actualizar aunque esté llena: %v", err)
	}
}
