package bridge

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
)

// testBoardServer es un tablón + bandeja de likes EN MEMORIA que habla el mismo protocolo
// JSON que infra/nyx-node (board.go / like.go). Vive en el test por el mismo motivo que
// `testMailboxServer`: los dos módulos usan versiones distintas de go-libp2p (v0.48 aquí,
// v0.38 el nodo) y no pueden compartir código — el contrato es el protocolo, no el tipo.
type testBoardServer struct {
	mu    sync.Mutex
	cards map[string]map[string]boardCard // categoría → autor → tarjeta
	likes map[string]map[string]likeEnvelope
}

func startTestBoard(t *testing.T) (peer.ID, string, *testBoardServer) {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatalf("board host: %v", err)
	}
	t.Cleanup(func() { _ = h.Close() })
	srv := &testBoardServer{
		cards: map[string]map[string]boardCard{},
		likes: map[string]map[string]likeEnvelope{},
	}

	h.SetStreamHandler(boardPublishProtocol, func(s network.Stream) {
		defer s.Close()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var req struct {
			Cat  string `json:"cat"`
			Card string `json:"card"`
		}
		if err := json.Unmarshal(line, &req); err != nil {
			fmt.Fprintln(s, `{"err":"json"}`)
			return
		}
		srv.mu.Lock()
		if srv.cards[req.Cat] == nil {
			srv.cards[req.Cat] = map[string]boardCard{}
		}
		author := s.Conn().RemotePeer().String()
		srv.cards[req.Cat][author] = boardCard{Peer: author, Ts: time.Now().UnixMilli(), Card: req.Card}
		srv.mu.Unlock()
		fmt.Fprintln(s, `{"ok":true}`)
	})

	h.SetStreamHandler(boardQueryProtocol, func(s network.Stream) {
		defer s.Close()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var req struct {
			Cat string `json:"cat"`
		}
		_ = json.Unmarshal(line, &req)
		srv.mu.Lock()
		var out []boardCard
		for _, c := range srv.cards[req.Cat] {
			out = append(out, c)
		}
		srv.mu.Unlock()
		sort.Slice(out, func(i, j int) bool { return out[i].Peer < out[j].Peer })
		w := bufio.NewWriter(s)
		for _, c := range out {
			raw, _ := json.Marshal(c)
			fmt.Fprintf(w, "%s\n", raw)
		}
		fmt.Fprintln(w, `{"done":true}`)
		_ = w.Flush()
	})

	h.SetStreamHandler(boardDeleteProtocol, func(s network.Stream) {
		defer s.Close()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var req struct {
			Cat string `json:"cat"`
		}
		_ = json.Unmarshal(line, &req)
		author := s.Conn().RemotePeer().String()
		srv.mu.Lock()
		for cat, byAuthor := range srv.cards {
			if req.Cat == "" || req.Cat == cat {
				delete(byAuthor, author)
			}
		}
		srv.mu.Unlock()
		fmt.Fprintln(s, `{"ok":true}`)
	})

	h.SetStreamHandler(likePutProtocol, func(s network.Stream) {
		defer s.Close()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var req struct {
			To   string `json:"to"`
			Blob string `json:"blob"`
		}
		if err := json.Unmarshal(line, &req); err != nil {
			fmt.Fprintln(s, `{"err":"json"}`)
			return
		}
		from := s.Conn().RemotePeer().String()
		srv.mu.Lock()
		if srv.likes[req.To] == nil {
			srv.likes[req.To] = map[string]likeEnvelope{}
		}
		// Sobreescribe por emisor, como el nodo real.
		srv.likes[req.To][from] = likeEnvelope{From: from, Ts: time.Now().UnixMilli(), Blob: req.Blob}
		srv.mu.Unlock()
		fmt.Fprintln(s, `{"ok":true}`)
	})

	h.SetStreamHandler(likeGetProtocol, func(s network.Stream) {
		defer s.Close()
		to := s.Conn().RemotePeer().String()
		srv.mu.Lock()
		var out []likeEnvelope
		for _, e := range srv.likes[to] {
			out = append(out, e)
		}
		srv.mu.Unlock()
		sort.Slice(out, func(i, j int) bool { return out[i].From < out[j].From })
		w := bufio.NewWriter(s)
		for _, e := range out {
			raw, _ := json.Marshal(e)
			fmt.Fprintf(w, "%s\n", raw)
		}
		fmt.Fprintln(w, `{"done":true}`)
		_ = w.Flush()

		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var ack struct {
			Ack []string `json:"ack"`
		}
		if json.Unmarshal(line, &ack) != nil {
			return
		}
		srv.mu.Lock()
		for _, from := range ack.Ack {
			delete(srv.likes[to], from)
		}
		srv.mu.Unlock()
	})

	return h.ID(), h.Addrs()[0].String() + "/p2p/" + h.ID().String(), srv
}

