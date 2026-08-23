package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// Estado local de la revisión: qué denuncias ya se miraron y qué se decidió con cada una.
//
// # Por qué hace falta
//
// Sin esto, `decrypt` reimprime el árbol entero cada vez, y las denuncias viven 180 días. A las
// pocas semanas revisar es releer decenas de cosas ya vistas buscando las nuevas — que es
// exactamente el motivo por el que uno deja de revisar. El registro de decisiones resuelve la
// otra mitad: si se decide **no** actuar, hoy no queda rastro de que hubo un proceso, y "el
// desarrollador puede actuar sobre lo denunciado" es algo que hay que poder demostrar.
//
// # Dónde vive
//
// Junto a la clave privada del operador, en `~/keys/nyx-operator/`. Mismo dominio de confianza y
// misma obligación de respaldo: el fichero dice a quién se expulsó y por qué, así que no es un
// caché desechable.
//
// # Por qué el id es un hash y no la ruta
//
// El operador copia el árbol con `scp` a un directorio con fecha, así que la ruta cambia en cada
// revisión. El identificador es `sha256(blob)[:16]`: estable frente a copias, renombrados y a que
// el nodo barra el original.
type reviewState struct {
	path     string
	Reviewed map[string]decision `json:"reviewed"`
}

type decision struct {
	Action     string `json:"action"`      // "ban" | "dismiss"
	Peer       string `json:"peer"`        // denunciado
	Reason     string `json:"reason"`      // nota del operador
	ReviewedAt string `json:"reviewed_at"` // RFC3339
}

func reportID(blob []byte) string {
	sum := sha256.Sum256(blob)
	return hex.EncodeToString(sum[:])[:16]
}

func statePath() string {
	return filepath.Join(filepath.Dir(defaultKeyPath()), "revisadas.json")
}

func loadState() (*reviewState, error) {
	st := &reviewState{path: statePath(), Reviewed: map[string]decision{}}
	data, err := os.ReadFile(st.path)
	if os.IsNotExist(err) {
		return st, nil // primera vez: no es un error
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, st); err != nil {
		return nil, fmt.Errorf("estado ilegible en %s: %w", st.path, err)
	}
	if st.Reviewed == nil {
		st.Reviewed = map[string]decision{}
	}
	return st, nil
}

// save escribe con tmp+rename: una interrupción a mitad no puede dejar el registro de
// decisiones corrupto, que sería perder la trazabilidad entera.
func (s *reviewState) save() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *reviewState) isReviewed(id string) bool {
	_, ok := s.Reviewed[id]
	return ok
}

func (s *reviewState) record(id, action, peer, reason string) error {
	s.Reviewed[id] = decision{
		Action:     action,
		Peer:       peer,
		Reason:     reason,
		ReviewedAt: time.Now().Format(time.RFC3339),
	}
	return s.save()
}

// history devuelve las decisiones de la más reciente a la más antigua.
func (s *reviewState) history() []struct {
	ID string
	decision
} {
	out := make([]struct {
		ID string
		decision
	}, 0, len(s.Reviewed))
	for id, d := range s.Reviewed {
		out = append(out, struct {
			ID string
			decision
		}{id, d})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ReviewedAt > out[j].ReviewedAt })
	return out
}
