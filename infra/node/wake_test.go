package main

import (
	"bufio"
	"context"
	"encoding/json"
	"testing"
	"time"
)

// TestWakeOnDeposit valida el wake integrado: B se suscribe a /krypta/wake y, cuando A
// deposita en su buzón, recibe `{"wake":true}` al instante. Un depósito para otro peer
// no lo despierta.
func TestWakeOnDeposit(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	mbx := newMailbox(t.TempDir())
	mbx.attach(node)
	wake := newWakeRegistry()
	wake.attach(node)
	mbx.notify = wake.wake
	connect(t, a, node)
	connect(t, b, node)

	// B mantiene el stream de wake abierto (como hará su Foreground Service).
	s, err := b.NewStream(context.Background(), node.ID(), wakeProtocol)
	if err != nil {
		t.Fatalf("wake stream: %v", err)
	}
	defer s.Close()
	lines := make(chan map[string]bool, 4)
	go func() {
		r := bufio.NewReader(s)
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				close(lines)
				return
			}
			var msg map[string]bool
			if json.Unmarshal([]byte(line), &msg) == nil {
				lines <- msg
			}
		}
	}()

	// Un depósito para OTRO peer no debe despertar a B.
	if err := mbxPut(t, b, node.ID(), a.ID().String(), []byte("para A")); err != nil {
		t.Fatalf("put para A: %v", err)
	}
	select {
	case msg := <-lines:
		t.Fatalf("B despertado por correo ajeno: %v", msg)
	case <-time.After(300 * time.Millisecond):
	}

	// Un depósito para B sí.
	if err := mbxPut(t, a, node.ID(), b.ID().String(), []byte("para B")); err != nil {
		t.Fatalf("put para B: %v", err)
	}
	select {
	case msg := <-lines:
		if !msg["wake"] {
			t.Fatalf("esperaba wake, llegó %v", msg)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timeout: el depósito no despertó a B")
	}
}
