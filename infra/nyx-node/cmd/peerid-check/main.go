// Comprobación puntual: deriva el PeerID de un node.key respaldado.
// No forma parte del nodo; sirve para verificar que una copia de seguridad es la buena.
package main

import (
	"fmt"
	"os"

	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
)

func main() {
	data, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	priv, err := crypto.UnmarshalPrivateKey(data)
	if err != nil {
		panic(err)
	}
	id, err := peer.IDFromPrivateKey(priv)
	if err != nil {
		panic(err)
	}
	fmt.Println(id.String())
}
