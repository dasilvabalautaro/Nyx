package main

import (
	"context"
	"io"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	relayv2 "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	ma "github.com/multiformats/go-multiaddr"
)

// Caudales medidos de Nyx, por dirección (ver relay.go). Sirven de referencia para
// comprobar que los topes dan de sobra para una llamada real.
const (
	videoBytesPerHour = int64(225) << 20
	voiceBytesPerHour = int64(43) << 20
)

// TestRelayResourcesFinite: los topes del relay son finitos (no WithInfiniteLimits) y a la
// vez holgados para una llamada larga de verdad. Es la doble condición que hay que cumplir
// para poder abrir el nodo a público sin regalar ancho de banda ni cortar llamadas.
func TestRelayResourcesFinite(t *testing.T) {
	r := relayResources(relayDataPerDirection, relayCircuitDuration)

	if r.Limit == nil {
		t.Fatal("el relay quedó con límites infinitos: es exactamente lo que este cambio arregla")
	}
	if hours := r.Limit.Data / videoBytesPerHour; hours < 4 {
		t.Errorf("tope de datos = %d MiB, solo %d h de vídeo; una videollamada larga se cortaría",
			r.Limit.Data>>20, hours)
	}
	if hours := r.Limit.Data / voiceBytesPerHour; hours < 20 {
		t.Errorf("tope de datos = %d MiB, solo %d h de voz", r.Limit.Data>>20, hours)
	}
	if r.Limit.Duration < 4*time.Hour {
		t.Errorf("duración máxima de circuito = %s, demasiado corta para una llamada larga", r.Limit.Duration)
	}

	// Regresión concreta: los defaults que mataban las llamadas a los ~20 s.
	def := relayv2.DefaultLimit()
	if r.Limit.Data <= def.Data || r.Limit.Duration <= def.Duration {
		t.Errorf("los topes no superan los defaults de go-libp2p (%d B / %s), que ya se sabe que cortan llamadas",
			def.Data, def.Duration)
	}
}

// TestRelayResourcesCGNAT: los topes por IP y por ASN están por encima de los defaults de
// go-libp2p. Los defaults (8 por IP, 32 por ASN) asumen una IP pública por usuario; los
// usuarios de Nyx entran por CGNAT móvil, donde una operadora entera comparte unas pocas
// IPs y un solo ASN — con el default, el usuario 33 de la operadora se queda sin relay.
func TestRelayResourcesCGNAT(t *testing.T) {
	r := relayResources(relayDataPerDirection, relayCircuitDuration)
	def := relayv2.DefaultResources()

	if r.MaxReservationsPerIP <= def.MaxReservationsPerIP {
		t.Errorf("MaxReservationsPerIP = %d, no supera el default %d (CGNAT móvil comparte IP)",
			r.MaxReservationsPerIP, def.MaxReservationsPerIP)
	}
	if r.MaxReservationsPerASN <= def.MaxReservationsPerASN {
		t.Errorf("MaxReservationsPerASN = %d, no supera el default %d (una operadora entera es un ASN)",
			r.MaxReservationsPerASN, def.MaxReservationsPerASN)
	}
	if r.MaxReservations < r.MaxReservationsPerASN {
		t.Errorf("MaxReservations (%d) por debajo del tope por ASN (%d): el tope global anularía al de ASN",
			r.MaxReservations, r.MaxReservationsPerASN)
	}
	if r.MaxCircuits <= 0 || r.MaxReservations <= 0 {
		t.Errorf("topes de concurrencia no positivos: %+v", r)
	}
}

// TestRelayResourcesFromFlags: los dos valores que llegan por flag (-relaydata,
// -relayduration) son los que acaban en Resources, no las constantes.
func TestRelayResourcesFromFlags(t *testing.T) {
	r := relayResources(7<<20, 90*time.Second)
	if r.Limit.Data != 7<<20 || r.Limit.Duration != 90*time.Second {
		t.Fatalf("los flags no llegan a Resources: %+v", r.Limit)
	}
}

