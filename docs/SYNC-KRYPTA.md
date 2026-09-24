# Sincronización con Krypta

Registro de qué se ha arreglado en **Krypta** y qué se hizo con ello aquí. Lo pide la
sección "Relación con Krypta a largo plazo" de [PLAN-NYX.md](PLAN-NYX.md): Nyx hereda ~90%
del transporte y la criptografía de Krypta (`native-bridge/libp2p/bridge.go`,
`:p2p-signaling`, `:core`, `:data`, el nodo), ese código sigue recibiendo arreglos allí, y
cada arreglo es un fallo que Nyx **también tiene**. Sin esta tabla, dentro de un año nadie
sabrá qué se revisó.

## Cómo se usa

La dirección es **de Krypta a Nyx y nunca al revés**. La config lo impone, no la disciplina:

```sh
git remote -v
# origin    git@github.com:dasilvabalautaro/Nyx.git      (fetch/push)
# upstream  git@github.com:dasilvabalautaro/Krypta.git   (fetch)
# upstream  no_push                                       (push)  → falla al resolver la URL
```

*(El `upstream` estaba en HTTPS y ni siquiera podía hacer `fetch` — el repo es privado y no
hay credenciales HTTPS en la máquina. Pasó a SSH el 21 ago 2026, que es lo que autentica.)*

Qué hay en Krypta que aquí falta:

```sh
git fetch upstream
git log --oneline upstream/main --not HEAD          # candidatos
for c in $(git log --format=%h upstream/main --not HEAD); do
  grep -q "$c" docs/SYNC-KRYPTA.md || echo "SIN DECIDIR: $c $(git log -1 --format=%s $c)"
done                                                 # lo que de verdad falta
```

El `git log` a secas **no sirve** como "no queda nada": el porte es un `git am`, o sea un
commit nuevo con otro SHA, así que el commit original de Krypta se queda en esa lista para
siempre aunque esté portado. Por eso el segundo bucle cruza cada candidato con la tabla de
abajo, donde está *todo* lo revisado — portado y "no aplica" por igual. Si imprime algo, es
que apareció algo que nadie ha mirado; si no imprime nada, la tabla está al día. Esto obliga
a **anotar en la tabla también lo que se descarta**: un commit que no esté en ella vuelve a
salir en cada repaso.

Traer un commit:

```sh
tools/port-from-krypta.sh <sha-de-krypta>
```

El script reescribe el parche con la sustitución de marca del rebrand y lo pasa por
`git am`, y deja el rastro en el propio commit (`Ported-from-Krypta: <sha>`). Si falla,
`git am --abort` y a mano — **sin `git am -3`**, que resuelve contra el blob original de
Krypta y ensucia el conflicto. Detalle completo en el plan.

**Ojo con los docs**: los de Nyx (`CLAUDE.md`, `docs/architecture.md`, `docs/PLAY-STORE.md`,
`docs/PRUEBAS-PENDIENTES.md`, `docs/politica-privacidad.html`) todavía son literalmente los
de Krypta — el rebrand documental es la tarea 6.1 del plan. Mientras siga así, sus hunks se
aplican con el parche **sin** sustituir (`git apply --include='docs/*' <parche original>`);
con "Nyx" en las líneas de contexto no encajan. Cuando se haga la 6.1, esto se invierte.

**No se portan** (se anotan como "no aplica"): commits de `infra/fdroid-repo`, de la ficha o
los docs de Play de Krypta, y cambios en `data/schemas/` (los esquemas divergen desde la v5).
Un commit que toque `native-bridge/libp2p/*.go` sí se porta, pero **exige regenerar el AAR**
después: el `.aar` no está en git.

## Tabla

