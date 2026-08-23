// Command node is Nyx's minimal self-hosted infrastructure node (Phase 1 seed):
// a libp2p bootstrap + Kademlia DHT *server*. Later phases add Circuit Relay v2, the
// E2EE store-and-forward mailbox and the wake server to this same binary/fleet.
//
// It also doubles as the discovery counterpart for on-device tests: pass -rendezvous to
// make it advertise + look up a rendezvous key so a phone can find it (and vice versa).
//
// Run:
//   cd infra/node && go mod tidy
//   go run . -listen /ip4/0.0.0.0/tcp/4001 -rendezvous <hex>
package main

import (
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/protocol"
	drouting "github.com/libp2p/go-libp2p/p2p/discovery/routing"
	dutil "github.com/libp2p/go-libp2p/p2p/discovery/util"
	relayv2 "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
)

const nyxProtocol = protocol.ID("/nyx/msg/1.0.0")

func main() {
	listen := flag.String("listen", "/ip4/0.0.0.0/tcp/4001", "listen multiaddr")
	keyPath := flag.String("key", "node.key", "path to persist the Ed25519 identity (stable PeerID)")
	rendezvous := flag.String("rendezvous", "", "if set, advertise + find this rendezvous key")
	wsPortFlag := flag.String("wsport", "8081", "local WebSocket port (Cloudflare Tunnel exposes it as wss/443)")
	// Puerto QUIC. 0 = efímero, que es lo correcto detrás de Cloudflare Tunnel (el túnel no
	// lleva UDP, así que QUIC no se usa y no merece la pena ocupar un puerto fijo). En un VPS
	// con IP pública SÍ hay que fijarlo (p. ej. 4001): el multiaddr QUIC del bootstrap tiene
	// que ser estable entre reinicios para poder publicarlo en DEFAULT_BOOTSTRAP.
	quicPortFlag := flag.String("quicport", "0", "UDP port for QUIC (0 = ephemeral; set it on a public-IP node)")
	mailboxDir := flag.String("mailboxdir", "", "E2EE store-and-forward mailbox dir (default: <key dir>/mailbox)")
	boardDir := flag.String("boarddir", "", "tablón de tarjetas de perfil (default: <key dir>/board)")
	boardTTL := flag.Duration("boardttl", boardDefaultTTL, "cuánto vive una tarjeta del tablón")
	boardMaxCard := flag.Int("boardmaxcard", boardDefaultMaxCard, "tamaño máximo de una tarjeta, en bytes")
	likeDir := flag.String("likedir", "", "bandeja de likes, con cuota propia (default: <key dir>/likes)")
	reportDir := flag.String("reportdir", "", "denuncias cifradas al operador (default: <key dir>/reports)")
	banFile := flag.String("banlist", "", "PeerID expulsados del tablón, uno por línea (default: <key dir>/banned.txt)")
	// Topes del relay (ver relay.go). Ajustables sin recompilar porque son la palanca que
	// se toca si el gasto de la caja se dispara o si una llamada larga se corta.
	relayDataMiB := flag.Int64("relaydata", relayDataPerDirection>>20, "relay: MiB reenviados por dirección y circuito antes de cortarlo")
	relayDuration := flag.Duration("relayduration", relayCircuitDuration, "relay: vida máxima de un circuito relayado")
	flag.Parse()
	wsPort := *wsPortFlag
	quicPort := *quicPortFlag

	ctx := context.Background()

	priv, err := loadOrCreateKey(*keyPath)
	if err != nil {
		log.Fatalf("identity: %v", err)
	}

	h, err := libp2p.New(
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(
			*listen, // TCP crudo (LAN / IP pública directa)
			"/ip4/0.0.0.0/udp/"+quicPort+"/quic-v1", // QUIC (no cruza Cloudflare; fíjalo en VPS)
			"/ip4/0.0.0.0/tcp/"+wsPort+"/ws", // WebSocket: lo expone Cloudflare Tunnel como wss/443
		),
		// Circuit Relay v2: este nodo reenvía tráfico (E2EE) cuando DCUtR no perfora el NAT.
		// Topes FINITOS pero dimensionados con los caudales reales de voz/vídeo (ver
		// relay.go): los defaults de go-libp2p (128 KiB / 2 min) mataban las llamadas a los
		// ~20 s, e infinito regalaba la caja como proxy de ancho de banda a cualquiera.
		libp2p.EnableRelayService(relayv2.WithResources(relayResources(*relayDataMiB<<20, *relayDuration))),
		// El servicio de relay v2 solo ofrece el protocolo `hop` cuando el nodo se cree
		// PÚBLICAMENTE alcanzable. Tras Cloudflare Tunnel (sin IP pública directa) AutoNAT no
		// lo confirma y desactivaría el relay → los móviles no podrían reservar slot. Como este
		// nodo ES el relay público de Nyx, forzamos reachability=public para que el hop
		// quede siempre activo.
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		log.Fatalf("libp2p.New: %v", err)
	}
	defer h.Close()

	// Print any inbound Nyx message (lets on-device tests verify the phone can dial
	// this node and deliver a message over a libp2p stream).
	h.SetStreamHandler(nyxProtocol, func(s network.Stream) {
		defer s.Close()
		data, _ := io.ReadAll(s)
		// Print as hex: payloads are E2EE, so this node sees only opaque ciphertext.
		fmt.Printf("message from %s: %d bytes (ciphertext, hex): %x\n",
			s.Conn().RemotePeer(), len(data), data)
	})

	// Buzón E2EE store-and-forward (ver mailbox.go). Default junto al node.key para que
	// funcione igual bajo launchd (que no fija WorkingDirectory) que a mano.
	mbxDir := *mailboxDir
	if mbxDir == "" {
		mbxDir = filepath.Join(filepath.Dir(*keyPath), "mailbox")
	}
	if err := os.MkdirAll(mbxDir, 0o700); err != nil {
		log.Fatalf("mailbox dir: %v", err)
	}
	mbx := newMailbox(mbxDir)
	mbx.attach(h)
	go func() {
		for {
			mbx.sweep()
			time.Sleep(time.Hour)
		}
	}()

	// Tablón de tarjetas de perfil (ver board.go). A diferencia del buzón, el contenido va en
	// claro: ser descubrible es el punto.
	brdDir := *boardDir
	if brdDir == "" {
		brdDir = filepath.Join(filepath.Dir(*keyPath), "board")
	}
	if err := os.MkdirAll(brdDir, 0o700); err != nil {
		log.Fatalf("board dir: %v", err)
	}
	brd := newBoard(brdDir)
	brd.ttl = *boardTTL
	brd.maxCard = *boardMaxCard
	brd.attach(h)

	// Bandeja de "me gusta" (ver like.go). Directorio y cuota **propios**, separados del
	// buzón: si compartieran cuota, inundar de likes a alguien le bloquearía la entrega de
	// sus mensajes reales.
	lkDir := *likeDir
	if lkDir == "" {
		lkDir = filepath.Join(filepath.Dir(*keyPath), "likes")
	}
	if err := os.MkdirAll(lkDir, 0o700); err != nil {
		log.Fatalf("like dir: %v", err)
	}
	likes := newLikebox(lkDir)
	likes.attach(h)

	// Denuncias y expulsión (ver report.go). El nodo guarda sobres opacos —cifrados a la
	// clave del operador— y consulta una lista de expulsados que se edita por SSH; no hay
	// protocolo de recogida ni de administración, porque SSH ya resuelve las dos cosas.
	rptDir := *reportDir
	if rptDir == "" {
		rptDir = filepath.Join(filepath.Dir(*keyPath), "reports")
	}
	if err := os.MkdirAll(rptDir, 0o700); err != nil {
		log.Fatalf("report dir: %v", err)
	}
	rpt := newReports(rptDir)
	rpt.attach(h)

	banPath := *banFile
	if banPath == "" {
		banPath = filepath.Join(filepath.Dir(*keyPath), "banned.txt")
	}
	bans := newBanlist(banPath)
	brd.bans = bans

	go func() {
		for {
			brd.sweep()
			likes.sweep()
			rpt.sweep()
			time.Sleep(time.Hour)
		}
	}()

	// Wake integrado: un depósito en el buzón —o un like— avisa al instante al destinatario
	// suscrito. Los likes también despiertan: si no, un match tardaría hasta el siguiente
	// ciclo del wanLoop en notarse.
	wake := newWakeRegistry()
	wake.attach(h)
	mbx.notify = wake.wake
	likes.notify = wake.wake

	kad, err := dht.New(ctx, h, dht.Mode(dht.ModeServer))
	if err != nil {
		log.Fatalf("dht.New: %v", err)
	}
	if err := kad.Bootstrap(ctx); err != nil {
		log.Fatalf("dht.Bootstrap: %v", err)
	}

	fmt.Println("Nyx infra node up (bootstrap + DHT server + relay v2). PeerID:", h.ID().String())
	// Los topes del relay se imprimen a propósito: son la diferencia entre "las llamadas se
	// cortan solas" y "cualquiera usa la caja de proxy gratis", y ninguna de las dos cosas
	// se diagnostica rápido sin saber con qué valores arrancó el nodo.
	fmt.Printf("Relay v2 topes: %d MiB/dirección/circuito, %s máx., %d reservas (%d por IP, %d por ASN), %d circuitos por peer\n",
		*relayDataMiB, *relayDuration, relayMaxReservations,
		relayMaxReservationsPerIP, relayMaxReservationsPerASN, relayMaxCircuitsPerPeer)
	fmt.Printf("Tablón: %s (TTL %s, tarjeta ≤%d KiB, ≤%d por categoría) · Likes: %s (cuota propia, ≤%d pendientes)\n",
		brdDir, brd.ttl, brd.maxCard>>10, brd.maxCards, lkDir, likes.maxPending)
	// Se imprime el número de expulsados, y no solo la ruta, para que se vea de un vistazo
	// que el fichero se está leyendo de verdad: una lista que no se carga no da ningún error.
	fmt.Printf("Denuncias: %s (TTL %s, sobre ≤%d KiB, ≤%d por denunciante) · Expulsados: %s (%d)\n",
		rptDir, rpt.ttl, rpt.maxBlob>>10, rpt.maxPerReporter, banPath, bans.count())
	fmt.Println("Bootstrap addrs (use one of these from the phone):")
	for _, a := range h.Addrs() {
		fmt.Printf("  %s/p2p/%s\n", a, h.ID().String())
	}

	if *rendezvous != "" {
		disc := drouting.NewRoutingDiscovery(kad)
		dutil.Advertise(ctx, disc, *rendezvous)
		go func() {
			for {
				time.Sleep(5 * time.Second)
				c, cancel := context.WithTimeout(ctx, 5*time.Second)
				peers, err := disc.FindPeers(c, *rendezvous)
				if err != nil {
					cancel()
					continue
				}
				for p := range peers {
					if p.ID != h.ID() && p.ID != "" {
						fmt.Println("discovered under rendezvous:", p.ID.String())
					}
				}
				cancel()
			}
		}()
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	fmt.Println("\nshutting down")
}

func loadOrCreateKey(path string) (crypto.PrivKey, error) {
	if data, err := os.ReadFile(path); err == nil {
		return crypto.UnmarshalPrivateKey(data)
	}
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, err
	}
	data, err := crypto.MarshalPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return nil, err
	}
	return priv, nil
}
