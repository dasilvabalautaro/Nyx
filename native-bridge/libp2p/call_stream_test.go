package bridge

import (
	"bytes"
	"testing"
	"time"
)

type echoCallHandler struct{ opened chan *CallStream }

func (h *echoCallHandler) OnCallStream(s *CallStream) {
	h.opened <- s
	go func() {
		for {
			f, err := s.ReadFrame()
			if err != nil {
				return
			}
			if err := s.WriteFrame(f); err != nil {
				return
			}
		}
	}()
}

// TestCallStreamEcho valida el stream de llamada (Fase 7b): A abre /krypta/call/1.0.0
// hacia B, manda frames binarios con framing uint16 y los recibe de vuelta intactos.
func TestCallStreamEcho(t *testing.T) {
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

	h := &echoCallHandler{opened: make(chan *CallStream, 1)}
	b.SetCallHandler(h)

	// A conoce las addrs de B (como tras un rendezvous/mDNS).
	if err := a.StartDHT(loopbackBootstrap(t, b), false); err != nil {
		t.Fatalf("connect A→B: %v", err)
	}
	s, err := a.OpenCallStream(b.PeerID())
	if err != nil {
		t.Fatalf("OpenCallStream: %v", err)
	}
	defer s.Close()

	// La negociación del protocolo es lazy: el handler de B no salta hasta que llegan los
	// primeros bytes — por eso el flujo real manda el frame "hello" nada más abrir.
	// Frames de tamaño variado (incluye uno vacío y uno de 20 ms de audio típico).
	frames := [][]byte{{0x01}, {}, bytes.Repeat([]byte{0xAB}, 88), bytes.Repeat([]byte{0x7F}, 1500)}
	for i, f := range frames {
		if err := s.WriteFrame(f); err != nil {
			t.Fatalf("WriteFrame %d: %v", i, err)
		}
		got, err := s.ReadFrame()
		if err != nil {
			t.Fatalf("ReadFrame %d: %v", i, err)
		}
		if !bytes.Equal(got, f) {
			t.Fatalf("frame %d: eco distinto (%d bytes vs %d)", i, len(got), len(f))
		}
	}

	select {
	case in := <-h.opened:
		if in.RemotePeer() != a.PeerID() {
			t.Fatalf("RemotePeer = %s, quería %s", in.RemotePeer(), a.PeerID())
		}
	case <-time.After(10 * time.Second):
		t.Fatal("B nunca recibió el stream entrante")
	}
}