const relayEchoProtocol = protocol.ID("/nyx/test/relay-echo/1.0.0")

// TestRelayLimitsAppliedLive comprueba el mecanismo de verdad, no solo el struct: monta un
// relay real con un tope de datos pequeño, hace pasar tráfico por un circuito y verifica
// que (a) por debajo del tope el byte-a-byte llega intacto y (b) por encima el relay corta.
// (b) es lo que demuestra que WithResources está realmente aplicado — con
// WithInfiniteLimits este test se colgaría en vez de cortar.
func TestRelayLimitsAppliedLive(t *testing.T) {
	const dataCap = 256 << 10 // 256 KiB por dirección, para poder agotarlo en un test

	relayHost := newTestHost(t)
	res := relayResources(dataCap, relayCircuitDuration)
	if _, err := relayv2.New(relayHost, relayv2.WithResources(res)); err != nil {
		t.Fatalf("relay service: %v", err)
	}

	// El destinatario (como un móvil tras NAT): reserva slot en el relay y se anuncia por
	// su dirección /p2p-circuit.
	dst := newTestClientHost(t)
	// El emisor.
	src := newTestClientHost(t)

	connect(t, dst, relayHost)
	connect(t, src, relayHost)

	if _, err := client.Reserve(context.Background(), dst, peer.AddrInfo{ID: relayHost.ID(), Addrs: relayHost.Addrs()}); err != nil {
		t.Fatalf("reserva en el relay: %v", err)
	}

	// dst hace de eco: devuelve todo lo que reciba.
	dst.SetStreamHandler(relayEchoProtocol, func(s network.Stream) {
		defer s.Close()
		_, _ = io.Copy(s, s)
	})

	circuit, err := ma.NewMultiaddr("/p2p/" + relayHost.ID().String() + "/p2p-circuit")
	if err != nil {
		t.Fatalf("multiaddr: %v", err)
	}
	src.Peerstore().AddAddrs(dst.ID(), []ma.Multiaddr{circuit}, time.Hour)

	// Por debajo del tope: los bytes llegan y vuelven intactos. 64 KiB ya son la mitad del
	// default de go-libp2p (128 KiB), el que cortaba las llamadas.
	if n := relayEcho(t, src, dst.ID(), 64<<10); n != 64<<10 {
		t.Fatalf("por debajo del tope se perdieron bytes: eco de %d de 65536", n)
	}

	// Por encima del tope: el relay corta el circuito a mitad de transferencia. Se abre un
	// stream nuevo porque el anterior ya consumió parte de la cuota del circuito.
	if n := relayEcho(t, src, dst.ID(), 4*dataCap); n >= 4*dataCap {
		t.Fatalf("el relay no cortó al superar el tope de %d B: pasaron %d B", dataCap, n)
	}
}

// newTestClientHost es un host con el transporte de circuito habilitado (el relay en sí no
// lo necesita; los extremos sí).
func newTestClientHost(t *testing.T) host.Host {
	t.Helper()
	h, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.EnableRelay(),
	)
	if err != nil {
		t.Fatalf("host: %v", err)
	}
	t.Cleanup(func() { _ = h.Close() })
	return h
}

// relayEcho envía `size` bytes por un circuito relayado y devuelve cuántos volvieron.
// No falla si el relay corta: ese corte es justo lo que mide el caller.
func relayEcho(t *testing.T, from host.Host, to peer.ID, size int) int {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	s, err := from.NewStream(network.WithAllowLimitedConn(ctx, "test"), to, relayEchoProtocol)
	if err != nil {
		t.Fatalf("stream por el circuito: %v", err)
	}
	defer s.Close()
	_ = s.SetDeadline(time.Now().Add(30 * time.Second))

	go func() {
		buf := make([]byte, 32<<10)
		for sent := 0; sent < size; {
			n := min(len(buf), size-sent)
			if _, err := s.Write(buf[:n]); err != nil {
				return
			}
			sent += n
		}
		_ = s.CloseWrite()
	}()

	got, _ := io.Copy(io.Discard, s)
	return int(got)
}
