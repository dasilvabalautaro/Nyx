package bridge

import (
	"os"
	"strings"
	"testing"
)

// TestReserveAgainstLocalNode dials a locally-run infra node (RELAY_ADDR) and attempts a
// Circuit Relay v2 reservation, replicating the phone's path. Self-skips without RELAY_ADDR.
func TestReserveAgainstLocalNode(t *testing.T) {
	relay := os.Getenv("RELAY_ADDR")
	if relay == "" {
		t.Skip("set RELAY_ADDR to the local infra node multiaddr")
	}
	n, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode: %v", err)
	}
	defer n.Close()

	res := n.ReserveRelay(relay)
	t.Logf("ReserveRelay -> %q", res)
	if !strings.HasPrefix(res, "OK") {
		t.Fatalf("reservation failed: %s", res)
	}
}
