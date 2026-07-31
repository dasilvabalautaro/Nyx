package bridge

import (
	"strings"
	"testing"
)

// TestPingProbeLocal valida la sonda de latencia (Fase 7a, llamadas): un nodo B mide el RTT
// contra un nodo A in-process con el servicio ping estándar de libp2p y obtiene estadísticas.
func TestPingProbeLocal(t *testing.T) {
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	defer a.Close()

	b, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	defer b.Close()

	boot := loopbackBootstrap(t, a)
	stats, err := b.PingProbe(boot, 10, 20)
	if err != nil {
		t.Fatalf("PingProbe: %v", err)
	}
	t.Logf("stats: %s", stats)
	if !strings.HasPrefix(stats, "n=10/10 ") || !strings.Contains(stats, "p95=") {
		t.Fatalf("estadísticas inesperadas: %q", stats)
	}
}
