// Lado cliente del tablón de perfiles y de la bandeja de "me gusta" (Fase 3). El servidor
// vive en infra/nyx-node/{board.go,like.go}; aquí solo se habla su protocolo.
//
// Convención con gomobile, la misma que ya usa el buzón: nada de mapas ni de listas de
// structs cruzando la frontera. Lo que entra y sale son `string`, `[]byte`, `int`, `bool` y
// `error`; una lista de tarjetas viaja como **JSON en un string** y la parsea Kotlin.
//
// Política multi-nodo, distinta para cada operación y a propósito:
//
//   - **Publicar**: al primero que acepte (failover), como `MailboxPut`. La tarjeta acaba en
//     un nodo y con eso basta para ser descubrible.
//   - **Consultar**: a TODOS, fusionando por autor y quedándose con la más reciente. Si cada
//     nodo tiene un trozo del tablón, consultar uno solo daría una vista parcial.
//   - **Borrar**: en TODOS, y **el error se reporta aunque falle uno solo**. Es la asimetría
//     importante: publicar en un nodo basta, pero borrar en todos menos uno deja tu perfil
//     público en ese uno. Quien llama tiene que enterarse y volver a intentarlo.
package bridge

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const (
	boardPublishProtocol = protocol.ID("/nyx/board/publish/1.0.0")
	boardQueryProtocol   = protocol.ID("/nyx/board/query/1.0.0")
	boardDeleteProtocol  = protocol.ID("/nyx/board/delete/1.0.0")

	likePutProtocol = protocol.ID("/nyx/like/put/1.0.0")
	likeGetProtocol = protocol.ID("/nyx/like/get/1.0.0")
)

// boardCard es la tarjeta tal y como la devuelve el nodo. `Card` es base64 opaco: el nodo no
// lo interpreta y este puente tampoco — lo decodifica Kotlin.
type boardCard struct {
	Peer string `json:"peer"`
	Ts   int64  `json:"ts"`
	Card string `json:"card"`
	Done bool   `json:"done,omitempty"`
	Err  string `json:"err,omitempty"`
	OK   bool   `json:"ok,omitempty"`
}

type likeEnvelope struct {
	From string `json:"from"`
	Ts   int64  `json:"ts"`
	Blob string `json:"blob"`
	Done bool   `json:"done,omitempty"`
	Err  string `json:"err,omitempty"`
	OK   bool   `json:"ok,omitempty"`
}

// LikeHandler lo implementa Kotlin para recibir los "me gusta" retirados. Igual que
// `MailboxHandler`: debe procesar **de forma síncrona** y devolver true solo cuando el like
// esté persistido — solo se ack'ea (y por tanto se borra en el nodo) lo confirmado, así que
// un fallo a media tanda se reentrega en la siguiente pasada en vez de perderse. Un like
// perdido es un match que nunca ocurre.
type LikeHandler interface {
	OnLike(from string, ts int64, data []byte) bool
}

// SetLikeHandler registra el handler al que LikeFetch entrega los likes.
func (n *Node) SetLikeHandler(h LikeHandler) { n.likeHandler = h }

// PublishCard publica (o actualiza) mi tarjeta en `category`. El autor lo fija el nodo desde
// la identidad del stream, así que no viaja en la petición y no se puede falsear.
func (n *Node) PublishCard(boardAddrs string, category string, card []byte) error {
	nodes := parseAddrInfos(boardAddrs)
	if len(nodes) == 0 {
		return errors.New("sin nodo de tablón configurado")
	}
	var errs []string
	for _, ai := range nodes {
		if err := n.publishCardTo(ai, category, card); err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
			continue
		}
		return nil
	}
	return errors.New("tablón: " + strings.Join(errs, "; "))
}

func (n *Node) publishCardTo(ai peer.AddrInfo, category string, card []byte) error {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return err
	}
	s, err := n.h.NewStream(ctx, ai.ID, boardPublishProtocol)
	if err != nil {
		return err
	}
	defer s.Close()
	req, err := json.Marshal(map[string]any{
		"v":    1,
		"cat":  category,
		"card": base64.StdEncoding.EncodeToString(card),
	})
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(s, "%s\n", req); err != nil {
		return err
	}
	_ = s.CloseWrite()
	return readAck(s)
}

