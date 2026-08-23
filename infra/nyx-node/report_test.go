package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
)

func sendReport(t *testing.T, from host.Host, node peer.ID, blob []byte) error {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, reportProtocol)
	if err != nil {
		t.Fatalf("report stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(reportReq{V: 1, Blob: base64.StdEncoding.EncodeToString(blob)})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()
	return readOkErr(t, s)
}

// storedReports lee el disco como lo haría el operador por SSH.
func storedReports(t *testing.T, dir string) []reportRecord {
	t.Helper()
	var out []reportRecord
	_ = filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || !strings.HasSuffix(path, ".json") {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		var rec reportRecord
		if json.Unmarshal(data, &rec) == nil {
			out = append(out, rec)
		}
		return nil
	})
	return out
}

// --- tests ---

// El ciclo básico, y las dos propiedades que lo definen: el sobre llega **intacto** (el nodo no
// lo toca porque no puede leerlo) y el denunciante lo fija el nodo desde la identidad del
// stream, así que no se puede denunciar en nombre de otro.
func TestReportStoresOpaqueBlobWithRealSender(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	rpt := newReports(dir)
	rpt.attach(node)
	connect(t, a, node)

	sobre := []byte("\x00\x01cifrado-a-la-clave-del-operador\xff")
	if err := sendReport(t, a, node.ID(), sobre); err != nil {
		t.Fatalf("no se pudo denunciar: %v", err)
	}

	got := storedReports(t, dir)
	if len(got) != 1 {
		t.Fatalf("esperaba 1 denuncia, hay %d", len(got))
	}
	if got[0].From != a.ID().String() {
		t.Errorf("denunciante = %q, esperaba %q", got[0].From, a.ID().String())
	}
	raw, err := base64.StdEncoding.DecodeString(got[0].Blob)
	if err != nil {
		t.Fatalf("blob no es base64: %v", err)
	}
	if string(raw) != string(sobre) {
		t.Errorf("el sobre no llegó intacto: %q", raw)
	}
}

// Dos denuncias del mismo usuario son dos hechos distintos y **no** deben sobreescribirse,
// a diferencia de un like o de una tarjeta del tablón. Perder la primera sería perder una
// prueba.
func TestReportsFromSameReporterAccumulate(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	rpt := newReports(dir)
	rpt.attach(node)
	connect(t, a, node)

	for i := 0; i < 3; i++ {
		if err := sendReport(t, a, node.ID(), []byte(fmt.Sprintf("denuncia-%d", i))); err != nil {
			t.Fatalf("denuncia %d: %v", i, err)
		}
	}
	if got := storedReports(t, dir); len(got) != 3 {
		t.Fatalf("esperaba 3 denuncias acumuladas, hay %d", len(got))
	}
}

func TestReportQuotaAndSize(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	rpt := newReports(dir)
	rpt.maxPerReporter = 3
	rpt.maxBlob = 64
	rpt.attach(node)
	connect(t, a, node)

	if err := sendReport(t, a, node.ID(), make([]byte, 65)); err == nil {
		t.Error("un sobre por encima del tope debería rechazarse")
	}
	if err := sendReport(t, a, node.ID(), nil); err == nil {
		t.Error("una denuncia vacía debería rechazarse")
	}
	for i := 0; i < 3; i++ {
		if err := sendReport(t, a, node.ID(), []byte("ok")); err != nil {
			t.Fatalf("denuncia %d dentro de cuota: %v", i, err)
		}
	}
	if err := sendReport(t, a, node.ID(), []byte("una-mas")); err == nil {
		t.Error("pasada la cuota por denunciante debería rechazarse")
	}
}

// La cuota es **por denunciante**: que uno la agote no puede impedir denunciar a los demás.
// Sin esto, un abusador con una identidad silenciaría el mecanismo entero — el mismo fallo que
// `TestLikeQuotaDoesNotStarveMailbox` cubre para los likes.
func TestReportQuotaIsPerReporter(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	rpt := newReports(t.TempDir())
	rpt.maxPerReporter = 2
	rpt.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	for i := 0; i < 2; i++ {
		if err := sendReport(t, a, node.ID(), []byte("spam")); err != nil {
			t.Fatalf("A dentro de cuota: %v", err)
		}
	}
	if err := sendReport(t, a, node.ID(), []byte("spam")); err == nil {
		t.Fatal("A debería haber agotado su cuota")
	}
	if err := sendReport(t, b, node.ID(), []byte("denuncia real")); err != nil {
		t.Errorf("B no debería verse afectado por el abuso de A: %v", err)
	}
}

