package bridge

import (
	"os"
	"testing"
)

// TestMailboxFetchAgainstLiveNode sondea el buzón de un nodo REAL (p. ej. el de infra en
// producción, vía wss/Cloudflare): crea una identidad efímera y retira su buzón (vacío).
// Si el nodo no expone /krypta/mbx/get/1.0.0 (binario viejo), falla con "protocols not
// supported". Se auto-omite sin MBX_ADDR:
//
//	MBX_ADDR="/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>" go test -run TestMailboxFetchAgainstLiveNode -v .
// TestMailboxPutAgainstLiveNode deposita un blob de prueba en el buzón de MBX_TO en un
// nodo REAL — sirve para disparar el wake del destinatario y medir la latencia de punta a
// punta (el receptor lo ignorará por remitente desconocido, pero su diagnóstico registra
// la retirada). Se auto-omite sin MBX_ADDR/MBX_TO.
func TestMailboxPutAgainstLiveNode(t *testing.T) {
	addr, to := os.Getenv("MBX_ADDR"), os.Getenv("MBX_TO")
	if addr == "" || to == "" {
		t.Skip("MBX_ADDR/MBX_TO no definidos; sonda solo bajo demanda")
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

	if err := n.MailboxPut(addr, to, []byte("sonda wake")); err != nil {
		t.Fatalf("put: %v", err)
	}
	t.Logf("OK: blob depositado para %s — el wake debería dispararlo en segundos", to)
}

func TestMailboxFetchAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("MBX_ADDR")
	if addr == "" {
		t.Skip("MBX_ADDR no definido; sonda solo bajo demanda")
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

	got, err := n.MailboxFetch(addr)
	if err != nil {
		t.Fatalf("el nodo no atiende el buzón: %v", err)
	}
	t.Logf("OK: el nodo habla /krypta/mbx/get/1.0.0 (buzón de identidad efímera: %d sobres)", got)
}