// QueryBoard devuelve las tarjetas de `category` como **JSON**: `[{"peer","ts","card"},…]`,
// `card` en base64. Consulta todos los nodos y fusiona por autor quedándose con la tarjeta
// más reciente — un mismo perfil publicado en dos nodos aparece una sola vez.
func (n *Node) QueryBoard(boardAddrs string, category string, limit int) (string, error) {
	nodes := parseAddrInfos(boardAddrs)
	if len(nodes) == 0 {
		return "", errors.New("sin nodo de tablón configurado")
	}
	newest := map[string]boardCard{}
	var errs []string
	for _, ai := range nodes {
		cards, err := n.queryBoardFrom(ai, category, limit)
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
			continue
		}
		for _, c := range cards {
			if prev, ok := newest[c.Peer]; !ok || c.Ts > prev.Ts {
				newest[c.Peer] = c
			}
		}
	}
	if len(errs) == len(nodes) {
		return "", errors.New("tablón: " + strings.Join(errs, "; "))
	}

	out := make([]boardCard, 0, len(newest))
	for _, c := range newest {
		out = append(out, c)
	}
	// Más recientes primero, y recorte final: el límite es del conjunto fusionado, no de
	// cada nodo por separado.
	sortCardsDesc(out)
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	data, err := json.Marshal(out)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (n *Node) queryBoardFrom(ai peer.AddrInfo, category string, limit int) ([]boardCard, error) {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return nil, err
	}
	s, err := n.h.NewStream(ctx, ai.ID, boardQueryProtocol)
	if err != nil {
		return nil, err
	}
	defer s.Close()
	req, err := json.Marshal(map[string]any{"v": 1, "cat": category, "limit": limit})
	if err != nil {
		return nil, err
	}
	if _, err := fmt.Fprintf(s, "%s\n", req); err != nil {
		return nil, err
	}
	_ = s.CloseWrite()

	var cards []boardCard
	r := bufio.NewReader(s)
	for {
		line, err := r.ReadBytes('\n')
		if err != nil && len(line) == 0 {
			return cards, nil // el nodo cerró; lo leído vale
		}
		var c boardCard
		if err := json.Unmarshal(line, &c); err != nil {
			return cards, fmt.Errorf("tarjeta ilegible: %w", err)
		}
		if c.Done {
			return cards, nil
		}
		cards = append(cards, c)
	}
}

// DeleteCard quita mi tarjeta de `category` (o de todas si va vacía) en **todos** los nodos.
// Devuelve error si falla en alguno: a diferencia de publicar, aquí un éxito parcial deja el
// perfil visible en el nodo que falló, y quien llama tiene que poder reintentarlo.
func (n *Node) DeleteCard(boardAddrs string, category string) error {
	nodes := parseAddrInfos(boardAddrs)
	if len(nodes) == 0 {
		return errors.New("sin nodo de tablón configurado")
	}
	var errs []string
	for _, ai := range nodes {
		if err := n.deleteCardOn(ai, category); err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
		}
	}
	if len(errs) > 0 {
		return errors.New("tablón (tarjeta AÚN visible en esos nodos): " + strings.Join(errs, "; "))
	}
	return nil
}

func (n *Node) deleteCardOn(ai peer.AddrInfo, category string) error {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return err
	}
	s, err := n.h.NewStream(ctx, ai.ID, boardDeleteProtocol)
	if err != nil {
		return err
	}
	defer s.Close()
	req, err := json.Marshal(map[string]any{"v": 1, "cat": category})
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(s, "%s\n", req); err != nil {
		return err
	}
	_ = s.CloseWrite()
	return readAck(s)
}

// LikePut deposita un "me gusta" ya cifrado para `to`. Failover al primer nodo que acepte,
// igual que el buzón — y por el camino propio de likes, que tiene su cuota separada.
func (n *Node) LikePut(likeAddrs string, to string, data []byte) error {
	nodes := parseAddrInfos(likeAddrs)
	if len(nodes) == 0 {
		return errors.New("sin nodo de likes configurado")
	}
	var errs []string
	for _, ai := range nodes {
		if err := n.likePutTo(ai, to, data); err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
			continue
		}
		return nil
	}
	return errors.New("likes: " + strings.Join(errs, "; "))
}

