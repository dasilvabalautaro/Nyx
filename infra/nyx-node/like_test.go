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

func likePut(t *testing.T, from host.Host, node peer.ID, to string, blob []byte) error {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, likePutProtocol)
	if err != nil {
		t.Fatalf("like put stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(likePutReq{V: 1, To: to, Blob: base64.StdEncoding.EncodeToString(blob)})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()
	return readOkErr(t, s)
}

// likeFetch retira los likes y ack'ea los emisores indicados por ackFrom (nil = todos).
func likeFetch(t *testing.T, from host.Host, node peer.ID, ackFrom []string) []likeEnvelope {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, likeGetProtocol)
	if err != nil {
		t.Fatalf("like get stream: %v", err)
	}
	defer s.Close()

	var out []likeEnvelope
	r := bufio.NewReader(s)
	for {
		line, err := r.ReadBytes('\n')
		if len(line) == 0 && err != nil {
			break
		}
		var env likeEnvelope
		if json.Unmarshal(line, &env) != nil || env.Done {
			break
		}
		out = append(out, env)
	}

	ack := ackFrom
	if ack == nil {
		for _, e := range out {
			ack = append(ack, e.From)
		}
	}
	payload, _ := json.Marshal(likeAck{Ack: ack})
	fmt.Fprintf(s, "%s\n", payload)
	_ = s.CloseWrite()
	return out
}

// TestLikePutFetch: ciclo básico. El emisor lo pone el nodo desde el stream, el blob viaja
// intacto (es ciphertext: el nodo no puede leerlo) y lo ack'eado se borra.
func TestLikePutFetch(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	lk := newLikebox(t.TempDir())
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	if err := likePut(t, a, node.ID(), b.ID().String(), []byte("ciphertext-like")); err != nil {
		t.Fatalf("A no pudo enviar like: %v", err)
	}

	got := likeFetch(t, b, node.ID(), nil)
	if len(got) != 1 {
		t.Fatalf("B esperaba 1 like, recibió %d", len(got))
	}
	if got[0].From != a.ID().String() {
		t.Errorf("el emisor no es el del stream: %s", got[0].From)
	}
	raw, _ := base64.StdEncoding.DecodeString(got[0].Blob)
	if string(raw) != "ciphertext-like" {
		t.Errorf("el blob no llegó intacto: %q", raw)
	}
	if again := likeFetch(t, b, node.ID(), nil); len(again) != 0 {
		t.Errorf("lo ack'eado debe desaparecer, quedan %d", len(again))
	}
}

// Sin ack no se borra: si el cliente muere antes de persistir, el like se reentrega. Misma
// garantía que el buzón (ack-after-persist), y aquí importa igual — un like perdido es un
// match que nunca ocurre.
func TestLikeRedeliverUnacked(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	lk := newLikebox(t.TempDir())
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	_ = likePut(t, a, node.ID(), b.ID().String(), []byte("like"))

	if got := likeFetch(t, b, node.ID(), []string{}); len(got) != 1 {
		t.Fatalf("preparación: esperaba 1 like, hubo %d", len(got))
	}
	if got := likeFetch(t, b, node.ID(), nil); len(got) != 1 {
		t.Errorf("sin ack el like debe reentregarse, llegaron %d", len(got))
	}
}

// TestLikeOverwritesPerSender: insistir no acumula. Es la mitad del anti-abuso que vive en el
// almacenamiento — el coste que puede imponer un abusador no crece con el número de intentos,
// solo con el número de identidades distintas que se moleste en crear.
func TestLikeOverwritesPerSender(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	lk := newLikebox(t.TempDir())
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	for i := 0; i < 20; i++ {
		if err := likePut(t, a, node.ID(), b.ID().String(), []byte(fmt.Sprintf("like-%d", i))); err != nil {
			t.Fatalf("like %d: %v", i, err)
		}
	}

	got := likeFetch(t, b, node.ID(), nil)
	if len(got) != 1 {
		t.Fatalf("20 likes del mismo emisor deben dejar 1 pendiente, hay %d", len(got))
	}
	raw, _ := base64.StdEncoding.DecodeString(got[0].Blob)
	if string(raw) != "like-19" {
		t.Errorf("debe quedar el último: %q", raw)
	}
}

