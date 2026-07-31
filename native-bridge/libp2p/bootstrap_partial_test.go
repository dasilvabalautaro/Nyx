package bridge

import (
	"fmt"
	"testing"
)

// TestStartDHTPartialBootstrapFailure pins the multi-node semantics of StartDHT: with a
// bootstrap list, ONE reachable node is enough to be "connected" (a downed node must not
// flip the app to "sin conexión" while the other keeps delivering — seen live 17 Jul 2026),
// and only ALL nodes failing is an error.
func TestStartDHTPartialBootstrapFailure(t *testing.T) {
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	defer a.Close()
	if err := a.StartDHT("", true); err != nil {
		t.Fatalf("A StartDHT: %v", err)
	}

	// Un peer válido pero inalcanzable (identidad real, puerto sin nadie escuchando).
	ghost, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode ghost: %v", err)
	}
	ghostID := ghost.PeerID()
	ghost.Close()
	dead := fmt.Sprintf("/ip4/127.0.0.1/tcp/9/p2p/%s", ghostID)

	b, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	defer b.Close()

	// Nodo caído + nodo vivo → conectado (nil), no error.
	list := dead + "\n" + loopbackBootstrap(t, a)
	if err := b.StartDHT(list, false); err != nil {
		t.Fatalf("StartDHT with one live bootstrap should succeed, got: %v", err)
	}

	// Todos caídos → error (ahí sí estamos fuera de la WAN).
	c, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode C: %v", err)
	}
	defer c.Close()
	if err := c.StartDHT(dead, false); err == nil {
		t.Fatalf("StartDHT with all bootstraps down should fail")
	}
}
