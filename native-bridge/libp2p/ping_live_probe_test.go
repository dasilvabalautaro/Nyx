package bridge

import (
	"os"
	"testing"
)

// TestPingAgainstLiveNode mide el RTT contra el nodo REAL (producción, vía wss/Cloudflare)
// con la sonda de Fase 7a (llamadas). Un RTT ida-y-vuelta a través de Cloudflare ≈ la
// latencia one-way de un frame de audio relayed entre dos móviles (dos travesías de CF en
// ambos casos). Se auto-omite sin PING_ADDR:
//
//	PING_ADDR="/dns4/nyx.neto.chat/tcp/443/wss/p2p/<PeerID>" go test -run TestPingAgainstLiveNode -v .
func TestPingAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("PING_ADDR")
	if addr == "" {
		t.Skip("PING_ADDR no definido; sonda solo bajo demanda")
	}
	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	n, err := NewNodeWithIdentity(id, "")
	if err != nil {
		t.Fatalf("node: %v", err)
	}
	defer n.Close()

	stats, err := n.PingProbe(addr, 50, 20)
	if err != nil {
		t.Fatalf("PingProbe: %v", err)
	}
	t.Logf("RTT vía Cloudflare: %s", stats)
}
