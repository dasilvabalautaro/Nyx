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
| `deb0bf8` | 6 sep 2026 | Acotar la lectura de los streams entrantes (mensaje, buzón y wake) | **Portado** en `f78e834`. El código con el script; los docs a mano: el hunk de `CLAUDE.md` y el del README del nodo no aplicaban ni sustituidos ni sin sustituir, y el cierre del bloque hablaba del VPS de Krypta — reescrito con lo de aquí. **El nodo de Nyx (`216.238.104.36`) sigue con el binario anterior**: necesita su propio `deploy-vps.sh`. AAR regenerado. Verde: `go vet`, tests del puente y del nodo. |
| `936e9ae` | 6 sep 2026 | La silueta de la burbuja (esquina, borde y sombra) escala con su altura | **Portado** en `447c88a`, **a mano**: el parche da por hecho las respuestas citadas (`37f185d`, sin portar), así que sus seis hunks salieron rechazados. El arreglo es autocontenido y entra tal cual sobre la burbuja de aquí, que conserva su menú de "Denunciar este mensaje". Compila y pasa los tests JVM; falta prueba en móvil. |
| `1ab4453` | 6 sep 2026 | Bloquear contacto (`Contact.blocked`, Room v5, UI en lista y chat) | **No aplica**: Nyx ya tiene bloqueo, y **más completo** — `BlockedPeer` en tabla propia (bloquea por PeerID, así que sobrevive a borrar el contacto y sirve para alguien del tablón con quien nunca hablaste), `BlockedPeersScreen` para desbloquear, y cuelga la llamada en curso al bloquear. Krypta lo resolvió después y con una columna en `contacts`; portarlo sería un retroceso. Además su Room v5 choca con la v5 de aquí (los esquemas divergen desde ahí, ya anotado arriba). |
| `9a4a7fa` | 21 ago 2026 | `FLAG_SECURE` solo en la pantalla de chat (antes cubría toda la app) | **Portado** en `0f8bad8`. Código con el script; docs a mano con el parche sin sustituir. Compila y pasa los tests JVM; falta prueba en móvil. |
| `feb96d0` | 21 ago 2026 | Correo de contacto de la política de privacidad de Krypta | **No aplica**: dato de la ficha/política de Krypta. Nyx pondrá el suyo en la 6.1. |
| `25ee9e8` | 21 ago 2026 | Onboarding de la lista vacía + jerarquía tipográfica en Ajustes | **Portado** en `a0a9851`, entero y con el script. Revertida la decisión de dejarlo pendiente: la mitad de Ajustes es tipografía pura y no toca el tablón, así que aplazarla solo conservaba aquí un defecto de legibilidad ya arreglado en Krypta — el commit no es atómico desde el punto de vista de Nyx. Y los tres pasos describen el único flujo de alta que Nyx tiene hoy. El estado vacío se reescribe cuando aterrice el tablón: tarea **4.8b** del plan. Compila y pasa los tests JVM; falta prueba en móvil. |
| `b83be11` | 21 ago 2026 | `.gitignore`: ignora `keys-git.md` | **No aplica**: aquí ya estaba ignorado (fue al revés — se copió de Nyx a Krypta). |

Todo lo anterior a `dfc84fb` (main de Krypta el 21 ago 2026) está en el historial común: el
`git log upstream/main --not HEAD` sale vacío, así que no hay deuda acumulada de antes.

**Último repaso: 6 sep 2026.** El tip de Krypta es `deb0bf8` en `main` y en
`feat/avisos-gif-capturas-1.5` (las dos ramas apuntan al mismo sitio). Se triaron **solo los
tres commits del 6 sep** (los dos primeros de la tabla y el de bloqueo). **Queda deuda: 11
commits de Krypta entre el 21 ago y el 3 sep siguen sin decidir**, y el bucle de "Cómo se usa"
los imprimirá:

| Commit | Qué es |
| --- | --- |
| `37f185d` | Responder citando un mensaje anterior (sobre `Y` de envoltorio, deslizar para responder) |
| `17aa61f`, `21fcdc5` | Docs de entrega: causa del fallo en 2.º plano en HiOS y decisión de dejarlo |
| `72977de`, `56974f6`, `3629545` | Copiar un mensaje (pulsación larga, botón de icono) y enlaces tocables |
| `81711f7` | El latido no se cancela al morir el servicio; rearranque al quitar de recientes |
| `1d8caf3` | Docs: auditoría previa a producción y requisitos nuevos de Play |
| `03bf430` | R8: acotar al puente el proguard que mete el AAR de gomobile |
| `a7ea71e` | WAN: un nodo colgado ya no para la entrega |
| `4a14131` | Vibración de llamada en Android 11 |

De esos, `81711f7`, `a7ea71e`, `03bf430` y `4a14131` son de la capa común y **pintan bien
aquí**; el resto es UI de chat y docs, que hay que mirar uno a uno.