/*
El test que justifica que este fichero exista, y no reutilizar el buzón.

Publicar una tarjeta hace tu PeerID público. Si los likes compartieran la cuota del buzón
(200 msgs / 5 MiB por destinatario), un abusador podría llenarla a base de likes y **bloquear
la entrega de tus mensajes reales**: el mecanismo anti-acoso se convertiría en un DoS de
mensajería. Aquí se agota la bandeja de likes de B a propósito y se comprueba que su buzón
sigue aceptando mensajes con normalidad.
*/
func TestLikeQuotaDoesNotStarveMailbox(t *testing.T) {
	dir := t.TempDir()
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	mbx := newMailbox(dir + "/mailbox")
	mbx.attach(node)
	lk := newLikebox(dir + "/likes")
	lk.maxPending = 2 // pequeño para poder agotarlo
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	// Emisores distintos, para que cada like ocupe su propia ranura.
	flooders := []host.Host{newTestHost(t), newTestHost(t), newTestHost(t)}
	rejected := 0
	for _, f := range flooders {
		connect(t, f, node)
		if err := likePut(t, f, node.ID(), b.ID().String(), []byte("spam")); err != nil {
			rejected++
		}
	}
	if rejected == 0 {
		t.Fatal("preparación: con maxPending=2 el tercer emisor debía rechazarse")
	}

	// Lo que de verdad se prueba: el buzón de B no se ha visto afectado.
	if err := mbxPut(t, a, node.ID(), b.ID().String(), []byte("mensaje real")); err != nil {
		t.Fatalf("la inundación de likes bloqueó el buzón de B: %v", err)
	}
	envs, err := mbx.list(b.ID().String())
	if err != nil || len(envs) != 1 {
		t.Fatalf("el mensaje real debía estar en el buzón (err=%v, n=%d)", err, len(envs))
	}
}

// TestLikeLimits: blob pequeño a propósito (aquí no cabe una carga útil) y destinatario válido.
func TestLikeLimits(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	lk := newLikebox(t.TempDir())
	lk.maxBlob = 64
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	if err := likePut(t, a, node.ID(), b.ID().String(), make([]byte, 65)); err == nil {
		t.Error("un blob por encima del tope debe rechazarse")
	}
	if err := likePut(t, a, node.ID(), b.ID().String(), nil); err == nil {
		t.Error("un blob vacío debe rechazarse")
	}
	if err := likePut(t, a, node.ID(), "no-soy-un-peerid", []byte("x")); err == nil {
		t.Error("un destinatario inválido debe rechazarse")
	}
}

// TestLikeTTLAndSweep: un like caducado no se entrega y el barrido lo borra.
func TestLikeTTLAndSweep(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	lk := newLikebox(t.TempDir())
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	_ = likePut(t, a, node.ID(), b.ID().String(), []byte("like"))
	lk.ttl = time.Nanosecond
	time.Sleep(2 * time.Millisecond)

	if got := likeFetch(t, b, node.ID(), nil); len(got) != 0 {
		t.Errorf("un like caducado no debe entregarse: %+v", got)
	}
	lk.sweep()
	if n := countJSON(lk.dir + "/" + b.ID().String()); n != 0 {
		t.Errorf("el barrido debe borrarlo, quedan %d", n)
	}
}

// El wake debe dispararse también con un like: si no, un match tardaría hasta el siguiente
// ciclo del wanLoop (hasta 180 s) en notarse.
func TestLikeTriggersWake(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	lk := newLikebox(t.TempDir())
	woken := make(chan string, 4)
	lk.notify = func(to string) { woken <- to }
	lk.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	_ = likePut(t, a, node.ID(), b.ID().String(), []byte("like"))

	select {
	case to := <-woken:
		if to != b.ID().String() {
			t.Errorf("despertó a quien no era: %s", to)
		}
	case <-time.After(2 * time.Second):
		t.Error("un like debe despertar al destinatario")
	}
}