// likeRecv acumula lo entregado por LikeFetch. `reject` simula un fallo de persistencia en
// Kotlin (→ sin ack) y `panicOn` una excepción.
type likeRecv struct {
	mu      sync.Mutex
	got     []string
	from    []string
	reject  map[string]bool
	panicOn map[string]bool
}

func (r *likeRecv) OnLike(from string, ts int64, data []byte) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.panicOn[string(data)] {
		panic("kotlin exception simulada")
	}
	r.from = append(r.from, from)
	r.got = append(r.got, string(data))
	return !r.reject[string(data)]
}

func newTestNode(t *testing.T) *Node {
	t.Helper()
	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad: %v", err)
	}
	n, err := NewNodeWithIdentity(id, "")
	if err != nil {
		t.Fatalf("nodo: %v", err)
	}
	t.Cleanup(func() { _ = n.Close() })
	return n
}

// decodeCards deshace el JSON que QueryBoard devuelve a Kotlin.
func decodeCards(t *testing.T, raw string) []boardCard {
	t.Helper()
	var cards []boardCard
	if err := json.Unmarshal([]byte(raw), &cards); err != nil {
		t.Fatalf("QueryBoard no devolvió JSON válido (%q): %v", raw, err)
	}
	return cards
}

// TestBoardPublishQueryBridge: ciclo publicar → consultar visto desde el cliente, con la
// tarjeta llegando intacta y el autor puesto por el servidor.
func TestBoardPublishQueryBridge(t *testing.T) {
	_, addr, _ := startTestBoard(t)
	a, b := newTestNode(t), newTestNode(t)

	if err := a.PublishCard(addr, "citas", []byte(`{"nick":"Ana"}`)); err != nil {
		t.Fatalf("publish A: %v", err)
	}
	if err := b.PublishCard(addr, "citas", []byte(`{"nick":"Bruno"}`)); err != nil {
		t.Fatalf("publish B: %v", err)
	}

	cards := decodeCards(t, mustQuery(t, a, addr, "citas", 50))
	if len(cards) != 2 {
		t.Fatalf("esperaba 2 tarjetas, hay %d", len(cards))
	}
	found := map[string]string{}
	for _, c := range cards {
		raw, err := base64.StdEncoding.DecodeString(c.Card)
		if err != nil {
			t.Fatalf("card no es base64: %v", err)
		}
		found[c.Peer] = string(raw)
	}
	if found[a.PeerID()] != `{"nick":"Ana"}` || found[b.PeerID()] != `{"nick":"Bruno"}` {
		t.Errorf("las tarjetas no llegaron intactas: %v", found)
	}
}

/*
Consultar drena TODOS los nodos y fusiona por autor. Es lo que evita la vista parcial cuando
cada nodo tiene un trozo del tablón: A publica en el nodo 1 y B en el nodo 2, y una sola
consulta tiene que ver a los dos.
*/
func TestQueryBoardMergesAllNodes(t *testing.T) {
	_, addr1, _ := startTestBoard(t)
	_, addr2, _ := startTestBoard(t)
	both := addr1 + "\n" + addr2

	a, b, reader := newTestNode(t), newTestNode(t), newTestNode(t)
	if err := a.PublishCard(addr1, "citas", []byte("soy A")); err != nil {
		t.Fatalf("publish A: %v", err)
	}
	if err := b.PublishCard(addr2, "citas", []byte("soy B")); err != nil {
		t.Fatalf("publish B: %v", err)
	}

	cards := decodeCards(t, mustQuery(t, reader, both, "citas", 50))
	if len(cards) != 2 {
		t.Fatalf("consultando ambos nodos deben verse 2 tarjetas, hay %d: %+v", len(cards), cards)
	}
}

// El mismo autor publicado en dos nodos aparece UNA vez, con la versión más reciente.
func TestQueryBoardDedupesSameAuthor(t *testing.T) {
	_, addr1, _ := startTestBoard(t)
	_, addr2, _ := startTestBoard(t)
	a, reader := newTestNode(t), newTestNode(t)

	if err := a.PublishCard(addr1, "citas", []byte("vieja")); err != nil {
		t.Fatalf("publish: %v", err)
	}
	time.Sleep(3 * time.Millisecond)
	if err := a.PublishCard(addr2, "citas", []byte("nueva")); err != nil {
		t.Fatalf("publish: %v", err)
	}

	cards := decodeCards(t, mustQuery(t, reader, addr1+"\n"+addr2, "citas", 50))
	if len(cards) != 1 {
		t.Fatalf("el mismo autor no puede salir dos veces: %+v", cards)
	}
	raw, _ := base64.StdEncoding.DecodeString(cards[0].Card)
	if string(raw) != "nueva" {
		t.Errorf("debe quedarse la más reciente, quedó %q", raw)
	}
}

