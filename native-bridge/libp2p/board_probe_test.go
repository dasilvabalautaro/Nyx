package bridge

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/libp2p/go-libp2p/core/crypto"
)

// Sonda de un solo uso contra el nodo vivo, en el estilo de las de buzón y wake: publica una
// tarjeta con una identidad **derivada de una semilla** en la categoría indicada, para poder
// comprobar en el móvil que el tablón se ve de punta a punta. Se salta sola si no se le pide
// nada.
//
//	BOARD_ADDR=/dns4/nyx.neto.chat/tcp/4001/p2p/<PeerID> BOARD_CAT=citas \
//	  go test -run TestBoardPublishAgainstLiveNode -v ./...
//
// La identidad sale de la semilla y no es aleatoria justamente para poder **retirarla después**:
// una tarjeta olvidada se queda 48 h en el tablón de producción. Para borrarla, la misma orden
// con BOARD_DELETE=1.
func TestBoardPublishAgainstLiveNode(t *testing.T) {
	addr, cat := os.Getenv("BOARD_ADDR"), os.Getenv("BOARD_CAT")
	if addr == "" || cat == "" {
		t.Skip("sin BOARD_ADDR/BOARD_CAT: sonda desactivada")
	}
	seed := os.Getenv("BOARD_SEED")
	if seed == "" {
		seed = "sonda-tablon-nyx"
	}
	// La identidad de libp2p es una clave privada serializada, no una semilla cruda; se
	// deriva Ed25519 de forma determinista a partir de la semilla para que la sonda pueda
	// borrar después lo que publicó.
	sum := sha256.Sum256([]byte(seed))
	priv, _, err := crypto.GenerateEd25519Key(bytes.NewReader(append(sum[:], sum[:]...)))
	if err != nil {
		t.Fatalf("clave: %v", err)
	}
	identity, err := crypto.MarshalPrivateKey(priv)
	if err != nil {
		t.Fatalf("serializar clave: %v", err)
	}

	n, err := NewNodeWithIdentity(identity, "")
	if err != nil {
		t.Fatalf("nodo: %v", err)
	}
	defer n.Close()
	if err := n.StartDHT(addr, false); err != nil {
		t.Logf("aviso: StartDHT devolvió %v (se intenta publicar igual)", err)
	}

	if os.Getenv("BOARD_DELETE") != "" {
		if err := n.DeleteCard(addr, cat); err != nil {
			t.Fatalf("no se pudo retirar la tarjeta: %v", err)
		}
		t.Logf("tarjeta retirada de %q (era de %s)", cat, n.PeerID())
		return
	}

	// Formato P1 de BoardCard.kt, a mano: cabecera por líneas + bio detrás, sin avatar (la app
	// dibuja entonces el rostro derivado del PeerID, que es justo lo que interesa ver).
	// BOARD_MAX=1 publica una tarjeta con el contenido máximo que la app permite hoy, para
	// mirar si la tarjeta se ve proporcionada en el peor caso y no solo en el bonito.
	nick, bio, intereses := "Sonda", "Tarjeta de prueba publicada desde el Mac para verificar el tablon.", "senderismo\tcine"
	if os.Getenv("BOARD_MAX") != "" {
		nick = strings.Repeat("N", 32)
		bio = strings.Repeat("Palabra ", 37) + "fin."
		if len(bio) > 300 {
			bio = bio[:300]
		}
		partes := make([]string, 10)
		for i := range partes {
			partes[i] = fmt.Sprintf("interes-largo-num-%02d", i)
		}
		intereses = strings.Join(partes, "\t")
	}
	card := fmt.Sprintf("P1\n%s\n29\n35\n%s\n\n%d\n0\n%s", nick, intereses, len(bio), bio)
	if err := n.PublishCard(addr, cat, []byte(card)); err != nil {
		t.Fatalf("no se pudo publicar: %v", err)
	}
	t.Logf("publicada en %q como %s", cat, n.PeerID())
}
