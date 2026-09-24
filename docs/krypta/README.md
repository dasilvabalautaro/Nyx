# Documentos de diseño de Krypta (copia de referencia)

Copiados **tal cual, sin rebrandear**, de `upstream/main` de Krypta (`a310ced`, 24 sep 2026), al
portar a Nyx el doble ratchet y el buzón ciego. El código portado los cita (`Ratchet.kt`,
`RatchetSessions.kt`, `ChatService.kt`, `CallService.kt`…) para explicar **por qué** es como
es, y sin ellos esas referencias no llevaban a ninguna parte.

Describen **Krypta**: sus nodos (Dallas, São Paulo), su revisión externa (OTF), su operador y
sus fechas. Lo que vale para Nyx es el **protocolo** —ratchet, épocas, relleno, reengache,
etiquetas del buzón— porque el código es el mismo. Lo que difiere en Nyx está en los mensajes de
commit con `Ported-from-Krypta` y en [../SYNC-KRYPTA.md](../SYNC-KRYPTA.md); lo más importante:

- En Nyx el bloqueo vive en `blocked_peers`, no en `contacts.blocked`.
- La base de Nyx llega a v9 por su propio camino (v5 con `likes`/`blocked_peers`, v6 sin
  `sharedSecret`); las migraciones 6→7→8→9 son las mismas sentencias que en Krypta.
- Los likes (sobre `L`) **no** van por el ratchet: van a desconocidos, sin sesión, con la
  clave estática del ECDH de identidades. Una cita (`Y`) nunca puede envolver un `L`.
- El wake v2 del nodo también avisa por PeerID, o un like no despertaría al móvil.

No se actualizan desde aquí: si Krypta los cambia, se vuelven a copiar al portar.