| Commit de Krypta | Fecha | Qué es | Aquí |
| --- | --- | --- | --- |
| `98354a6` | 22 sep 2026 | Docs: manual al día (identidad en Keystore, pendientes de Play) | **No aplica**: solo docs de Krypta (`MANUAL.md`, `CLAUDE.md`). |
| `cb1688a` | 22 sep 2026 | Docs: identificador de la solicitud al OTF | **No aplica**: solo docs de Krypta (revisión externa de Krypta). |
| `7e3c969` | 22 sep 2026 | Docs: solicitud enviada al OTF Security Lab | **No aplica**: solo docs de Krypta (revisión externa de Krypta). |
| `e66448b` | 22 sep 2026 | Docs: clave PGP en la respuesta 16 del OTF | **No aplica**: solo docs de Krypta (revisión externa de Krypta). |
| `ecedb9a` | 22 sep 2026 | Clave PGP para recibir hallazgos del OTF | **No aplica**: solo docs de Krypta y su clave pública. Si Nyx quiere un canal de avisos de seguridad, necesita su propia clave, no la de Krypta. |
| `a642758` | 21 sep 2026 | Reintentar reenvía el archivo, no su descriptor | **Pendiente — bloque «doble ratchet»** (ver abajo): el reintento que arregla pasa por el sobre del ratchet. |
| `58b2286` | 18 sep 2026 | Docs: los nodos los opera 4000MSNM S.R.L.; marco de metadatos | **No aplica**: solo docs de Krypta. Quién opera los nodos de Nyx es una decisión del autor que no se hereda. |
| `dd95f04` | 18 sep 2026 | Docs: el wake v2 da la correlación en vivo en un paso | **Pendiente — bloque «buzón ciego»** (ver abajo). |
| `04efc41` | 18 sep 2026 | Textos de usuario sobre lo que ve el nodo | **Pendiente — bloque «buzón ciego»** (ver abajo): los textos nuevos hablan de la etiqueta opaca del buzón v2. |
| `3acc004` | 15 sep 2026 | Docs: respuesta al criterio de protocolo maduro | **No aplica**: solo docs de Krypta (revisión externa). |
| `1980d3c` | 15 sep 2026 | Docs: referencias genéricas a la revisión independiente | **No aplica**: solo docs de Krypta (revisión externa). |
| `fadb9fc` | 15 sep 2026 | Docs: respuesta al registro de hallazgos AK-2026-001 | **No aplica**: solo docs de Krypta (revisión externa). |
| `ebf2d43` | 15 sep 2026 | Test de protocolo H-5 y fuera los bytes NUL del fuente | **Pendiente — bloque «doble ratchet»** (ver abajo). |
| `f8d9a75` | 15 sep 2026 | Ayuda: qué hacer si tras importar la copia no llegan mensajes (W-6) | **Pendiente — bloque «doble ratchet»** (ver abajo): el W-6 es un fallo del estado del ratchet tras restaurar. |
| `f119237` | 15 sep 2026 | Docs: revisión de diseño independiente (W-6, W-14, KCI) | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `fe21111` | 15 sep 2026 | Llamadas: invite repetido (H-7) y W-8 | **Pendiente — bloque «doble ratchet»** (ver abajo): el arreglo de H-7 viene de la misma revisión del protocolo; mirarlo al portar el bloque. |
| `ea088b1` | 14 sep 2026 | Docs: AAR reproducible comprobado; NDK real de binarios anteriores | **No aplica**: solo docs de Krypta; lo que importaba ya entró con `8d02875`. |
| `8d02875` | 14 sep 2026 | AAR reproducible y ligado a su commit | **Portado** en `2830bf3`, con el script. Verificado: go1.26.4, NDK 26.1, JDK 25, las cuatro ABI con páginas de 16 KB, el commit dentro y sin rutas locales. |
| `d820609` | 14 sep 2026 | Docs: release revision-externa-1 publicada | **No aplica**: solo docs de Krypta. |
| `26bd405` | 14 sep 2026 | `build-aar.sh` funciona en un clon limpio | **Portado** en `6429ab7`, solo el `mkdir -p ../libs`; el resto son docs de la revisión externa. |
| `a97cbab` | 14 sep 2026 | Revisión del protocolo: clave y nonce repetidos, mensajes perdidos, versión que bajaba | **Pendiente — bloque «doble ratchet»** (ver abajo). Es el más importante del bloque: corrige defectos del propio ratchet, así que va con él o no va. |
| `8c4d874` | 12 sep 2026 | Docs: comparación con Signal, SECURITY.md, rotación | **Portado solo el hunk de ayuda** en `055e757` (la copia recupera PeerID y contactos, no conversaciones). El resto no aplica. |
| `f562c33` | 12 sep 2026 | `security.txt` en los nodos y vigilancia que avisa al cambiar | **Pendiente, menor**: el `security.txt` lleva el contacto de Krypta y el resto toca `deploy-caddy.sh`, que Nyx no usa. Rehacerlo con datos de Nyx cuando haya canal de avisos. |
| `16a1c4a` | 12 sep 2026 | Depósito ciego por contacto y fuzzing del parseo | **Pendiente — bloque «buzón ciego»** (ver abajo). |
| `5e11f05` | 12 sep 2026 | Una sola conexión por nodo, sin WebSocket redundantes | **Portado** en `04e20eb`; el campo `wsPruned` a mano. Aquí la WebSocket de sobra viene del ws/8081 que identify aprende, no de Caddy. |
| `cb42f47` | 12 sep 2026 | Límite de ritmo en la retirada del buzón | **Portado** en `7886db2`, solo la retirada v1 (el hunk y el test de v2 son del buzón ciego). La bandeja de likes no pasa por este límite. **Falta desplegar en las dos cajas.** |
| `ecf6833` | 12 sep 2026 | Licencia MIT o Apache-2.0 y README | **No aplica**: la licencia de Nyx la decide el autor; no se hereda de Krypta. |
| `3abeada` | 11 sep 2026 | Docs: fases 1 y 2 del post-cuántico | **Pendiente — bloque «post-cuántico»** (ver abajo). |
| `7eca8df` | 11 sep 2026 | Primitiva post-cuántica (ML-KEM-768), sin protocolo | **Pendiente — bloque «post-cuántico»** (ver abajo). No cambia nada por sí sola: solo tiene sentido junto al diseño que la usa. |
| `13037ec` | 11 sep 2026 | Docs: diseño post-cuántico cerrado | **Pendiente — bloque «post-cuántico»** (ver abajo). |
| `87d6152` | 11 sep 2026 | Docs: la v3 se anuncia sola tras el salto de versión | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `69dd3b3` | 11 sep 2026 | Docs: relleno por tramos | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `d2523ff` | 11 sep 2026 | Rellenar el tamaño por tramos dentro del cifrado | **Pendiente — bloque «doble ratchet»** (ver abajo): el relleno va dentro del sobre v3. |
| `7f2bbac` | 11 sep 2026 | Docs: ventana de deduplicación | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `dd1d8e0` | 11 sep 2026 | La deduplicación se acota por tiempo | **Pendiente — bloque «doble ratchet»** (ver abajo): poda la tabla `ratchet_seen`, que aquí no existe. |
| `04b6e43` | 10 sep 2026 | Docs: el reengache | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `3aca88a` | 10 sep 2026 | Reengachar la sesión cuando el otro perdió el estado | **Pendiente — bloque «doble ratchet»** (ver abajo). |
| `15eb13a` | 10 sep 2026 | Docs: la prueba de propiedades del ratchet | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `6f5c624` | 10 sep 2026 | El ratchet bajo caos, con semillas fijas | **Pendiente — bloque «doble ratchet»** (ver abajo) (test). |
| `d84e714` | 10 sep 2026 | Docs: plan de privacidad y confianza; mDNS al día | **No aplica**: solo docs de Krypta. |
| `cbb3f38` | 10 sep 2026 | mDNS opt-in, apagado de serie | **Portado** en `31cccf5`; tres hunks a mano por contexto. En Nyx pesa más: anunciarse decía a toda la WiFi que ese móvil lleva una app de citas. AAR regenerado. |
| `af34a42` | 10 sep 2026 | Docs: la fuga de IP, cómo se cerró | **No aplica**: solo docs de Krypta; el razonamiento va en el mensaje de `2fc8ba9`. |
| `35927e1` | 10 sep 2026 | Solo contactos y nodos pueden abrirnos conexión (fuga de IP) | **Portado** en `2fc8ba9` junto con su sonda. Adaptado al bloqueo de Nyx (`blocked_peers`); aquí pesa más porque la tarjeta del tablón hace público el PeerID. AAR regenerado. |
| `e5f84af` | 10 sep 2026 | Docs: pruebas con dos móviles del 10 sep | **No aplica**: solo docs de Krypta. |
| `f723717` | 10 sep 2026 | Sonda que reproduce la fuga de IP | **Portado** en `2fc8ba9` (con `35927e1`); el ejemplo apunta a `nyx.neto.chat`. El hunk de `.gitignore` (un PDF del autor) no aplica. |
| `6133ff0` | 10 sep 2026 | Docs: confianza operativa, nodo de Dallas, wss/443 | **No aplica**: solo docs de Krypta e infra de Krypta. |
| `68458bc` | 10 sep 2026 | VPS de Dallas + wss/443 por Caddy + ranker de marcado | **Portado solo el ranker** (`dial_ranker.go`) en `2bce58d`. Dallas, Caddy y el `DEFAULT_BOOTSTRAP` son infra de Krypta y no aplican. |
| `3f393e4` | 10 sep 2026 | El log del nodo no guarda PeerIDs; retención del journal | **Portado** en `bb63308`; aquí ya estaba el sysctl de QUIC, entra el journal. Revisados `board.go`, `like.go` y `report.go`: no imprimen nada. **Falta desplegar.** |
| `80af727` | 10 sep 2026 | Docs: poner al día lo que se promete | **Pendiente — bloque «doble ratchet»** (ver abajo) (docs). |
| `afb576a` | 10 sep 2026 | Secreto hacia adelante con un doble ratchet por épocas | **Pendiente — bloque «doble ratchet»** (ver abajo). 56 ficheros y 4 453 líneas, con DB propia: es el núcleo del bloque. |
| `2a6118e` | 9 sep 2026 | Cifrar la base de datos con SQLCipher | **Portado** en `0fdf56e`. La frase se guarda con `commit()` en vez de `apply()`. 6/6 instrumentados en el TECNO; el falso cuelgue era HiOS congelando el proceso de test (anotado en el test). **La app instalada aún no ha convertido su base.** |
| `867af62` | 9 sep 2026 | Sonda de depósito ciego en el chequeo de salud | **Pendiente — bloque «buzón ciego»** (ver abajo). |
| `63222d1` | 9 sep 2026 | El cliente recibe a ciegas (fase 4) | **Pendiente — bloque «buzón ciego»** (ver abajo). |
| `157bcaf` | 9 sep 2026 | El puente Go habla v2 con caída a v1 por nodo | **Pendiente — bloque «buzón ciego»** (ver abajo). |
| `b03a14a` | 9 sep 2026 | Etiquetas semanales y protocolo v2 en el nodo | **Pendiente — bloque «buzón ciego»** (ver abajo). |
| `93e0046` | 9 sep 2026 | Docs: propuesta de depósito ciego | **Pendiente — bloque «buzón ciego»** (ver abajo) (diseño). |
| `020bcbb` | 8 sep 2026 | Docs de la vigilancia y agente de launchd | **No aplica tal cual**: el plist lanza `check-nodes.sh` desde el Mac del autor contra los nodos de Krypta. Si se quiere vigilar los de Nyx, es un plist nuevo apuntando a este repo. |
| `af3163f` | 8 sep 2026 | Límite de ritmo por remitente y chequeo de salud | **Portado** en `fc54c2c`. `check-nodes.sh` lee `DEFAULT_BOOTSTRAP` y saca los dos nodos de Nyx. **Falta desplegar.** |
| `9322a82` | 8 sep 2026 | Sacar el secreto compartido de la base (DB v6) | **Portado** en `8ed494b` con una `MIGRATION_5_6` propia (la v5 de aquí lleva `likes` y `blocked_peers`). Validada en la JVM contra `6.json` (falsificada) y en el TECNO sobre SQLite real, incluidas las tablas de Nyx. |
| `347eb97` | 8 sep 2026 | Docs: lo que protege el Keystore y lo que no | **No aplica**: solo docs de Krypta (`security-model.md`, que Nyx no tiene). |
| `62b50c4` | 8 sep 2026 | Docs: modelo de seguridad y auditoría del 7 sep | **No aplica**: solo docs de Krypta. Los hallazgos A-x de esa auditoría que tocan código son los commits de esta tabla. |
| `cda793c` | 8 sep 2026 | Test de la migración Room 4→5 de Krypta | **No aplica**: Nyx ya tenía su propio `MigrationTest` 4→5 (su v5 es otra). |
| `d4eba65` | 8 sep 2026 | Acotar la recepción de archivos y barrer el staging abandonado | **Portado** en `693df60`; un hunk a mano por contexto. |
| `3de817c` | 8 sep 2026 | Dejar de descifrar la conversación entera en cada tecla | **Portado** en `23d29e3`; la caché de `ChatViewModel` se reescribió sin las respuestas citadas. |
| `b45e123` | 8 sep 2026 | Reintentar los fallidos al reconectar; blindar el id del emisor | **Portado** en `746f24e`, adaptado a `blocked_peers`. Añade además que a un bloqueado no se le anuncia el rendezvous (test falsificado). |
| `d8ceb03` | 8 sep 2026 | Envolver la clave Ed25519 con el Android Keystore | **Portado** en `1f09467`. La copia en claro de `nyx_identity` se migra sola al primer arranque. **La app instalada aún no ha pasado por la migración.** |
| `2b9ea93` | 8 sep 2026 | Rendezvous: publicar una sola vez y ventana de solape | **Portado** en `b329490`, limpio. |
| `5e6bddd` | 8 sep 2026 | Reparto justo del buzón y topes en relay y wake | **Portado** en `04f4ffc` **sin el hunk del relay**: aquí ya había topes propios (`relay.go`). **Falta desplegar.** |
| `37f185d` | 3 sep 2026 | Responder citando un mensaje anterior | **Portado** en `2612bbf`, a mano. La cita solo envuelve contenido (nunca un like ni una señal: test propio). Pulsación larga: menú Responder · Copiar · Denunciar en los recibidos, píldora de iconos en los propios. Sin probar en el móvil (PRUEBAS-PENDIENTES §17). |
| `17aa61f` | 2 sep 2026 | Docs: dejar el fallo de 2.º plano en HiOS como está | **No aplica**: solo docs de Krypta (`PRUEBAS-PENDIENTES`). |
| `21fcdc5` | 2 sep 2026 | Docs: causa del fallo en 2.º plano (HiOS congela la app) | **No aplica**: solo docs de Krypta. El hallazgo sí vale aquí: el 24 sep 2026 HiOS congeló también el proceso de test de `:data`. |
| `72977de` | 2 sep 2026 | Copiar pasa a ser un botón de icono | **Portado** en `02ba6cf` (junto con `3629545` y `56974f6`). |
| `56974f6` | 2 sep 2026 | Icono y forma del menú de copiar, nota en la ayuda | **Portado** en `02ba6cf`. |
| `3629545` | 2 sep 2026 | Copiar un mensaje con pulsación larga y enlaces tocables | **Portado** en `02ba6cf`. Integrado a mano con el menú de «Denunciar»: en los recibidos, «Copiar» entra en ese menú; en los propios, el botón redondo. |
| `81711f7` | 2 sep 2026 | No cancelar el latido al morir; rearranque al quitar de recientes | **Portado** en `b99eaff`, limpio. |
| `1d8caf3` | 2 sep 2026 | Docs: auditoría previa a producción y requisitos de Play | **No aplica**: solo docs de Krypta (`PLAY-STORE.md`). |
| `03bf430` | 2 sep 2026 | R8: acotar al puente el proguard del AAR | **Portado** en `645741b`, limpio. |
| `a7ea71e` | 2 sep 2026 | WAN: un nodo colgado ya no para la entrega | **Portado** en `92f9892`; los tests nuevos se adaptaron al constructor de aquí. |
| `4a14131` | 2 sep 2026 | Vibración de llamada en Android 11 | **Portado** en `6cdfd7e`, limpio. |
| `a310ced` | 22 sep 2026 | Docs: confirmado el borrado de datos de los nodos domésticos de Krypta | **No aplica**: solo toca `CLAUDE.md` e `infra/node/README.md` de Krypta. Nyx nunca usó esos nodos (su `DEFAULT_BOOTSTRAP` son sus dos VPS propios). |
| `97f0fda` | 22 sep 2026 | Docs: los nodos domésticos de Krypta (Mac y Windows) se retiraron hacia el 8 sep | **No aplica**: mismo motivo; toca `CLAUDE.md`, `MANUAL.md`, `PLAY-STORE.md`, `architecture.md` e `infra/node/README.md` de Krypta. |
| `deb0bf8` | 6 sep 2026 | Acotar la lectura de los streams entrantes (mensaje, buzón y wake) | **Portado** en `f78e834`. El código con el script; los docs a mano: el hunk de `CLAUDE.md` y el del README del nodo no aplicaban ni sustituidos ni sin sustituir, y el cierre del bloque hablaba del VPS de Krypta — reescrito con lo de aquí. **El nodo de Nyx (`216.238.104.36`) sigue con el binario anterior**: necesita su propio `deploy-vps.sh`. AAR regenerado. Verde: `go vet`, tests del puente y del nodo. |
| `936e9ae` | 6 sep 2026 | La silueta de la burbuja (esquina, borde y sombra) escala con su altura | **Portado** en `447c88a`, **a mano**: el parche da por hecho las respuestas citadas (`37f185d`, sin portar), así que sus seis hunks salieron rechazados. El arreglo es autocontenido y entra tal cual sobre la burbuja de aquí, que conserva su menú de "Denunciar este mensaje". Compila y pasa los tests JVM; falta prueba en móvil. |
| `1ab4453` | 6 sep 2026 | Bloquear contacto (`Contact.blocked`, Room v5, UI en lista y chat) | **No aplica**: Nyx ya tiene bloqueo, y **más completo** — `BlockedPeer` en tabla propia (bloquea por PeerID, así que sobrevive a borrar el contacto y sirve para alguien del tablón con quien nunca hablaste), `BlockedPeersScreen` para desbloquear, y cuelga la llamada en curso al bloquear. Krypta lo resolvió después y con una columna en `contacts`; portarlo sería un retroceso. Además su Room v5 choca con la v5 de aquí (los esquemas divergen desde ahí, ya anotado arriba). |
| `9a4a7fa` | 21 ago 2026 | `FLAG_SECURE` solo en la pantalla de chat (antes cubría toda la app) | **Portado** en `0f8bad8`. Código con el script; docs a mano con el parche sin sustituir. Compila y pasa los tests JVM; falta prueba en móvil. |
| `feb96d0` | 21 ago 2026 | Correo de contacto de la política de privacidad de Krypta | **No aplica**: dato de la ficha/política de Krypta. Nyx pondrá el suyo en la 6.1. |
| `25ee9e8` | 21 ago 2026 | Onboarding de la lista vacía + jerarquía tipográfica en Ajustes | **Portado** en `a0a9851`, entero y con el script. Revertida la decisión de dejarlo pendiente: la mitad de Ajustes es tipografía pura y no toca el tablón, así que aplazarla solo conservaba aquí un defecto de legibilidad ya arreglado en Krypta — el commit no es atómico desde el punto de vista de Nyx. Y los tres pasos describen el único flujo de alta que Nyx tiene hoy. El estado vacío se reescribe cuando aterrice el tablón: tarea **4.8b** del plan. Compila y pasa los tests JVM; falta prueba en móvil. |
| `b83be11` | 21 ago 2026 | `.gitignore`: ignora `keys-git.md` | **No aplica**: aquí ya estaba ignorado (fue al revés — se copió de Nyx a Krypta). |

