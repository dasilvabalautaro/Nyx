package bridge

import (
	"bytes"
	"testing"
	"time"
)

type echoVideoHandler struct{ opened chan *VideoStream }

func (h *echoVideoHandler) OnVideoStream(s *VideoStream) {
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

// TestVideoStreamEcho valida el stream de vídeo (Fase 7c): A abre /krypta/video/1.0.0
// hacia B, manda frames con framing uint32 — incluido uno de 200 KiB (un keyframe H.264
// no cabe en el framing uint16 del audio) — y los recibe de vuelta intactos.
func TestVideoStreamEcho(t *testing.T) {
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

	h := &echoVideoHandler{opened: make(chan *VideoStream, 1)}
	b.SetVideoHandler(h)

	if err := a.StartDHT(loopbackBootstrap(t, b), false); err != nil {
		t.Fatalf("connect A→B: %v", err)
	}
	s, err := a.OpenVideoStream(b.PeerID())
	if err != nil {
		t.Fatalf("OpenVideoStream: %v", err)
	}
	defer s.Close()

	// Vacío, P-frame chico, y un keyframe grande (>64 KiB, imposible en el framing de audio).
	frames := [][]byte{{0x01}, {}, bytes.Repeat([]byte{0xCD}, 6_000), bytes.Repeat([]byte{0x42}, 200_000)}
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

	// Por encima del tope se rechaza en origen (protege al receptor).
	if err := s.WriteFrame(make([]byte, videoMaxFrame+1)); err == nil {
		t.Fatal("WriteFrame >1MiB debió fallar")
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