func TestReportSweepRespectsTTL(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	rpt := newReports(dir)
	rpt.attach(node)
	connect(t, a, node)

	if err := sendReport(t, a, node.ID(), []byte("vieja")); err != nil {
		t.Fatalf("denuncia: %v", err)
	}
	rpt.ttl = time.Nanosecond
	time.Sleep(2 * time.Millisecond)
	rpt.sweep()
	if got := storedReports(t, dir); len(got) != 0 {
		t.Errorf("el barrido debería haber borrado la denuncia caducada, quedan %d", len(got))
	}
}

// --- Expulsión del tablón --------------------------------------------------------------

func writeBanlist(t *testing.T, path string, lines ...string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatalf("escribir banlist: %v", err)
	}
}

// El test que de verdad importa de la expulsión: **la tarjeta que ya estaba publicada deja de
// verse**. Si la expulsión solo actuara al publicar, expulsar a alguien no retiraría nada hasta
// que su tarjeta caducara sola (48 h), y "el operador puede actuar sobre lo denunciado" —que es
// el requisito literal de Play— se quedaría en un gesto.
func TestBanHidesAlreadyPublishedCard(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	banPath := filepath.Join(t.TempDir(), "banned.txt")
	brd := newBoard(t.TempDir())
	brd.bans = newBanlist(banPath)
	brd.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	if err := boardPublish(t, a, node.ID(), "citas", []byte(`{"nick":"Abusador"}`)); err != nil {
		t.Fatalf("publicar: %v", err)
	}
	if err := boardPublish(t, b, node.ID(), "citas", []byte(`{"nick":"Normal"}`)); err != nil {
		t.Fatalf("publicar: %v", err)
	}
	if cards := boardQuery(t, b, node.ID(), "citas", 50); len(cards) != 2 {
		t.Fatalf("antes de expulsar debería haber 2 tarjetas, hay %d", len(cards))
	}

	// El operador edita el fichero por SSH; se admite un comentario en la misma línea.
	writeBanlist(t, banPath, "# denuncia 2026-08-23", a.ID().String()+"  # acoso")

	cards := boardQuery(t, b, node.ID(), "citas", 50)
	if len(cards) != 1 {
		t.Fatalf("tras expulsar debería quedar 1 tarjeta, hay %d", len(cards))
	}
	if cards[0].Peer != b.ID().String() {
		t.Errorf("quedó la tarjeta equivocada: %s", cards[0].Peer)
	}
}

func TestBannedPeerCannotPublish(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	banPath := filepath.Join(t.TempDir(), "banned.txt")
	writeBanlist(t, banPath, a.ID().String())
	brd := newBoard(t.TempDir())
	brd.bans = newBanlist(banPath)
	brd.attach(node)
	connect(t, a, node)

	if err := boardPublish(t, a, node.ID(), "citas", []byte(`{"nick":"Abusador"}`)); err == nil {
		t.Error("un peer expulsado no debería poder publicar")
	}
}

// La lista se relee cuando cambia el fichero: el operador no debería tener que reiniciar el
// nodo para que una expulsión tenga efecto (ni para levantarla).
func TestBanlistReloadsOnChange(t *testing.T) {
	banPath := filepath.Join(t.TempDir(), "banned.txt")
	bans := newBanlist(banPath)

	if bans.isBanned("12D3KooWAlguien") {
		t.Error("sin fichero no debería haber expulsados")
	}
	writeBanlist(t, banPath, "12D3KooWAlguien")
	if !bans.isBanned("12D3KooWAlguien") {
		t.Error("debería recoger la expulsión nueva sin reiniciar")
	}
	// mtime con granularidad de segundo en algunos sistemas de ficheros: se fuerza el cambio.
	time.Sleep(10 * time.Millisecond)
	writeBanlist(t, banPath, "# ya no")
	_ = os.Chtimes(banPath, time.Now().Add(time.Second), time.Now().Add(time.Second))
	if bans.isBanned("12D3KooWAlguien") {
		t.Error("debería recoger que se levantó la expulsión")
	}
}

// Un PeerID es un nombre de directorio; sin sanear, un "../.." se escaparía del reportdir.
func TestReportPeerPathCannotEscape(t *testing.T) {
	if strings.ContainsAny(sanitizePeer("../../etc/passwd"), "./\\") {
		t.Errorf("sanitizePeer dejó separadores de ruta: %q", sanitizePeer("../../etc/passwd"))
	}
	if sanitizePeer("") == "" {
		t.Error("un PeerID vacío no puede dar un nombre vacío")
	}
	real := "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3"
	if sanitizePeer(real) != real {
		t.Errorf("un PeerID legítimo no debería alterarse: %q", sanitizePeer(real))
	}
}
