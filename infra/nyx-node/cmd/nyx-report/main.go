// Herramienta del operador: genera su par de claves y descifra las denuncias.
//
// Corre en la máquina del operador, **nunca en el nodo**. Ese es el punto entero del diseño:
// el nodo guarda sobres que no puede abrir, así que comprometer la caja no expone ni una
// denuncia. La clave privada no debe copiarse nunca al VPS.
//
// Formato del sobre, el mismo idioma que `IdentityBackup` de la app:
//
//	"NYXR1" ‖ clavePúblicaEfímera(32) ‖ nonce(12) ‖ AES-256-GCM(denuncia)
//
// La clave se deriva con HKDF-SHA256 del ECDH X25519 entre una clave **efímera** del
// denunciante y la pública del operador. Efímera y no la identidad del denunciante: si se usara
// su clave larga, cualquiera con la privada del operador podría además **demostrar** quién
// escribió cada denuncia. Con una efímera, el sobre no prueba autoría por sí solo. (El nodo
// registra igualmente quién lo entregó, por la identidad del stream — pero eso es un dato
// operativo suyo, no una firma dentro del sobre.)
//
// El magic va como AAD del GCM, así que un sobre con la cabecera cambiada no descifra.
//
//	go run ./cmd/nyx-report keygen                 # una vez; escribe la privada y saca la pública
//	go run ./cmd/nyx-report decrypt reports/       # descifra un fichero o un árbol entero
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	magic    = "NYXR1"
	hkdfInfo = "nyx-report-v1"
)

func defaultKeyPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "operator.key"
	}
	return filepath.Join(home, "keys", "nyx-operator", "operator.key")
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(1)
	}
	var err error
	switch os.Args[1] {
	case "keygen":
		err = keygen(defaultKeyPath())
	case "status":
		err = status()
	case "decrypt":
		if len(os.Args) < 3 {
			usage()
			os.Exit(1)
		}
		// Por defecto solo lo nuevo: releer 180 días de denuncias buscando las de hoy es
		// exactamente el motivo por el que uno deja de revisar.
		todas := len(os.Args) > 3 && os.Args[3] == "-todas"
		err = decryptPath(defaultKeyPath(), os.Args[2], todas)
	case "ban":
		if len(os.Args) < 3 {
			usage()
			os.Exit(1)
		}
		err = ban(os.Args[2], arg(3), arg(4))
	case "unban":
		if len(os.Args) < 3 {
			usage()
			os.Exit(1)
		}
		err = unban(os.Args[2])
	case "dismiss":
		if len(os.Args) < 3 {
			usage()
			os.Exit(1)
		}
		err = dismiss(os.Args[2], arg(3))
	case "log":
		err = showLog()
	default:
		usage()
		os.Exit(1)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
}

func arg(i int) string {
	if len(os.Args) > i {
		return os.Args[i]
	}
	return ""
}

func usage() {
	fmt.Fprintf(os.Stderr, `nyx-report — revisión de denuncias y moderación del tablón

  status                        cuántas denuncias hay en el nodo y cuántas decisiones llevas
  decrypt <ruta> [-todas]       descifra las NUEVAS (con -todas, también las ya revisadas)
  ban <peerid> [nota] [id]      expulsa del tablón y registra la decisión
  unban <peerid>                levanta la expulsión
  dismiss <id-denuncia> [nota]  archiva una denuncia revisada sin acción
  log                           historial de decisiones
  keygen                        genera el par del operador (una sola vez)

Clave privada: %s
Registro:      %s
Nodo:          %s  (cambiable con NYX_NODE=usuario@host)
`, defaultKeyPath(), statePath(), sshHost())
}

func keygen(path string) error {
	// Negarse a sobreescribir es deliberado: regenerar la clave deja ilegibles todas las
	// denuncias anteriores **y** obliga a publicar una versión nueva en Play, porque la
	// pública va compilada en el APK. No es algo que deba poder pasar por un comando repetido.
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("ya existe %s; borrarlo a mano si de verdad quieres una clave nueva "+
			"(deja ilegibles las denuncias viejas y exige publicar otra versión en Play)", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}

	var priv [32]byte
	if _, err := io.ReadFull(rand.Reader, priv[:]); err != nil {
		return err
	}
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, []byte(hex.EncodeToString(priv[:])+"\n"), 0o600); err != nil {
		return err
	}

	fmt.Printf("Clave privada del operador escrita en %s (permisos 600).\n\n", path)
	fmt.Printf("Clave PÚBLICA, para compilar en la app:\n\n    %s\n\n", hex.EncodeToString(pub))
	fmt.Print(`Antes de seguir, respáldala fuera de esta máquina. Es de la misma familia que
node.key: si se pierde, todas las denuncias quedan ilegibles para siempre y cambiar
la clave exige publicar una versión nueva en Play, porque la pública viaja dentro
del APK instalado. No la copies nunca al VPS: el nodo no debe poder leer denuncias.
`)
	return nil
}

