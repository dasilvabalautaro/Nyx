// Cifrado del sobre de denuncia (plan 4.4).
//
// Vive en Go y no en Kotlin por una razón concreta: X25519 en Java (`KeyAgreement("XDH")`)
// llegó a Android en API 33 y el `minSdk` del proyecto es 30, así que en Kotlin no está
// garantizado. Aquí ya estaba `curve25519` para el ECDH de los contactos, y el puente es donde
// vive el resto de la criptografía de transporte.
//
// Formato, mismo idioma que `IdentityBackup` de la app:
//
//	"NYXR1" ‖ clavePúblicaEfímera(32) ‖ nonce(12) ‖ AES-256-GCM(denuncia)
//
// Dos decisiones que conviene no deshacer:
//
//   - La clave del denunciante es **efímera**, no su identidad. Si se usara su clave larga,
//     quien tuviera la privada del operador podría además **demostrar** quién escribió cada
//     denuncia; con una efímera, el sobre no prueba autoría por sí solo. (El nodo sí registra
//     quién lo entregó, por la identidad del stream — pero eso es un dato operativo del nodo,
//     no una firma dentro del sobre, y se puede borrar sin tocar la denuncia.)
//   - El magic va como **AAD** del GCM, así que un sobre con la cabecera manipulada no
//     descifra en vez de descifrar a basura.
//
// La herramienta que abre estos sobres es `infra/nyx-node/cmd/nyx-report`, y corre en la
// máquina del operador; la clave privada no toca nunca el nodo.
package bridge

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"strings"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	reportMagic    = "NYXR1"
	reportHKDFInfo = "nyx-report-v1"
)

// SealReport cifra una denuncia para el operador. `operatorPubHex` son los 32 bytes de la clave
// pública X25519 del operador en hexadecimal, tal como los imprime `nyx-report keygen`.
//
// Firma amistosa con gomobile: string + []byte de entrada, []byte + error de salida.
func SealReport(operatorPubHex string, plaintext []byte) ([]byte, error) {
	pub, err := hex.DecodeString(strings.TrimSpace(operatorPubHex))
	if err != nil {
		return nil, fmt.Errorf("clave del operador no es hexadecimal: %w", err)
	}
	if len(pub) != 32 {
		return nil, fmt.Errorf("clave del operador debe medir 32 bytes, mide %d", len(pub))
	}
	if len(plaintext) == 0 {
		return nil, errors.New("denuncia vacía")
	}

	var ephPriv [32]byte
	if _, err := io.ReadFull(rand.Reader, ephPriv[:]); err != nil {
		return nil, err
	}
	ephPub, err := curve25519.X25519(ephPriv[:], curve25519.Basepoint)
	if err != nil {
		return nil, err
	}
	shared, err := curve25519.X25519(ephPriv[:], pub)
	if err != nil {
		return nil, fmt.Errorf("ECDH con la clave del operador: %w", err)
	}

	key := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256.New, shared, nil, []byte(reportHKDFInfo)), key); err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}

	out := make([]byte, 0, len(reportMagic)+32+len(nonce)+len(plaintext)+gcm.Overhead())
	out = append(out, reportMagic...)
	out = append(out, ephPub...)
	out = append(out, nonce...)
	return gcm.Seal(out, nonce, plaintext, []byte(reportMagic)), nil
}
