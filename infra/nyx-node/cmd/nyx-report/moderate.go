package main

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
)

// Comandos de moderación: expulsar, levantar la expulsión y archivar una denuncia sin acción.
//
// Todos escriben en el nodo por SSH y dejan registro local de la decisión. Que sean un comando
// y no tres pasos a mano no es comodidad: el paso que se olvida siempre es el registro, y sin
// registro no hay forma de enseñar que hubo un proceso — ni de recordar dentro de seis meses por
// qué se expulsó a alguien.

const (
	defaultHost    = "root@nyx.neto.chat"
	banlistPath    = "/var/lib/nyx/banned.txt"
	reportsDirPath = "/var/lib/nyx/reports"
)

func sshHost() string {
	if h := os.Getenv("NYX_NODE"); h != "" {
		return h
	}
	return defaultHost
}

func runSSH(stdin string, args ...string) (string, error) {
	cmd := exec.Command("ssh", append([]string{sshHost()}, args...)...)
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	var out, errBuf bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errBuf
	if err := cmd.Run(); err != nil {
		return out.String(), fmt.Errorf("ssh %s: %v: %s", sshHost(), err, strings.TrimSpace(errBuf.String()))
	}
	return out.String(), nil
}

func currentBanlist() (map[string]bool, error) {
	// `|| true` para que un fichero que aún no existe no cuente como fallo de SSH.
	out, err := runSSH("", "cat "+banlistPath+" 2>/dev/null || true")
	if err != nil {
		return nil, err
	}
	set := map[string]bool{}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if i := strings.IndexAny(line, " \t#"); i > 0 {
			line = strings.TrimSpace(line[:i])
		}
		set[line] = true
	}
	return set, nil
}

// ban expulsa un PeerID del tablón y registra la decisión.
func ban(peer, note, reportID string) error {
	if strings.TrimSpace(peer) == "" {
		return fmt.Errorf("hace falta el PeerID a expulsar")
	}
	existing, err := currentBanlist()
	if err != nil {
		return err
	}
	if existing[peer] {
		fmt.Printf("%s ya estaba expulsado; no se duplica la línea.\n", peer)
	} else {
		// Por stdin y no interpolado en el comando: el PeerID viene de una denuncia, o sea de
		// fuera, y meterlo en una cadena de shell remota es pedir una inyección.
		linea := fmt.Sprintf("%s  # %s %s\n", peer, time.Now().Format("2006-01-02"), sanitizeComment(note))
		if _, err := runSSH(linea, "cat >> "+banlistPath); err != nil {
			return err
		}
		fmt.Printf("Expulsado %s del tablón.\n", peer)
		fmt.Println("Efecto inmediato: no puede publicar, su tarjeta ya publicada deja de verse,")
		fmt.Println("y el barrido horario la borra. El nodo relee la lista solo, sin reiniciar.")
	}

	st, err := loadState()
	if err != nil {
		return err
	}
	id := reportID
	if id == "" {
		id = "manual-" + time.Now().Format("20060102-150405")
	}
	return st.record(id, "ban", peer, note)
}

func unban(peer string) error {
	if strings.TrimSpace(peer) == "" {
		return fmt.Errorf("hace falta el PeerID")
	}
	existing, err := currentBanlist()
	if err != nil {
		return err
	}
	if !existing[peer] {
		return fmt.Errorf("%s no está en la lista de expulsados", peer)
	}
	// grep -v sobre un fichero temporal: más seguro que sed -i con una cadena externa.
	script := fmt.Sprintf(
		"grep -v -F -- \"$(cat)\" %s > %s.tmp && mv %s.tmp %s",
		banlistPath, banlistPath, banlistPath, banlistPath)
	if _, err := runSSH(peer, script); err != nil {
		return err
	}
	fmt.Printf("Levantada la expulsión de %s.\n", peer)

	st, err := loadState()
	if err != nil {
		return err
	}
	return st.record("unban-"+time.Now().Format("20060102-150405"), "unban", peer, "")
}

// dismiss archiva una denuncia revisada sin acción. Existe porque decidir que algo NO merece
// expulsión también es una decisión, y hoy no dejaba ningún rastro.
func dismiss(id, note string) error {
	if strings.TrimSpace(id) == "" {
		return fmt.Errorf("hace falta el id de la denuncia (sale en `decrypt`)")
	}
	st, err := loadState()
	if err != nil {
		return err
	}
	if err := st.record(id, "dismiss", "", note); err != nil {
		return err
	}
	fmt.Printf("Denuncia %s archivada sin acción.\n", id)
	return nil
}

func showLog() error {
	st, err := loadState()
	if err != nil {
		return err
	}
	h := st.history()
	if len(h) == 0 {
		fmt.Println("Todavía no hay decisiones registradas.")
		return nil
	}
	fmt.Printf("%-18s %-9s %-12s %s\n", "FECHA", "ACCIÓN", "DENUNCIA", "PEER / NOTA")
	for _, d := range h {
		fecha := d.ReviewedAt
		if len(fecha) > 16 {
			fecha = fecha[:16]
		}
		detalle := d.Peer
		if d.Reason != "" {
			detalle = strings.TrimSpace(detalle + " · " + d.Reason)
		}
		fmt.Printf("%-18s %-9s %-12s %s\n", fecha, d.Action, d.ID, detalle)
	}
	return nil
}

// status contesta lo único que hace falta saber para decidir si toca ponerse: cuántas hay sin
// revisar. Consulta el nodo directamente, sin descargar nada.
func status() error {
	out, err := runSSH("", "find "+reportsDirPath+" -name '*.json' 2>/dev/null | wc -l")
	if err != nil {
		return err
	}
	total := strings.TrimSpace(out)

	st, err := loadState()
	if err != nil {
		return err
	}
	fmt.Printf("Nodo:       %s\n", sshHost())
	fmt.Printf("Denuncias en el nodo:     %s\n", total)
	fmt.Printf("Decisiones registradas:   %d\n", len(st.Reviewed))
	fmt.Println()
	fmt.Println("El número de \"sin revisar\" sale al descargarlas, porque el id de cada denuncia")
	fmt.Println("se calcula de su contenido cifrado:")
	fmt.Printf("  scp -r %s:%s ./reports-$(date +%%F)\n", sshHost(), reportsDirPath)
	// Con Printf y sin directivas: `go vet` toma el %F de `date` por una directiva de formato.
	fmt.Printf("%s\n", `  go run ./cmd/nyx-report decrypt ./reports-$(date +%F)`)
	return nil
}

// sanitizeComment deja el comentario en una sola línea: va a un fichero de una línea por PeerID,
// y un salto de línea partiría la entrada en dos y podría inventar una expulsión.
func sanitizeComment(s string) string {
	s = strings.ReplaceAll(s, "\n", " ")
	s = strings.ReplaceAll(s, "\r", " ")
	if len(s) > 120 {
		s = s[:120]
	}
	return strings.TrimSpace(s)
}