func loadPrivate(path string) ([]byte, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("no se pudo leer la clave del operador (%s): %w", path, err)
	}
	priv, err := hex.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(priv) != 32 {
		return nil, fmt.Errorf("la clave de %s no son 32 bytes en hexadecimal", path)
	}
	return priv, nil
}

// open descifra un sobre. Devuelve error si el magic, el tamaño o el tag no cuadran.
func open(priv, sealed []byte) ([]byte, error) {
	if len(sealed) < len(magic)+32+12+16 {
		return nil, fmt.Errorf("sobre demasiado corto (%d bytes)", len(sealed))
	}
	if string(sealed[:len(magic)]) != magic {
		return nil, fmt.Errorf("no es un sobre de denuncia de Nyx")
	}
	ephPub := sealed[len(magic) : len(magic)+32]
	nonce := sealed[len(magic)+32 : len(magic)+32+12]
	ct := sealed[len(magic)+32+12:]

	shared, err := curve25519.X25519(priv, ephPub)
	if err != nil {
		return nil, fmt.Errorf("ECDH: %w", err)
	}
	key := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256New, shared, nil, []byte(hkdfInfo)), key); err != nil {
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
	// El magic como AAD: un sobre con la cabecera manipulada no descifra.
	return gcm.Open(nil, nonce, ct, []byte(magic))
}

func decryptPath(keyPath, target string, todas bool) error {
	priv, err := loadPrivate(keyPath)
	if err != nil {
		return err
	}
	st, err := loadState()
	if err != nil {
		return err
	}
	n, saltadas := 0, 0
	err = filepath.Walk(target, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || !strings.HasSuffix(path, ".json") {
			return nil
		}
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil
		}
		var rec struct {
			From string `json:"from"`
			Ts   int64  `json:"ts"`
			Blob string `json:"blob"`
		}
		if json.Unmarshal(data, &rec) != nil {
			return nil
		}
		sealed, decErr := base64.StdEncoding.DecodeString(rec.Blob)
		if decErr != nil {
			fmt.Printf("── %s\n   (blob ilegible)\n\n", path)
			return nil
		}
		id := reportID(sealed)
		if !todas && st.isReviewed(id) {
			saltadas++
			return nil
		}
		n++
		fmt.Printf("── denuncia %s\n   entregada por: %s\n   recibida:      %s\n",
			id, rec.From, time.UnixMilli(rec.Ts).Format(time.RFC3339))
		plain, openErr := open(priv, sealed)
		if openErr != nil {
			fmt.Printf("   NO SE PUDO DESCIFRAR: %v\n\n", openErr)
			return nil
		}
		fmt.Printf("   contenido:\n%s\n\n", indent(string(plain)))
		return nil
	})
	if err != nil {
		return err
	}
	switch {
	case n == 0 && saltadas > 0:
		if saltadas == 1 {
			fmt.Printf("Nada nuevo: la única denuncia de %s ya estaba revisada.\n", target)
		} else {
			fmt.Printf("Nada nuevo: las %d denuncias de %s ya estaban revisadas.\n", saltadas, target)
		}
		fmt.Println("Para verlas otra vez: decrypt <ruta> -todas")
	case n == 0:
		fmt.Println("No hay denuncias en", target)
	default:
		fmt.Printf("%d sin revisar", n)
		if saltadas > 0 {
			fmt.Printf(" (%d ya revisadas, ocultas)", saltadas)
		}
		fmt.Println(".")
		fmt.Println()
		fmt.Println("Para cada una, decide y deja constancia:")
		fmt.Println("  nyx-report ban <peerid-denunciado> \"motivo\" <id-denuncia>")
		fmt.Println("  nyx-report dismiss <id-denuncia> \"por qué no se actúa\"")
	}
	return nil
}

func indent(s string) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	for i, l := range lines {
		lines[i] = "      " + l
	}
	return strings.Join(lines, "\n")
}