Todo lo anterior a `dfc84fb` (main de Krypta el 21 ago 2026) está en el historial común: el
`git log upstream/main --not HEAD` sale vacío, así que no hay deuda acumulada de antes.

**Último repaso: 24 sep 2026.** El tip de Krypta en `main` es `a310ced`. Este repaso decidió 79
commits (los 11 que quedaban del 21 ago al 3 sep y los 68 del 8 al 22 sep): **26 portados** —dos
solo en parte, `68458bc` y `8c4d874`— en 23 commits de aquí, y el resto anotados como «no
aplica» o dentro de uno de los bloques pendientes. El de **respuestas citadas** (`37f185d`) se portó el
mismo día en `2612bbf`. El bucle de "Cómo se usa" no
imprime nada.

**Falta desplegar** en las dos cajas (`nyx` y `nyx2`) lo del nodo: reparto justo del buzón y
tope de wake (`5e6bddd`), límite de ritmo por remitente (`af3163f`), log sin PeerIDs y
retención del journal (`3f393e4`) y límite en la retirada (`cb42f47`). Y la app instalada en el
TECNO todavía no ha pasado por las tres migraciones locales (identidad en Keystore, DB v6 y
SQLCipher); exportar un `.nybk` antes de instalar el build nuevo.

### Bloques pendientes

