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

// Las DOS cajas, y esto es lo que hace que la moderación funcione.
//
// Con un solo nodo, el sitio donde se guardaba una denuncia y el sitio donde mirabas eran el
// mismo, así que apuntar a un host bastaba. Desde que hay dos (2 sep 2026) eso dejó de ser
// cierto y de la peor manera, **en silencio**: el cliente entrega al primero que acepte, así
// que una denuncia cae en cualquiera de las dos, y una herramienta que pregunta solo a la
// primera contesta "cero" sin dar ningún error.
//
// Peor todavía con la expulsión. Cuando un nodo rechaza a un expulsado responde con un error,
// que el cliente lee como "ese nodo no me acepta" y **pasa al siguiente de la lista**. Una
// expulsión escrita en una sola caja se esquiva con el cliente de serie, sin hacer nada
// especial — y como `QueryBoard` fusiona todos los nodos, la tarjeta la sigue viendo todo el
// mundo. Por eso `ban`/`unban` escriben en todas y **fallan si alguna falla**: media expulsión
// es peor que ninguna, porque parece que actuaste.
var defaultHosts = []string{"root@nyx.neto.chat", "root@nyx2.neto.chat"}

const (
	banlistPath    = "/var/lib/nyx/banned.txt"
	reportsDirPath = "/var/lib/nyx/reports"
)

// sshHosts devuelve las cajas sobre las que operar. NYX_NODE las sustituye (separadas por
// comas), para poder trabajar contra una sola cuando haga falta.
func sshHosts() []string {
	if h := os.Getenv("NYX_NODE"); h != "" {
		var hosts []string
		for _, part := range strings.Split(h, ",") {
			if part = strings.TrimSpace(part); part != "" {
				hosts = append(hosts, part)
			}
		}
		if len(hosts) > 0 {
			return hosts
		}
	}
	return defaultHosts
}

func runSSHOn(host, stdin string, args ...string) (string, error) {
	cmd := exec.Command("ssh", append([]string{"-o", "BatchMode=yes", "-o", "ConnectTimeout=15", host}, args...)...)
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	var out, errBuf bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errBuf
	if err := cmd.Run(); err != nil {
		return out.String(), fmt.Errorf("ssh %s: %v: %s", host, err, strings.TrimSpace(errBuf.String()))
	}
	return out.String(), nil
}

// runSSHAll ejecuta en todas las cajas y **acumula los fallos en vez de parar en el primero**:
// si una está caída, quiero que la otra quede escrita igual y que el mensaje diga exactamente
// cuál se quedó sin aplicar, para poder repetirlo ahí cuando vuelva.
func runSSHAll(stdin string, args ...string) (map[string]string, error) {
	outs := map[string]string{}
	var fallos []string
	for _, host := range sshHosts() {
		out, err := runSSHOn(host, stdin, args...)
		outs[host] = out
		if err != nil {
			fallos = append(fallos, err.Error())
		}
	}
	if len(fallos) > 0 {
		return outs, fmt.Errorf("%d de %d nodos fallaron: %s",
			len(fallos), len(sshHosts()), strings.Join(fallos, "; "))
	}
	return outs, nil
}

// currentBanlist devuelve la UNIÓN de las listas de las dos cajas, y avisa si divergen.
// La unión es lo correcto: si alguien está expulsado en una y no en la otra, está expulsado —
// lo que falta es propagarlo, no olvidarlo.
func currentBanlist() (map[string]bool, error) {
	outs, err := runSSHAll("", "cat "+banlistPath+" 2>/dev/null || true")
	if err != nil {
		return nil, err
	}
	set := map[string]bool{}
	porNodo := map[string]int{}
	for host, out := range outs {
		n := 0
		for _, line := range strings.Split(out, "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			if i := strings.IndexAny(line, " \t#"); i > 0 {
				line = strings.TrimSpace(line[:i])
			}
			set[line] = true
			n++
		}
		porNodo[host] = n
	}
	for host, n := range porNodo {
		if n != len(set) {
			fmt.Printf("AVISO: %s tiene %d expulsados y en total hay %d. Las cajas divergen:\n",
				host, n, len(set))
			fmt.Println("       vuelve a aplicar el ban que falte, o ese peer publica por ahí.")
		}
	}
	return set, nil
}