// Publicar es failover (basta un nodo); **borrar tiene que ser en todos**, y si falla en uno
// hay que enterarse: la tarjeta sigue pública ahí. Esta asimetría es deliberada.
func TestDeleteCardReportsPartialFailure(t *testing.T) {
	_, addr1, _ := startTestBoard(t)
	_, addr2, srv2 := startTestBoard(t)
	a := newTestNode(t)

	if err := a.PublishCard(addr1, "citas", []byte("soy A")); err != nil {
		t.Fatalf("publish: %v", err)
	}
	if err := a.PublishCard(addr2, "citas", []byte("soy A")); err != nil {
		t.Fatalf("publish: %v", err)
	}

	// Borrado con los dos nodos vivos: sin error y sin rastro.
	if err := a.DeleteCard(addr1+"\n"+addr2, "citas"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	srv2.mu.Lock()
	remaining := len(srv2.cards["citas"])
	srv2.mu.Unlock()
	if remaining != 0 {
		t.Errorf("el borrado debe alcanzar al segundo nodo, quedan %d", remaining)
	}

	// Con un nodo inalcanzable, el borrado NO puede reportarse como éxito.
	dead := "/ip4/127.0.0.1/tcp/1/p2p/" + mustPeerID(t)
	if err := a.DeleteCard(addr1+"\n"+dead, "citas"); err == nil {
		t.Error("si un nodo no acepta el borrado hay que devolver error: la tarjeta sigue ahí")
	}
}

// TestLikePutFetchBridge: ciclo de like visto desde el cliente, con emisor fijado por el
// servidor y borrado tras el ack.
func TestLikePutFetchBridge(t *testing.T) {
	_, addr, _ := startTestBoard(t)
	a, b := newTestNode(t), newTestNode(t)

	if err := a.LikePut(addr, b.PeerID(), []byte("like-cifrado")); err != nil {
		t.Fatalf("like put: %v", err)
	}

	recv := &likeRecv{}
	b.SetLikeHandler(recv)
	n, err := b.LikeFetch(addr)
	if err != nil {
		t.Fatalf("like fetch: %v", err)
	}
	if n != 1 || len(recv.got) != 1 || recv.got[0] != "like-cifrado" {
		t.Fatalf("fetch entregó %d: %v", n, recv.got)
	}
	if recv.from[0] != a.PeerID() {
		t.Errorf("emisor esperado %s, fue %s", a.PeerID(), recv.from[0])
	}
	if n, err := b.LikeFetch(addr); err != nil || n != 0 {
		t.Errorf("tras el ack no debe quedar nada: n=%d err=%v", n, err)
	}
}

// Un like que el handler no confirma (fallo de persistencia, o excepción de Kotlin) no se
// ack'ea y se reentrega. Misma garantía que el buzón, y aquí igual de importante: un like
// perdido es un match que nunca ocurre.
func TestLikeRedeliverUnackedBridge(t *testing.T) {
	_, addr, _ := startTestBoard(t)
	a, b := newTestNode(t), newTestNode(t)

	if err := a.LikePut(addr, b.PeerID(), []byte("importante")); err != nil {
		t.Fatalf("like put: %v", err)
	}

	// Primera pasada: el handler lanza → sin ack.
	boom := &likeRecv{panicOn: map[string]bool{"importante": true}}
	b.SetLikeHandler(boom)
	if _, err := b.LikeFetch(addr); err != nil {
		t.Fatalf("fetch 1: %v", err)
	}

	// Segunda: el handler rechaza → sigue sin ack.
	no := &likeRecv{reject: map[string]bool{"importante": true}}
	b.SetLikeHandler(no)
	if _, err := b.LikeFetch(addr); err != nil {
		t.Fatalf("fetch 2: %v", err)
	}
	if len(no.got) != 1 {
		t.Fatalf("debía reentregarse tras el panic, llegaron %d", len(no.got))
	}

	// Tercera: acepta → ya sí se borra.
	ok := &likeRecv{}
	b.SetLikeHandler(ok)
	if _, err := b.LikeFetch(addr); err != nil {
		t.Fatalf("fetch 3: %v", err)
	}
	if len(ok.got) != 1 {
		t.Fatalf("debía reentregarse tras el rechazo, llegaron %d", len(ok.got))
	}
	if n, _ := b.LikeFetch(addr); n != 0 {
		t.Error("tras confirmarlo debe dejar de reentregarse")
	}
}

func mustQuery(t *testing.T, n *Node, addrs, cat string, limit int) string {
	t.Helper()
	raw, err := n.QueryBoard(addrs, cat, limit)
	if err != nil {
		t.Fatalf("QueryBoard: %v", err)
	}
	return raw
}

func mustPeerID(t *testing.T) string {
	t.Helper()
	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad: %v", err)
	}
	pid, err := PeerIDForIdentity(id)
	if err != nil {
		t.Fatalf("peerID: %v", err)
	}
	return pid
}