No son arreglos sueltos sino cambios de protocolo encadenados, cada uno con decisiones que en
Nyx no son las de Krypta. Se portan como bloque o no se portan:

| Bloque | Commits | Por qué no entra con el script |
| --- | --- | --- |
| **Buzón ciego** (protocolo v2) | `93e0046`, `b03a14a`, `157bcaf`, `63222d1`, `867af62`, `16a1c4a`, `dd95f04`, `04efc41` | Cambia el protocolo del buzón en el nodo **y** en el cliente (etiquetas semanales en vez de PeerID destino). En Nyx convive con la bandeja de likes, que por diseño va con remitente explícito, y exige redesplegar las dos cajas. |
| **Doble ratchet** | `afb576a`, `6f5c624`, `15eb13a`, `3aca88a`, `04b6e43`, `dd1d8e0`, `7f2bbac`, `d2523ff`, `69dd3b3`, `87d6152`, `80af727`, `a97cbab`, `fe21111`, `f119237`, `ebf2d43`, `f8d9a75`, `a642758` | ~7 000 líneas con tablas propias, nuevo sobre v3 y una revisión externa que corrigió el propio ratchet (`a97cbab`). En Nyx toca `LikeService` (el like va con ECDH estático a un desconocido, sin sesión), la DB (que aquí va por otra numeración) y rompe la compatibilidad de mensajes con builds anteriores. |
| **Post-cuántico** | `13037ec`, `7eca8df`, `3abeada` | Solo la primitiva y el diseño; sin el ratchet no tiene dónde usarse. |
