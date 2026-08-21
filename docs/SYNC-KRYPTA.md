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
git log --oneline upstream/main --not HEAD
```

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
| `9a4a7fa` | 21 ago 2026 | `FLAG_SECURE` solo en la pantalla de chat (antes cubría toda la app) | **Portado** en `0f8bad8`. Código con el script; docs a mano con el parche sin sustituir. Compila y pasa los tests JVM; falta prueba en móvil. |
| `feb96d0` | 21 ago 2026 | Correo de contacto de la política de privacidad de Krypta | **No aplica**: dato de la ficha/política de Krypta. Nyx pondrá el suyo en la 6.1. |
| `25ee9e8` | 21 ago 2026 | Onboarding de la lista vacía + jerarquía tipográfica en Ajustes | **Pendiente**. Es UI compartida y se porta bien, pero el texto de los tres pasos habla del PeerID y Nyx añade el tablón: conviene rehacerlo con el flujo de Nyx, no copiarlo. |
| `b83be11` | 21 ago 2026 | `.gitignore`: ignora `keys-git.md` | **No aplica**: aquí ya estaba ignorado (fue al revés — se copió de Nyx a Krypta). |

Todo lo anterior a `dfc84fb` (main de Krypta el 21 ago 2026) está en el historial común: el
`git log upstream/main --not HEAD` sale vacío, así que no hay deuda acumulada de antes.