func (n *Node) likePutTo(ai peer.AddrInfo, to string, data []byte) error {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return err
	}
	s, err := n.h.NewStream(ctx, ai.ID, likePutProtocol)
	if err != nil {
		return err
	}
	defer s.Close()
	req, err := json.Marshal(map[string]any{
		"v":    1,
		"to":   to,
		"blob": base64.StdEncoding.EncodeToString(data),
	})
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(s, "%s\n", req); err != nil {
		return err
	}
	_ = s.CloseWrite()
	return readAck(s)
}

// LikeFetch retira los "me gusta" pendientes de TODOS los nodos alcanzables (un depósito
// pudo aterrizar en cualquiera), los entrega al LikeHandler y ack'ea solo lo confirmado.
func (n *Node) LikeFetch(likeAddrs string) (int, error) {
	nodes := parseAddrInfos(likeAddrs)
	if len(nodes) == 0 {
		return 0, errors.New("sin nodo de likes configurado")
	}
	total := 0
	var errs []string
	for _, ai := range nodes {
		got, err := n.likeFetchFrom(ai)
		total += got
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
		}
	}
	if len(errs) == len(nodes) {
		return total, errors.New("likes: " + strings.Join(errs, "; "))
	}
	return total, nil
}

func (n *Node) likeFetchFrom(ai peer.AddrInfo) (int, error) {
	ctx, cancel := context.WithTimeout(n.ctx, 60*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return 0, err
	}
	s, err := n.h.NewStream(ctx, ai.ID, likeGetProtocol)
	if err != nil {
		return 0, err
	}
	defer s.Close()
	r := bufio.NewReader(s)
	var envs []likeEnvelope
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return 0, fmt.Errorf("likes interrumpidos: %w", err)
		}
		var env likeEnvelope
		if err := json.Unmarshal(line, &env); err != nil {
			return 0, fmt.Errorf("like ilegible: %w", err)
		}
		if env.Done {
			break
		}
		envs = append(envs, env)
	}
	// La clave de ack es el PeerID del emisor (así se guardan en el nodo). Un blob con
	// base64 corrupto se ack'ea igual: no hay nada que persistir y reentregarlo sería un
	// bucle envenenado. Mismo criterio que el buzón.
	ids := make([]string, 0, len(envs))
	for _, env := range envs {
		data, err := base64.StdEncoding.DecodeString(env.Blob)
		if err != nil {
			ids = append(ids, env.From)
			continue
		}
		if handleLikeEnvelope(n.likeHandler, env.From, env.Ts, data) {
			ids = append(ids, env.From)
		}
	}
	ack, err := json.Marshal(map[string][]string{"ack": ids})
	if err != nil {
		return len(ids), err
	}
	if _, err := fmt.Fprintf(s, "%s\n", ack); err != nil {
		return len(ids), err
	}
	_ = s.CloseWrite()
	return len(ids), nil
}

// handleLikeEnvelope protege del panic que aflora aquí si el callback Kotlin lanza: cuenta
// como "no persistido" (→ sin ack, reentrega) en vez de tumbar la tanda entera.
func handleLikeEnvelope(h LikeHandler, from string, ts int64, data []byte) (ok bool) {
	if h == nil {
		return false
	}
	defer func() {
		if r := recover(); r != nil {
			ok = false
		}
	}()
	return h.OnLike(from, ts, data)
}

// readAck lee la respuesta {"ok":true} / {"err":"…"} común a publish, delete y like put.
func readAck(r io.Reader) error {
	line, err := bufio.NewReader(io.LimitReader(r, 8<<10)).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return fmt.Errorf("sin respuesta: %w", err)
	}
	var resp boardCard
	if err := json.Unmarshal(line, &resp); err != nil {
		return fmt.Errorf("respuesta ilegible: %w", err)
	}
	if resp.Err != "" {
		return errors.New(resp.Err)
	}
	return nil
}

// sortCardsDesc ordena por `ts` descendente (más recientes primero).
func sortCardsDesc(cards []boardCard) {
	sort.Slice(cards, func(i, j int) bool { return cards[i].Ts > cards[j].Ts })
}