func banlistFromOutput(out string) map[string]bool {
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
	return set
}

// ban expulsa un PeerID del tablón, **en todas las cajas**, y registra la decisión.
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
		if _, err := runSSHAll(linea, "cat >> "+banlistPath); err != nil {
			// Deliberadamente ruidoso: una expulsión a medias deja al peer publicando por el
			// nodo que se quedó sin la línea, y encima con la sensación de haber actuado.
			fmt.Println("ATENCIÓN: la expulsión NO quedó aplicada en todas las cajas.")
			fmt.Println("Mientras falte una, ese PeerID puede seguir publicando por ella.")
			fmt.Printf("Repite el comando cuando vuelva, o aplícalo a mano en la que falló:\n")
			fmt.Printf("  ssh <nodo> 'echo \"%s\" >> %s'\n", strings.TrimSpace(peer), banlistPath)
			return err
		}
		fmt.Printf("Expulsado %s del tablón, en %d nodos.\n", peer, len(sshHosts()))
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
	if _, err := runSSHAll(peer, script); err != nil {
		// Aquí el fallo parcial es menos grave que en `ban` (queda expulsado de más, no de
		// menos), pero hay que decirlo igual: si no, el peer cree que puede volver y no puede.
		fmt.Println("ATENCIÓN: la expulsión sigue puesta en alguna caja. Repite cuando vuelva.")
		return err
	}
	fmt.Printf("Levantada la expulsión de %s, en %d nodos.\n", peer, len(sshHosts()))

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
	// Se consulta caja por caja y se enseña el desglose, no solo la suma: saber en cuál están
	// es lo que dice de dónde hay que descargarlas.
	hosts := sshHosts()
	conteo := map[string]string{}
	var caidos []string
	total := 0
	for _, host := range hosts {
		out, err := runSSHOn(host, "", "find "+reportsDirPath+" -name '*.json' 2>/dev/null | wc -l")
		if err != nil {
			conteo[host] = "?"
			caidos = append(caidos, host)
			continue
		}
		n := strings.TrimSpace(out)
		conteo[host] = n
		var v int
		if _, e := fmt.Sscanf(n, "%d", &v); e == nil {
			total += v
		}
	}

	st, err := loadState()
	if err != nil {
		return err
	}
	fmt.Println("Denuncias por nodo:")
	for _, host := range hosts {
		fmt.Printf("  %-24s %s\n", host, conteo[host])
	}
	fmt.Printf("Total:                    %d\n", total)
	fmt.Printf("Decisiones registradas:   %d\n", len(st.Reviewed))
	if len(caidos) > 0 {
		// Un "?" no es un cero. Sin este aviso, una caja inaccesible se leería como
		// "no hay denuncias ahí", que es justo la confusión que hay que evitar.
		fmt.Printf("\nAVISO: no pude consultar %s. Ese \"?\" no es un cero.\n",
			strings.Join(caidos, ", "))
	}
	fmt.Println()
	fmt.Println("El número de \"sin revisar\" sale al descargarlas, porque el id de cada denuncia")
	fmt.Println("se calcula de su contenido cifrado. Baja las de cada nodo con las denuncias en")
	fmt.Println("un directorio distinto y descífralos juntos:")
	for i, host := range hosts {
		if conteo[host] == "0" {
			continue
		}
		fmt.Printf("  scp -r %s:%s ./reports-$(date +%%F)-n%d\n", host, reportsDirPath, i+1)
	}
	// Con Printf y sin directivas: `go vet` toma el %F de `date` por una directiva de formato.
	fmt.Printf("%s\n", `  go run ./cmd/nyx-report decrypt ./reports-$(date +%F)-n1  # y n2, etc.`)
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
