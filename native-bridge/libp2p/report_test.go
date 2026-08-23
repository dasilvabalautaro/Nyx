package bridge

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"time"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"strings"
	"testing"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

// abre implementa el lado del operador, igual que `infra/nyx-node/cmd/nyx-report`. Está aquí
// duplicado a propósito: si algún día el formato cambia en un lado y no en el otro, este test
// lo nota sin necesidad de cruzar módulos de Go.
func abre(t *testing.T, priv, sealed []byte) ([]byte, error) {
	t.Helper()
	if string(sealed[:5]) != reportMagic {
		t.Fatalf("magic incorrecto: %q", sealed[:5])
	}
	ephPub, nonce, ct := sealed[5:37], sealed[37:49], sealed[49:]
	shared, err := curve25519.X25519(priv, ephPub)
	if err != nil {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256.New, shared, nil, []byte(reportHKDFInfo)), key); err != nil {
		return nil, err
	}
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	return gcm.Open(nil, nonce, ct, []byte(reportMagic))
}

func parDeClaves(t *testing.T) (priv, pubHex string) {
	t.Helper()
	p := bytes.Repeat([]byte{7}, 32)
	pub, err := curve25519.X25519(p, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(p), hex.EncodeToString(pub)
}

func TestSealReportRoundTrip(t *testing.T) {
	privHex, pubHex := parDeClaves(t)
	priv, _ := hex.DecodeString(privHex)

	denuncia := []byte(`{"peer":"12D3KooWAbusador","nota":"acoso","fragmento":"…"}`)
	sealed, err := SealReport(pubHex, denuncia)
	if err != nil {
		t.Fatalf("SealReport: %v", err)
	}
	plain, err := abre(t, priv, sealed)
	if err != nil {
		t.Fatalf("el operador no pudo abrir el sobre: %v", err)
	}
	if !bytes.Equal(plain, denuncia) {
		t.Errorf("la denuncia no sobrevivió: %q", plain)
	}
}

// Dos sobres del mismo texto tienen que salir distintos: la clave efímera y el nonce son
// nuevos cada vez. Si salieran iguales, el operador (o quien viera el tráfico) podría saber
// que dos denuncias dicen lo mismo sin abrirlas.
func TestSealReportIsNotDeterministic(t *testing.T) {
	_, pubHex := parDeClaves(t)
	a, _ := SealReport(pubHex, []byte("misma denuncia"))
	b, _ := SealReport(pubHex, []byte("misma denuncia"))
	if bytes.Equal(a, b) {
		t.Error("dos sobres del mismo texto no deberían ser idénticos")
	}
}

// El magic va como AAD, así que manipular la cabecera tiene que romper el descifrado, no
// producir basura silenciosa.
func TestSealReportRejectsTamperedHeader(t *testing.T) {
	privHex, pubHex := parDeClaves(t)
	priv, _ := hex.DecodeString(privHex)
	sealed, _ := SealReport(pubHex, []byte("denuncia"))

	roto := append([]byte(nil), sealed...)
	roto[len(roto)-1] ^= 0xFF // un bit del tag
	if _, err := abre(t, priv, roto); err == nil {
		t.Error("un sobre manipulado no debería descifrar")
	}
}

// Otra clave privada no abre el sobre: es lo que hace que el nodo no pueda leer las denuncias
// que almacena.
func TestSealReportOnlyOpensWithTheRightKey(t *testing.T) {
	_, pubHex := parDeClaves(t)
	sealed, _ := SealReport(pubHex, []byte("denuncia"))
	otra := bytes.Repeat([]byte{9}, 32)
	if _, err := abre(t, otra, sealed); err == nil {
		t.Error("una clave distinta no debería abrir el sobre")
	}
}

func TestSealReportValidatesInput(t *testing.T) {
	_, pubHex := parDeClaves(t)
	if _, err := SealReport(pubHex, nil); err == nil {
		t.Error("una denuncia vacía debería rechazarse")
	}
	if _, err := SealReport("no-es-hex", []byte("x")); err == nil {
		t.Error("una clave no hexadecimal debería rechazarse")
	}
	if _, err := SealReport(strings.Repeat("ab", 16), []byte("x")); err == nil {
		t.Error("una clave de 16 bytes debería rechazarse")
	}
}

// Sonda de un solo uso, en el mismo estilo que las de nodo vivo (`MBX_ADDR=…`): se salta sola
// si no se le pide nada. Cifra con la clave pública REAL del operador y deja el fichero con la
// forma exacta que guarda el nodo, para poder abrirlo con `nyx-report decrypt` y comprobar que
// el puente y la herramienta —que están en módulos de Go distintos— hablan el mismo formato.
//
//	REPORT_OUT=/tmp/p/xx.json REPORT_PUB=<hex> go test -run TestSealReportProbe ./...
func TestSealReportProbe(t *testing.T) {
	out, pub := os.Getenv("REPORT_OUT"), os.Getenv("REPORT_PUB")
	if out == "" || pub == "" {
		t.Skip("sin REPORT_OUT/REPORT_PUB: sonda desactivada")
	}
	sealed, err := SealReport(pub, []byte("denuncia de prueba: acoso desde 12D3KooWEjemplo\nnota libre del usuario"))
	if err != nil {
		t.Fatalf("SealReport: %v", err)
	}
	rec := map[string]any{
		"from": "12D3KooWDenunciante",
		"ts":   time.Now().UnixMilli(),
		"blob": base64.StdEncoding.EncodeToString(sealed),
	}
	data, _ := json.Marshal(rec)
	if err := os.MkdirAll(filepath.Dir(out), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(out, data, 0o600); err != nil {
		t.Fatal(err)
	}
	t.Logf("sobre escrito en %s (%d bytes cifrados)", out, len(sealed))
}
