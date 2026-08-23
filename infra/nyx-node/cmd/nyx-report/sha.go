package main

import (
	"crypto/sha256"
	"hash"
)

// sha256New existe para pasarle a hkdf.New la función constructora sin importar el paquete
// en el fichero principal, que ya tiene bastantes imports.
func sha256New() hash.Hash { return sha256.New() }
