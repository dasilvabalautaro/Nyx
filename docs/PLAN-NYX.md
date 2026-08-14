> Estado: plan aprobado, pendiente de ejecución. Creado 14 de agosto de 2026, **revisado y
> corregido contra el repo el mismo día** (las correcciones van marcadas con «Corrección
> 14 ago» donde cambian lo acordado). La checklist punto por punto de la sección final es la
> fuente de verdad de seguimiento hasta que la Tarea 6.0 la transcriba a
> `docs/NYX-TAREAS.md`; cada tarea completada debe cerrar con su entrada correspondiente en
> [`CLAUDE.md`](../CLAUDE.md), igual que documenta Krypta hoy.

# Krypta → Nyx: rebrand + pivot a P2P de citas/relaciones

## Contexto

Este repositorio es una copia de Krypta (mensajero P2P E2EE) que se convierte en un
producto distinto: **Nyx**, una app P2P de citas, relaciones y contenido para adultos.
Se conserva toda la ingeniería de seguridad/privacidad/transporte de Krypta (identidad
Ed25519 local, ECDH, E2EE, DHT+rendezvous, buzón store-and-forward, relay v2, etc.) — lo
que cambia es el propósito del producto y, por tanto, quién puede hablar con quién.

**Nyx no sustituye a Krypta** (*precisión del 14 de agosto de 2026, corrige una lectura
equivocada de la primera revisión de este plan*). Krypta sigue en producción y en
desarrollo, con sus usuarios, su ficha, sus nodos y su repositorio. Nyx es un **proyecto
hermano** que *asimila* las técnicas y restricciones ya resueltas en Krypta y a partir de
ahí es autónomo: repositorio propio, `applicationId` propio, identidad de firma propia,
nodos propios, PeerID propios, dominio propio, ficha propia. Nada de lo que se haga aquí
puede degradar, modificar ni depender de lo que Krypta ya tiene funcionando.

Esto tiene tres consecuencias que este plan asume a partir de aquí:

1. **Infraestructura separada de raíz.** No se comparte ni el hardware: Nyx tiene caja
   propia (decisión 2, revisada el 14 ago). No hay convivencia que gestionar, ni límites
   de recursos que repartir, ni un despliegue de Nyx que pueda tocar producción de Krypta.
2. **Base de ingeniería común que sigue viva en los dos lados.** Un fallo del buzón o del
   relay que se corrija en Krypta es un fallo que Nyx también tiene, y viceversa. El plan
   original daba por hecha una divergencia total; ver "Relación con Krypta a largo plazo"
   más abajo, que define cómo se portan esos arreglos sin acoplar los proyectos.
3. **Nada se borra "porque ya está en Krypta".** Lo que se elimina de esta copia
   (`infra/fdroid-repo`) se elimina porque **aquí** sobra y es peligroso ejecutarlo desde
   este checkout, no porque Krypta lo sustituya. En el repo de Krypta sigue intacto y en
   uso.

El problema central que resuelve este plan: en Krypta los contactos ya se conocen
(intercambian PeerID fuera de la app); en una app de citas los usuarios son
desconocidos entre sí. Hace falta un mecanismo de descubrimiento que no rompa el
principio de "solo se maneja el ID, ningún otro dato personal", que no permita acoso
(nadie puede escribirte sin que tú también muestres interés primero), y que sea
coherente con la ausencia total de servidor/cuentas central.

Decisiones de producto ya tomadas con el usuario (no reabrir):

1. **Naming**: paquete `chat.neto.krypta` → `chat.neto.nyx` en los 5 módulos; dominio
   de bootstrap `krypta.neto.chat` → `nyx.neto.chat` (mismo patrón).
2. **Infraestructura: caja propia e independiente** (*decisión revisada el 14 ago 2026;
   sustituye a la versión inicial del plan, que proponía reutilizar las cajas de Krypta
   como proceso separado*). Nyx despliega en **hardware propio**, no compartido con
   Krypta. **No se evalúa la convivencia en la misma máquina** — no es una comparación
   abierta, es una restricción de partida. Motivos, por si alguien lo reabre: el VPS de
   Krypta es un plan compartido de 2 GB que ya avisa de `net.core.rmem_max` bajo con un
   solo nodo; el relay de Nyx va a mover vídeo, el tráfico más pesado de los dos; y
   cualquier fallo de despliegue de Nyx sobre esa caja se lleva por delante un producto
   en producción. Caja propia elimina la clase entera de problemas, no la gestiona.
   Consecuencia práctica: Nyx usa los **puertos estándar** (TCP/UDP 4001, ws 8081), los
   mismos números que Krypta en la suya, porque ya no hay nada con lo que chocar.
   El código: este repositorio es una copia, y el Krypta desplegado se construye desde su
   propio repo, así que aquí `infra/node/` se **renombra en el sitio** a `infra/nyx-node/`
   (`git mv`) en vez de copiarse dejando el original detrás — una copia sin renombrar
   queda como código muerto que diverge y que alguien puede desplegar por error.
3. **Descubrimiento**: híbrido — (a) DHT rendezvous por categoría pública (extensión
   del patrón `HKDF(secreto, fecha)` ya usado, aquí sin secreto compartido) + (b) un
   "tablón" de tarjetas efímeras opt-in en los nodos relay, calcado del patrón ya
   probado del buzón (cuota + TTL).
4. **Perfil**: PeerID (obligatorio, es la dirección) + campos opcionales autodeclarados
   (apodo, franja de edad, intereses, bio corta) — nunca verificados, nunca identidad
   real, solo viven en la tarjeta efímera y/o en un borrador local.
5. **Anti-acoso**: doble opt-in — un "like" cifrado (nuevo tipo de envelope, mismo
   patrón que ya usan imagen/archivo/llamada) solo abre chat completo cuando ambas
   partes se han mostrado interés.
6. **Seguridad, en esta fase**: bloqueo real y persistente, un mecanismo de "reportar"
   honesto (bloqueo inmediato + exportar localmente un paquete de evidencia — **sin**
   canal de envío al relay ni cola de moderación, para no comprometerse a operar un
   proceso que nadie atendería), y un gate de edad 18+ por autodeclaración que cubre
   **toda la app** desde el primer inicio (Nyx se posiciona enteramente como app de
   citas/contenido adulto, así lo verá también el cuestionario de clasificación de
   Play).
7. **Monetización**: solo diseño/documentación en este plan (`docs/MONETIZACION.md`),
   nada de código de pagos — Freemium Humanizado (qué es siempre gratis vs. capa de
   conveniencia de pago, sin dark patterns, sin pagar por seguridad) + micro-aportes
   (Play Billing para el nivel de soporte a la app, y una dirección de propina P2P
   opcional y pasiva en el perfil, fuera del alcance de pago de Play).
8. **Distribución: solo Google Play** (*decisión del 14 ago 2026*). Nyx se publica
   exclusivamente en Play. **F-Droid queda fuera del plan por completo**: no es un canal
   secundario, no es un plan B, no se contempla. En consecuencia, `infra/fdroid-repo/`
   (16 KB: `README.md`, `deploy-catalina.sh`, `chat.neto.krypta.fdroid.plist`, que publica
   **APKs de Krypta desde la caja Catalina**) se **elimina de esta copia** — no como
   decisión de distribución, sino como limpieza de herramienta heredada que aquí sobra y
   que sería peligroso ejecutar desde este checkout. En el repo de Krypta sigue intacta y
   en uso. Consecuencia que hay que tener presente y que se desarrolla en la Fase 0: **sin
   canal alternativo, cumplir la política de Play deja de ser una preferencia y pasa a ser
   condición de existencia del producto.**
9. **Identidad visual**: se abandona el look "mensajería segura" (teal + burbuja con
   candado) por una dirección noche/misterio/intimidad coherente con "Nyx" (diosa
   griega de la noche) — paleta oscura (índigo/ciruela/dorado rosado sugerido), nuevo
   glifo de icono (diseño final es una tarea creativa de seguimiento, este plan solo
   fija qué archivos tocar).
10. **Avatar de la tarjeta del tablón — decisión pendiente, dos alternativas en el
    plan**: la tarjeta de la Pieza B lleva **una única imagen permitida: un avatar de
    solo rostro generado por la app, nunca una foto real**. Hay dos caminos posibles
    (ver Fase 3b) — un motor de generación especializado on-device que el usuario ya
    está desarrollando para mobile (con filtros de contenido propios), o un
    constructor paramétrico de rasgos curados como alternativa probada y de bajo
    riesgo. Se decide en el punto de integración (Fase 3b), no ahora — el plan deja
    ambos caminos documentados y el resto de fases (modelo de datos, tablón, UI) se
    diseña de forma que funcione con cualquiera de los dos.
11. **Política de documentación**: Nyx continúa la misma práctica de Krypta —
    documentar cada función a medida que se construye, con el mismo nivel de detalle
    narrativo, técnico y fechado que tiene hoy `CLAUDE.md` (qué se hizo, por qué, cómo
    se verificó), no como un resumen al final. Cada fase de este plan que se implemente
    debe cerrar con la actualización correspondiente de `CLAUDE.md` y los docs
    afectados — ver Fase 6 y la lista de tareas al final de este documento.

La secuencia importa: **Fase 1 (rebrand) debe completarse y verificarse con build
limpio antes de escribir ninguna línea de código nuevo** en `chat.neto.nyx` — el
renombrado mueve directorios de paquete físicos, no hay un estado "a medias" que
compile, así que hacerlo primero como un solo pase atómico evita duplicar el trabajo
mecánico más tarde.

---

## Relación con Krypta a largo plazo

*Sección añadida el 14 de agosto de 2026, tras precisar que los dos proyectos conviven
indefinidamente. El plan original describía la separación del día 1 pero no decía nada del
día 200, cuando alguien arregle un fallo del buzón en uno de los dos.*

**El problema.** Nyx hereda ~90% del código de transporte y criptografía de Krypta:
`native-bridge/libp2p/bridge.go`, `:p2p-signaling` entero, `:core`, `:data`, y el nodo. Ese
código sigue recibiendo arreglos en Krypta (el historial reciente son justo eso: fiabilidad
del buzón, ack-after-persist, relay sin límites, notificaciones). Cada uno de esos arreglos
es un fallo que Nyx **también tiene**. Sin una vía de comunicación entre los dos, el que se
quede atrás acumula bugs ya resueltos en el otro.

**Lo que NO se debe hacer**: convertirlos en un monorepo, publicar un artefacto compartido,
o retrofitear Nyx dentro de Krypta. Cualquiera de las tres toca Krypta, que es exactamente
lo que no se puede tocar. Y un módulo compartido versionado acopla los calendarios de
release de dos productos que deben poder moverse por separado.

**Lo que sí, y cuesta muy poco:**

1. **Mantener el repo de Krypta como remoto `upstream` de solo lectura** en el repo de Nyx.
   Los dos comparten historial (el commit raíz de este checkout es "Commit inicial: Krypta
   v1.3"), así que `git fetch upstream && git log upstream/main --not HEAD --oneline`
   responde en un segundo a "¿qué se ha arreglado en Krypta que aquí falta?". Es la
   diferencia entre saberlo y no saberlo.

   El "solo lectura" no se deja a la disciplina de nadie — se impone en la config:

   ```sh
   git remote set-url origin        <repo-de-nyx>     # deja de apuntar a Krypta
   git remote add   upstream        https://github.com/dasilvabalautaro/Krypta.git
   git remote set-url --push upstream no_push          # push a upstream = error inmediato
   ```

   Con eso, un `git push upstream` cualquiera falla al resolver la URL en vez de escribir en
   producción de Krypta. Es la única salvaguarda que hace falta y cuesta una línea.
2. **`docs/SYNC-KRYPTA.md`**: una tabla de dos columnas — commit de Krypta, cómo se aplicó
   en Nyx (portado / no aplica / pendiente). Diez líneas de mantenimiento por trimestre;
   sin ella, dentro de un año nadie sabrá qué se revisó.

   **Mecánica concreta del porte** (definida el 14 ago 2026). Los dos repos comparten
   historial, así que un arreglo de Krypta se puede traer como parche en vez de reescribirlo.
   El truco que lo hace viable: **el parche es texto, así que se le aplica la misma
   sustitución de dos patrones que hizo el rebrand** — con eso las rutas *y* los
   identificadores del parche pasan a ser los de Nyx y el `git am` encaja con el contexto
   local. Un `tools/port-from-krypta.sh` de cinco líneas:

   ```sh
   #!/bin/sh
   # Uso: tools/port-from-krypta.sh <sha-de-krypta>
   set -e
   git fetch upstream
   git format-patch -1 --stdout "$1" \
     | sed -e 's/Krypta/Nyx/g' -e 's/krypta/nyx/g' \
           -e 's|infra/node/|infra/nyx-node/|g' \
     | git am
   git commit --amend --trailer "Ported-from-Krypta: $1" --no-edit
   ```

   La tercera regla del `sed` es necesaria porque `infra/node` no contiene la cadena
   "krypta" y sin ella el parche apunta a una ruta inexistente. El *trailer* deja el rastro
   en el propio commit, además de en `SYNC-KRYPTA.md`.

   **Cuándo falla y qué hacer.** Falla si el hunk toca líneas que en Nyx divergen de verdad
   (no por marca): protocolo del tablón, `LikeService`, la UI de descubrimiento. Entonces:
   `git am --abort`, y el parche a mano con `git apply --reject` (deja `.rej` con los hunks
   que no entraron) o directamente leyendo el diff. **No usar `git am -3` aquí**: su
   respaldo a tres vías resuelve contra el blob original de Krypta, que trae los
   identificadores viejos, y ensucia el conflicto en vez de simplificarlo.

   **Casos que no se portan y se anotan como "no aplica"**: commits de `infra/fdroid-repo`
   (no existe aquí), de la ficha o los docs de Play de Krypta, y cambios en
   `data/schemas/` (los esquemas divergen desde la v5). Un commit que toque
   `native-bridge/libp2p/*.go` sí se porta, pero **exige regenerar el AAR** después — el
   `.aar` no está en git.

   **Dirección inversa** (un fallo encontrado en Nyx que Krypta también tiene): el mismo
   script con el `sed` invertido, ejecutado **en el repo de Krypta**, nunca desde aquí.
3. **Concentrar las cadenas derivadas de marca en un solo sitio, del lado de Nyx.** Los
   parches que vengan de Krypta van a chocar en cada línea que contenga un ID de protocolo
   o un dominio HKDF. Si en Nyx esos valores salen de **una constante** en vez de estar
   incrustados en 15 sitios (`const appNS = "nyx"` en Go, del que se derivan
   `/nyx/msg/1.0.0` y compañía; un objeto `BrandDomain` en `:p2p-signaling` con los `info`
   de HKDF, el salt de llamada y el magic del respaldo), la superficie de conflicto baja a
   una línea. **Matiz importante**: esto es una constante de compilación, no lógica
   condicional en runtime — no contradice la decisión de que `infra/nyx-node` sea un
   binario propio y no un nodo parametrizado para dos protocolos (ahí el argumento del plan
   sigue siendo bueno, porque Nyx gana endpoints que Krypta nunca tendrá). Y **Krypta no se
   toca**: se queda con sus valores incrustados, cambiar sus IDs de protocolo rompería a
   sus usuarios.

**Expectativa realista**: aun con las tres medidas, portar un arreglo no será un
`cherry-pick` limpio — el renombrado de paquete cambia la ruta de cada archivo y la línea
de `package`/`import` de cada uno. Será "leer el diff de Krypta y aplicarlo a mano". Eso es
asumible porque el ritmo de cambios en la capa compartida es bajo y son cambios que ya
entiendes. Lo que no es asumible es no enterarse de que existen.

---

## Fase 0 — Riesgos de política que conviene resolver antes de construir

*Sección añadida el 14 de agosto de 2026 al revisar el plan, y **elevada de prioridad el
mismo día** al fijarse que la distribución es solo Play (decisión 8). Señala dos cosas que,
si se resuelven mal, invalidan trabajo de las Fases 3–5 después de haberlo hecho. Ninguna
bloquea la Fase 1 (el rebrand es útil de todas formas).*

**Por qué esto ya no es un apartado de riesgos sino una puerta.** Mientras existía un canal
alternativo, un rechazo de Play era un contratiempo de distribución. Sin él, un rechazo es
**el final del producto**: no hay dónde publicarlo. Eso cambia la respuesta correcta a los
dos puntos de abajo — donde antes cabía "asumimos el riesgo y lo documentamos", ahora solo
cabe "cumplimos". En particular, el mecanismo de reporte de la Fase 4 deja de tener la
opción solo-local.

1. **"Contenido para adultos" vs. Google Play.** Play permite apps de citas, pero
   **prohíbe contenido sexual explícito**, y una app cuyo posicionamiento declarado sea
   "citas, relaciones y contenido para adultos" entra en revisión con esa lupa. Hay que
   decidir explícitamente qué significa "adulto" aquí: *público 18+ y conversaciones
   privadas sin censura* (compatible con Play, es lo mismo que hace cualquier app de
   citas y lo mismo que hace un mensajero E2EE) es muy distinto de *distribuir contenido
   explícito como función del producto* (no compatible). El plan asume lo primero; hay que
   escribirlo así en la ficha, en la ayuda y en el cuestionario de clasificación.
2. **Reportar sin backend** — ver Fase 4. Es el punto más probable de rechazo, y el que hay
   que dar por decidido en favor de cumplir: la política de contenido generado por usuarios
   exige un sistema **dentro de la app** para denunciar y bloquear, **y** que el
   desarrollador pueda actuar sobre lo denunciado. Un reporte que solo escribe un archivo
   en el teléfono del denunciante no llega a nadie y no lo satisface.

Coste de decidirlo ahora: una tarde de lectura de políticas. Coste de decidirlo después de
la Fase 4: el mecanismo de reporte y buena parte del texto de la ficha, rehechos — y sin
un segundo canal donde publicar mientras se rehacen.

### Resultado — Fase 0 cerrada el 14 de agosto de 2026

Las decisiones, su justificación y las tareas que se derivan de ellas están en
**[docs/NYX-POLITICA-CONTENIDO.md](NYX-POLITICA-CONTENIDO.md)**. En resumen:

- **Nyx es una app de citas 18+ con mensajería E2EE, no una app de contenido adulto.** El
  **tablón** va limpio y moderado (es la superficie regulada); la **conversación privada**
  queda sin censura al amparo de la excepción de *contenido sexual incidental*. La ficha no
  puede usar léxico adulto: es infracción por sí sola, con independencia del producto.
- **Denuncia con canal real al operador**, confirmada; la variante solo-local queda
  descartada.
- **Mercado inicial: Latinoamérica**, lo que deja fuera las leyes estadounidenses de
  verificación de edad y hace suficiente el gate 18+ autodeclarado.

**Hallazgo que el plan no contemplaba**: además de la política de contenido generado por
usuarios, Nyx cae obligatoriamente bajo **Child Safety Standards** por ser app de citas —
y esa política dice expresamente que la presencia o ausencia de menores en la app es
irrelevante. Añade estándares públicos que prohíban CSAE, punto de contacto designado de
seguridad infantil, proceso de reporte a NCMEC, formulario propio en Play Console y
aceptación obligatoria de unos Términos de uso antes de publicar nada. Las tareas nuevas
están en la sección 3 de ese documento y replicadas en la checklist de abajo.

**Consecuencia para la Fase 3b**: el avatar generado se publica en el tablón, la superficie
regulada, así que el riesgo de que produzca un rostro que parezca de menor deja de ser un
problema de calidad y entra en Child Safety Standards. El criterio de evaluación de la
Alternativa A se endurece: el fallo tiene que ser *estructuralmente improbable*, no
*estadísticamente raro*.

---

## Fase 1 — Rebrand mecánico

**Hallazgo clave** (verificado 14 ago): los identificadores `Krypta*` (clases, vals, tag
de log, estilo XML `Theme.Krypta`, ~29 iconos en `KryptaIcons.kt`) no son parte del string
de paquete `chat.neto.krypta` — son solo nombres que empiezan por "Krypta". No existe uso
en mayúsculas `KRYPTA` en ningún sitio. Esto significa que una sola sustitución de dos
patrones sensible a mayúsculas (`Krypta`→`Nyx`, `krypta`→`nyx`) sobre todos los
archivos afectados cubre paquetes, imports, nombres de clases/funciones, nombres de
recursos XML, tags de log, IDs de protocolo Go y strings de dominio HKDF — todo en un
solo pase, sin tratar "renombrar paquete" y "renombrar identificadores" como pasos
separados.

*Corrección 14 ago — dos matices que el hallazgo original pasaba por alto:*

- **La sustitución de dos patrones no cubre todo lo derivado de la marca.** El magic del
  respaldo de identidad es `"KRBK1"` (`IdentityBackup.kt:106`, **usado como AAD** del
  AES-GCM) y la extensión de archivo es `.krbk`; ninguno contiene la cadena "krypta", así
  que ningún sed los toca. Hay que renombrarlos a mano (ver paso 8) — no hay usuarios, así
  que romper compatibilidad del formato es gratis ahora y caro después.
- **El sed no puede limitarse a `.kt`/`.xml` bajo `src/`.** Los usos reales de "krypta"
  fuera de ahí, todos confirmados por grep, son: `settings.gradle.kts`, los 5
  `build.gradle.kts` (namespaces), `native-bridge/build.gradle.kts:34`
  (`api(files("libs/krypta-p2p.aar"))` + dos comentarios), `app/proguard-rules.pro:8`,
  `native-bridge/libp2p/*.go`, `infra/node/*.go`, `native-bridge/libp2p/build-aar.sh`,
  `.gitignore` (rutas `/infra/node/…` y comentario del keystore), `keystore.properties`
  (ignorado por git, pero apunta a `~/keystores/krypta/krypta.jks`), `docs/*` e
  `infra/*/README.md`/plists. La lista de pasos de abajo ya los cubre uno a uno; lo que
  cambia es que el paso 9 **no puede exigir cero resultados de grep en todo el árbol**
  (ver ahí).

**Pasos, en este orden:**

0. **Preflight crítico — el remoto** (*añadido 14 ago, riesgo real detectado en el repo*):
   este checkout tiene `origin = https://github.com/dasilvabalautaro/Krypta.git`, es decir
   **apunta al repositorio de Krypta, que sigue vivo y en desarrollo**. Un `git push`
   desde aquí publica Nyx dentro del repo de Krypta, y un push a `main` sería destructivo
   para un producto en producción. **Antes de crear ninguna rama**: crear el repo de Nyx y
   `git remote set-url origin <repo-de-nyx>` (o `git remote remove origin` mientras tanto).
   Verificar con `git remote -v` que ya no aparece "Krypta" antes de tocar nada más.
   El historial compartido sí se conserva a propósito (ver "Relación con Krypta"): los 13
   commits actuales, empezando por "Commit inicial: Krypta v1.3", son la base común y
   sirven para portar parches entre los dos proyectos.
1. **Preflight**: `git status` limpio, rama nueva. Nunca tocar `build/` (se regenera).
2. **Mover paquetes físicos + sustitución universal (un commit)**:
   `git mv` cada subárbol `.../chat/neto/krypta/...` → `.../chat/neto/nyx/...` en los 5
   módulos (`app/src/{main,test,androidTest}`, `core/src/main`, `data/src/main`,
   `native-bridge/src/main`, `p2p-signaling/src/{main,test}`), luego correr el sed de
   dos patrones sobre `.kt`/`.xml` bajo esos `src/`. **Excepción**: no tocar aún las
   referencias a `chat.neto.krypta.bridge.*` (paquete generado por gomobile, vive en
   los imports de `Libp2pNode.kt`) — eso cambia en lockstep con el paso 4, no antes, o
   el build queda roto a medias.
3. **Gradle (manual)**: `settings.gradle.kts:25` → `rootProject.name = "Nyx"`;
   `app/build.gradle.kts` → `namespace`/`applicationId` = `chat.neto.nyx`,
   `versionCode = 1`, `versionName = "1.0"` (producto nuevo, ficha nueva en Play —
   **no** tocar `compileSdk`/`minSdk`/`targetSdk`/`ndkVersion` ni nada en
   `gradle/libs.versions.toml`); namespaces de `core`/`data`/`native-bridge`/`p2p-signaling`
   (`core:6`, `data:8`, `native-bridge:8`, `p2p-signaling:8`, líneas confirmadas).
   **`native-bridge/build.gradle.kts:34`** (`api(files("libs/krypta-p2p.aar"))`) se cambia
   **en lockstep con el paso 4**, no aquí: si se renombra antes de regenerar el AAR, el
   build rompe. *Corrección 14 ago*: el `applicationId` nuevo también cambia la authority
   del `FileProvider`, pero está declarada como `${applicationId}.fileprovider`
   (`AndroidManifest.xml:106`) — se resuelve sola, **no tocarla a mano**.
4. **Go bridge + AAR gomobile (commit propio, el paso de más riesgo)**:
   `native-bridge/libp2p/bridge.go` — renombrar todos los protocol IDs `/krypta/...`
   → `/nyx/...` (msg `:44`, call `:453`, video `:538`, mailbox put/get `:636-637`, wake
   `:825`) y los context tags (`"krypta-msg"`/`"krypta-call"`/`"krypta-video"`, líneas
   304/526/620), más `discovery_test.go` (`"krypta-msg-test"`,
   `"krypta-test-rendezvous-…"`). `build-aar.sh:31` → `-javapkg=chat.neto.nyx`, artefacto
   `nyx-p2p.aar` (y su comentario de cabecera). Correr el script, y **solo entonces**
   actualizar (a) el bloque de imports excluido en `Libp2pNode.kt` al nuevo paquete
   `chat.neto.nyx.bridge` y (b) `native-bridge/build.gradle.kts:34` al nombre nuevo del
   `.aar`. `app/proguard-rules.pro:8` → `-keep class chat.neto.nyx.bridge.** { *; }`.
   Smoke-test `nativePing()` antes de tocar nada más (un mismatch JNI-por-nombre falla
   en runtime, no en compilación). El `.gitignore` no necesita cambio para el AAR (el
   patrón es `/native-bridge/libs/*.aar`), **pero sí** para los binarios del nodo (paso 5).
5. **`infra/node` → `infra/nyx-node` (Go, módulo separado, sin riesgo de AAR)**:
   `git mv` primero (ver corrección de la decisión 2), luego los mismos renombrados de
   protocol ID en `main.go:36` (`kryptaProtocol`, además del string),
   `mailbox.go:39-40` y `wake.go:27` — deben coincidir carácter a carácter con
   `bridge.go` o el teléfono no puede hablar con el relay. *Corrección 14 ago*: hay que
   actualizar también **`.gitignore`** (`/infra/node/node` y `/infra/node/dist/` →
   `/infra/nyx-node/…`); si no, los ~190 MB de binarios Go quedan sin ignorar y acaban
   en el primer commit. Y el módulo Go sigue pinneado a go-libp2p v0.38 + Go 1.22 por
   Catalina: **no aprovechar el rebrand para subirlo**, es un cambio independiente.
6. **Separadores de dominio criptográfico, `:p2p-signaling` (manual, 4 strings,
   sensible a seguridad)**: `SafetyNumber.DOMAIN`, el `info` HKDF de
   `AesGcmMessageCipher`, el de `RendezvousService`, y `CallService.CALL_KEY_SALT` —
   de `krypta-*` a `nyx-*`. Sin usuarios reales todavía, así que no hay preocupación de
   compatibilidad — solo revisar tests con vectores hardcodeados que citen los
   strings viejos.
7. **Identificadores de almacenamiento**: SharedPreferences (`krypta_settings`,
   `krypta_identity`) → `nyx_*`; Room DB `krypta.db` → `nyx.db`; directorio de adjuntos
   `krypta_files` → `nyx_files` (`DiskFileStore.kt`, `AudioRecorder.kt`,
   `ChatViewModel.kt`, `file_paths.xml:4` — ojo: ahí el `name=` **y** el `path=`).
   Instalación nueva con `applicationId` nuevo = sin datos previos que migrar, esto es
   solo corrección, no migración.
8. **Manifest/notificaciones/varios**: `.KryptaApplication`→`.NyxApplication`,
   `.KryptaForegroundService`→`.NyxForegroundService`, `Theme.Krypta`, acciones de
   broadcast `chat.neto.krypta.CALL_ANSWER/DECLINE` (+ `CallActionReceiver.kt`);
   `KryptaNotifications.kt`→`NyxNotifications.kt` con canales nuevos directamente en
   `v1` (no hay legado que limpiar en un `applicationId` nuevo); esquema QR
   `krypta:verify:`; tags de wake-lock/hilo (cosmético); renombrar archivos
   `Krypta*.kt` → `Nyx*.kt`.
   **Respaldo de identidad** (*corrección 14 ago*, el sed no lo alcanza): el nombre por
   defecto `"krypta-identidad.krbk"` (`SettingsScreen.kt:430`) **sí** lo cambia el sed,
   pero el magic `"KRBK1"` (`IdentityBackup.kt:106`, AAD del GCM), la extensión `.krbk`
   y sus menciones en `AndroidManifest.xml:42`/`backup_rules.xml`/
   `data_extraction_rules.xml` no — renombrar a `"NYXB1"`/`.nybk` a mano y actualizar
   `IdentityBackupTest` (hay casos que verifican el magic como AAD).
   **Álbum de capturas**: `ScreenSecurity.kt:73,79` guarda en `Pictures/Krypta` con
   prefijo `Krypta_`; el sed lo cubre, pero anotarlo porque es visible al usuario y
   aparece en la ayuda y en la política de privacidad.
   `DEFAULT_BOOTSTRAP` en `Libp2pNode.kt` se actualiza **al final**, solo cuando el nodo
   de Nyx esté arrancado y confirmado en vivo — si no, la app nueva no tiene bootstrap al
   que unirse. Ojo: las tres líneas actuales (VPS + Mac + Windows de Krypta) se sustituyen
   por **una sola**, la del nodo propio de Nyx (`/ip4/<IP>/tcp/4001/p2p/<PeerID>`, directo,
   sin Cloudflare); el formato de lista se mantiene para cuando llegue el segundo nodo. *Corrección 14 ago, importante*: el sed del paso 2 deja `DEFAULT_BOOTSTRAP`
   en un estado **peor que roto** — hostnames `nyx*.neto.chat` que aún no existen
   apuntando a los **PeerID de los nodos de Krypta**. Un multiaddr con PeerID equivocado
   no falla claro, falla en el handshake Noise; hasta cerrar el paso 1.13 hay que asumir
   que "sin conexión WAN" es lo esperado, no un bug.
8-bis. **Firma de release** (*corrección 14 ago, ausente del plan original*):
   `keystore.properties` y el comentario del `.gitignore` apuntan a
   `~/keystores/krypta/krypta.jks`, y `hasReleaseKeystore` en `app/build.gradle.kts`
   depende de ese archivo. Un `applicationId` nuevo es una app nueva en Play y merece
   **su propio keystore** (`~/keystores/nyx/nyx.jks`): reutilizar el de Krypta acopla la
   rotación y el compromiso de dos productos que ya no comparten nada. Crearlo, apuntar
   `keystore.properties` ahí, y verificar que `assembleRelease` sigue firmando (o cae al
   debug keystore, que es el comportamiento actual sin `keystore.properties`).
9. **Verificación** (*corregida 14 ago — la formulación original era inalcanzable*):
   `grep -ril krypta .` sobre todo el árbol **nunca** dará cero, y exigirlo lleva a
   borrar menciones legítimas. Lo que se verifica es:
   - **Cero en código y configuración ejecutable**: `*.kt`, `*.xml`, `*.go`, `*.kts`,
     `*.pro`, `*.sh`, `*.plist`, `*.service`, `*.json` (`data/schemas/`), excluyendo
     `build/`, `.git/`, `.gradle/`.
   - **Menciones que se quedan a propósito, y por qué**: (a) `keystore.properties` y el
     comentario del `.gitignore`, **hasta** completar el paso 8-bis (después, cero);
     (b) `CLAUDE.md` y `docs/*` conservan referencias a Krypta como **proyecto hermano**
     del que Nyx hereda el diseño y con el que comparte base de ingeniería — eso es
     documentación correcta, no residuo (la Fase 6 define qué se reescribe y qué se
     mantiene como referencia compartida); (c) nada más. `infra/fdroid-repo/` deja de ser
     una excepción porque se elimina de **esta copia** (decisión 8 corregida; en el repo
     de Krypta sigue en uso).
10. **Build limpio + tests**: `./gradlew clean :app:assembleDebug` y
    `./gradlew testDebugUnitTest` verdes.
11. **Smoke test en vivo, sin depender del redeploy de infra** (*corrección 14 ago*): el
    plan original ponía la prueba de dos dispositivos antes de la sección de infra, pero
    esa prueba **necesita un nodo Nyx vivo** y por tanto los pasos 1.12–1.13. Para no
    bloquear el cierre del rebrand detrás de tres despliegues, la verificación funcional
    se hace primero **contra un nodo local**: `cd infra/nyx-node && go run . -listen
    /ip4/0.0.0.0/tcp/4101`, `adb reverse tcp:4101 tcp:4101`, y meter ese multiaddr por el
    campo "Nodo WAN (bootstrap)" de Ajustes (que ya acepta override). Eso prueba el AAR
    nuevo, los protocol IDs nuevos y el buzón end-to-end sin tocar el VPS. La prueba de
    dos teléfonos contra `nyx.neto.chat` queda como cierre real del paso 1.13.

### Despliegue de infra (caja propia, cero contacto con Krypta)

- `git mv infra/node/ infra/nyx-node/` (que luego diverge con el tablón en la Fase 3) en
  vez de parametrizar un solo binario para dos namespaces de protocolo — Nyx gana
  endpoints propios que Krypta nunca tendrá, un binario propio es más simple que lógica
  condicional. Rename, no copia (ver decisión 2). El binario de Krypta que corre en
  producción se sigue construyendo desde el repo de Krypta.
- **Nodo primario: VPS propio.** Mismo perfil que funcionó para Krypta —
  **Vultr São Paulo** (DigitalOcean no tiene ninguna región en Sudamérica, y para un relay
  de voz/vídeo la región manda sobre la marca), Ubuntu 24.04, 1–2 vCPU / 2 GB / 20–40 GB.
  Si el presupuesto lo permite, **4 GB para el primario de Nyx**: el tablón añade
  almacenamiento y consultas que Krypta no tiene, y el relay moverá vídeo desde el día uno.
- Usuario de sistema `nyx`, estado en `/var/lib/nyx`, `node.key` propio → **PeerID nuevo**.
  Unidad systemd `nyx-node.service` derivada de la de Krypta (`Restart=always`,
  `LimitNOFILE=65535`), con `Description`/`StateDirectory`/`User`/`Group` propios.
  **Puertos estándar 4001 / ws 8081**: al no compartir caja no hay colisión, y desviarse de
  los números conocidos solo añade confusión.
- **Sin Cloudflare Tunnel para el primario.** Con IP pública propia, el nodo escucha
  directo en `/ip4/…/tcp/4001` **y QUIC en 4001/udp** (flag `-quicport 4001`), que es
  exactamente lo que se ganó al mover Krypta al VPS: menos latencia (p50 107 ms vs.
  146–163 ms a través de Cloudflare), DCUtR mejor y sin reciclado de WebSockets cada ~100 s.
  El listener `ws` (`-wsport 8081`) se deja activo como **camino alternativo** para redes
  que bloquean puertos altos, pero deja de ser la vía principal, y con él desaparece toda
  la fragilidad que motivó el ciclo adaptativo de `wanLoop`.
- **Subir `net.core.rmem_max` al aprovisionar**, no después: en Krypta quedó pendiente y
  quic-go avisa en cada arranque. Es una línea de `sysctl` y es el momento de ponerla.
- **Límites finitos en el relay** antes de abrir a público. Krypta usa
  `relayv2.WithInfiniteLimits()` porque la alternativa cortaba las llamadas a los ~20 s,
  pero infinito en un relay público es una invitación a que un tercero use la caja de
  ancho de banda gratis. Dimensionar un límite que aguante una videollamada larga (el dato
  de referencia: 128 KiB por conexión agotaba en ~20 s de audio) en vez de quitarlos.
- **Copia del `node.key` fuera de la caja** desde el primer día. En Krypta sigue pendiente;
  perderlo significa perder el PeerID, y el PeerID está incrustado en `DEFAULT_BOOTSTRAP`
  de todas las apps instaladas.
- **`deploy-vps.sh`**: la copia de Nyx cambia nombre de servicio, usuario y directorio, y
  no debe poder apuntarse por accidente al host de Krypta — el host destino va como
  parámetro explícito, sin valor por defecto heredado.
- **Segundo nodo: pendiente, y hace falta antes de abrir a público.** Un solo nodo es punto
  único de fallo del buzón, del wake y del relay — que es precisamente por lo que Krypta
  acabó con tres. El cliente ya soporta lista de bootstrap (`MailboxPut` con failover,
  `MailboxFetch` drenando todos, un wake por nodo), así que añadirlo después no cuesta
  código, solo una caja. Recomendación: **un segundo VPS propio en otra región/proveedor**,
  no una máquina doméstica — las de Krypta (Mac Catalina, PC Windows) están documentadas
  como puntos únicos de fallo y además son de Krypta. No es bloqueante para la Fase 1: con
  un nodo se puede desarrollar y probar todo.
- `DEFAULT_BOOTSTRAP` en el cliente solo se fija una vez el nodo de Nyx haya arrancado y
  mostrado su PeerID real.

---

## Fase 2 — Modelo de datos y migraciones Room

Esquema actual en v4 (verificado: `KryptaDatabase.kt:13` `version = 4`, última migración
`MIGRATION_3_4` en `Migrations.kt:24`; tras el rebrand,
`data/src/main/java/chat/neto/nyx/data/`). Se añade una `MIGRATION_4_5` con las tablas
nuevas (aditivas e independientes entre sí).

*Corrección 14 ago, dos cosas*: (a) `exportSchema=true` escribe `data/schemas/`, que hoy
contiene solo `4.json` — al subir a v5 hay que **commitear `5.json`**, o el test de
migración no tiene contra qué validar; (b) siendo honestos, `MIGRATION_4_5` es **código
muerto para los usuarios** (Nyx v1 se instala directamente en v5, no hay base instalada
que migrar). Se escribe igualmente por una razón concreta y no por ritual: el teléfono del
autor va a tener builds intermedios de Nyx en v4 durante la Fase 1, y sin la migración
Room lanza en runtime al arrancar el primer build con v5. Si esa situación no llega a
darse, se puede sustituir por un `fallbackToDestructiveMigrationFrom(4)` acotado — lo que
**no** se puede es reintroducir el fallback destructivo general.

- **Borrador de "mi perfil"**: no como tabla Room — es una única fila por dispositivo
  sin necesidad relacional, mejor como `MyProfilePrefs.kt` (mismo patrón
  SharedPreferences que `ThemePreference.kt`/`AppLock.kt`). Campos: `nickname`,
  `ageMin`/`ageMax` (rango tipo slider), `interests: List<String>`, `bio` (con tope de
  longitud), `tipAddress` (string libre, respalda la propina P2P opcional de la Fase
  6/monetización), **`avatarBytes: ByteArray?`** (el avatar de solo rostro generado
  por la app — ver Fase 3b; formato/tope de tamaño igual que las imágenes en línea del
  chat, ≤58 KiB, para que quepa dentro del presupuesto de la tarjeta del tablón). Solo
  se publica una instantánea al tablón cuando el usuario lo pide.
- **Estado de like/match** (`LikeEntity`, nueva, Room): `peerId` (PK), `sentAt`,
  `receivedAt`, `matchedAt` (se fija en cuanto ambos son no-null), `source`. Máquina de
  estados: cada dispositivo detecta la mutualidad de forma independiente cuando tiene
  tanto un `sentAt` como un `receivedAt` para el mismo peer — sin protocolo de
  coordinación más allá de los dos "Like" unidireccionales. Un like solo-recibido
  **nunca** desbloquea mensajería (ahí está el freno anti-acoso). Bloquear/eliminar un
  peer debe limpiar o ignorar su fila.
- **Peers bloqueados** (`BlockedPeerEntity`, nueva, Room): `peerId` (PK), `blockedAt`,
  `reason` opcional.
- Nuevos DAOs (`LikeDao`, `BlockedPeerDao`) y repos en `:core`/`:data` calcando
  exactamente la forma de 3 capas de `ContactDao`/`ContactRepository`.
- `addContact` (o el flujo equivalente desde un match) gana un guard sibling al de
  auto-añadirse: `require(!blockRepo.isBlocked(peerId))`.

---

## Fase 3 — Bridge Go/nativo: tablón + "like"

- **`infra/nyx-node/board.go`** (calca el patrón cuota+TTL ya probado en
  `mailbox.go`): protocolos `/nyx/board/publish/1.0.0` y `/nyx/board/query/1.0.0`.
  Almacenamiento `boarddir/<categoría>/<peerId>.json` — **una tarjeta activa por autor
  por categoría, se sobreescribe al republicar** (no append-only como el buzón; limita
  de forma natural el flood de una sola identidad, y como la tarjeta no es anónima —
  quien la consulta necesita el PeerID igualmente para poder mandar un Like — no hace
  falta más). Campos de la tarjeta calcan `MyProfilePrefs`, **en claro** (no cifrado —
  a diferencia del buzón, ser descubrible es el punto). TTL recomendado 48h como flag
  `-boardttl` ajustable durante el beta. Cuota por categoría (no por autor, ya limitado
  a 1 por el diseño de sobreescritura). Consulta de solo lectura, sin ack/borrado.
  *Añadido 14 ago*: hacen falta dos límites más que el plan original no fijaba —
  **tamaño máximo de tarjeta** (`-boardmaxcard`, sugerido 96 KiB: el avatar de ≤58 KiB
  más el texto, con holgura) y **borrado explícito** (`/nyx/board/delete/1.0.0`, o
  publicar una tarjeta vacía). Sin borrado, "quitar mi perfil del tablón" significa
  esperar hasta 48h, lo cual es inaceptable para una app donde el usuario puede querer
  desaparecer ya — y es exactamente el tipo de control que exige el RGPD. Autenticado
  con la identidad del stream, igual que el buzón.
- **Derivación del tema de descubrimiento**: nuevo objeto hermano de
  `RendezvousService` (no una extensión — modelo de confianza distinto: sin secreto
  compartido, derivable públicamente solo a partir de una categoría):
  `HKDF("nyx-discover-v1", categoría)`. `ISignalingService.announce`/`findPeers` ya
  aceptan `ByteArray` arbitrario, no hace falta cambio de interfaz para esta mitad.
- **`bridge.go`**: nuevas funciones expuestas a gomobile `PublishCard`/`QueryBoard`
  (convención string/JSON, igual que el buzón — gomobile no maneja bien genéricos
  complejos). `Libp2pNode.kt`: `publishCard`/`queryBoard` suspend, delegando a `Bridge`.
- **Envelope "Like"** (`MessageEnvelope.kt`, confirmado el set actual de prefijos
  `T/R/I/F/K/D/C`): nuevo prefijo `L` — `"L\n<ts>"`, mismo patrón textual que los
  demás. Entrega reutiliza el camino directo-o-buzón existente sin transporte nuevo,
  igual que `encodeCall`.
- **Cambio estructural necesario**: `ChatService.onReceived` (confirmado,
  `ChatService.kt:608-609`) hoy corta con
  `val contact = contacts.findByPeerId(peerId) ?: return null` — pero un Like llega
  por definición de alguien que **todavía no es contacto**. Ya existe
  `keyExchange.sharedSecretWith(peerId)` (confirmado, usado hoy en `addContact` para
  peerIds arbitrarios sin fila previa en contactos) para derivar el secreto ECDH al
  vuelo sin necesitar un `Contact` guardado. Nuevo `LikeService.kt` (`:p2p-signaling`,
  mismo patrón `@Singleton` Hilt que `ChatService`): expone `sendLike(peerId)` y tiene
  su propio punto de entrada para envelopes entrantes que, específicamente para el
  prefijo `L`, intenta descifrar aunque el lookup de contactos falle (el tag de
  autenticación AES-GCM es en sí mismo el chequeo de "esto es realmente para mí" — un
  tag inválido se descarta en silencio). Persiste en `LikeEntity` y dispara la
  transición de match mutuo.
- **Riesgo a vigilar**: permitir que cualquier PeerID desconocido dispare un intento de
  descifrado es una superficie de DoS/CPU real (hoy el tráfico de desconocidos se
  descarta *antes* de intentar descifrar). Recomendado: rate-limit explícito por PeerID
  emisor en `LikeService` (p. ej. máx N intentos/hora) como tarea concreta de esta
  fase, no un "ya lo veremos". *Precisión 14 ago*: lo caro no es el AES-GCM (microsegundos)
  sino el **X25519 de `keyExchange.sharedSecretWith(peerId)`**, que se hace una vez por
  PeerID desconocido — así que el rate-limit debe ir **antes** de derivar el secreto, y
  conviene cachear el secreto derivado por peer.
- **Segundo riesgo, no cubierto en el plan original y más grave que el de CPU**: publicar
  una tarjeta hace tu PeerID **público**, y el camino de entrega de un Like es el buzón
  del nodo, que tiene cuota **por destinatario** (200 mensajes / 5 MiB, `mailbox.go`).
  Un solo abusador puede llenar tu cuota con Likes y con eso **bloquear la entrega de tus
  mensajes reales** — el mecanismo anti-acoso se convierte en un DoS de mensajería. La
  mitigación tiene que estar en el nodo, no en el cliente: dar a los Likes un protocolo
  propio (`/nyx/like/put/1.0.0`) con **su propio bucket de cuota**, separado del buzón de
  mensajes, y un tope bajo por (emisor, destinatario) — un Like repetido del mismo peer
  sobreescribe, igual que una tarjeta del tablón. Es una tarea de la Fase 3, no un
  "después".

---

## Fase 3b — Motor de avatar (rostro generado, dos alternativas)

**Contexto**: una tarjeta del tablón solo de texto es fría y poco atractiva. Se decidió
que la tarjeta lleve **una única imagen posible: un avatar de solo rostro generado por
la app** — nunca una foto real, nunca cuerpo completo. Esto no es un detalle
cosmético: reemplazar "foto real" por "avatar generado" es lo que permite añadir
atractivo visual **sin** reintroducir el problema de privacidad que todo el diseño
evita (una foto real es dato personal identificable de máxima sensibilidad). Sirve
además el mismo propósito narrativo de "Nyx": el usuario construye la cara que
presenta, no expone la suya.

Hay dos caminos posibles para generarlo. **No se elige ahora — se deja documentada la
decisión pendiente y el punto exacto donde se resuelve**, de forma que el resto del
plan (modelo de datos, protocolo del tablón, UI) funcione igual sin importar cuál gane.

### Alternativa A — Motor especializado propio (on-device, en desarrollo)

El usuario ya está desarrollando un modelo propio, optimizado para mobile, dedicado
únicamente a generar rostros a partir de un prompt de texto, con filtros de contenido
incorporados al propio pipeline.

- **A favor**: coherente con la filosofía de "cero terceros" que ya tiene todo el
  proyecto (sin SDKs externos, sin llamadas HTTP fuera de la propia infraestructura
  P2P) — corre enteramente en el dispositivo, el prompt de texto nunca sale del
  teléfono, no depende de red ni de una API de pago externa. Da la experiencia más
  rica ("escribe lo que imaginas").
- **Riesgo real, no cosmético**: un modelo de generación de rostro por texto libre es
  la superficie de abuso más obvia de toda la app de citas — hay que evitar de forma
  fiable que genere caras que parezcan menores, que imiten a una persona real
  (suplantación/acoso), o contenido explícito. El filtrado de prompt + un clasificador
  posterior a la generación es trabajo de ingeniería real, no un checkbox.
- **Estado**: en desarrollo, puede no llegar a tiempo o no dar la calidad/tamaño de
  modelo/latencia necesarios en un teléfono de gama media.

### Alternativa B — Constructor paramétrico (fallback probado, bajo riesgo)

Catálogo curado de rasgos (forma de cara, ojos, piel, pelo, expresión, etc., estilo
Bitmoji/Memoji) que el usuario combina para armar su avatar — sin texto libre.

- **A favor**: 100% on-device, sin modelo pesado, sin superficie de "prompt
  injection" que vigilar — el espacio de combinaciones ya está curado de antemano, así
  que es estructuralmente imposible que produzca algo ofensivo o que imite a alguien.
  Mucho más barato de construir y de mantener.
- **Contra**: pierde la magia de "generar desde una idea libre"; visualmente más
  limitado que un modelo generativo bien afinado.

### Punto de decisión

Antes de comprometerse a integrar la Alternativa A, evaluarla contra criterios
concretos: calidad visual aceptable, tamaño del modelo compatible con el APK (ya carga
~75 MB de AAR nativo), latencia de generación aceptable en gama media, y una tasa de
falsos negativos del filtro de contenido que el usuario considere suficiente. Si no
cumple, se cae a la Alternativa B **sin bloquear el resto del plan** — ninguna otra
fase depende de cuál gane.

### Integración común (independiente de cuál alternativa se use)

- Nueva pantalla "Crear tu avatar" dentro del flujo de `ProfileEditorScreen.kt`
  (Fase 4) — cualquiera de las dos alternativas se monta detrás de la misma pantalla y
  del mismo campo `MyProfilePrefs.avatarBytes` (Fase 2).
- El resultado se guarda como bytes de imagen ya comprimidos (mismo formato/tope que
  las imágenes en línea del chat existente, `ImageCodec` ya resuelve esa compresión —
  reutilizar, no reinventar) y viaja junto al resto de la tarjeta al publicarla en el
  tablón (Fase 3) dentro del mismo presupuesto de tamaño por tarjeta.
- Solo rostro, nunca cuerpo — límite ya decidido, aplica a ambas alternativas por
  igual, y debe quedar reflejado en la política de privacidad (Fase 6) como parte de
  la descripción del tablón.

---

## Fase 4 — Funcionalidad de app

- **Bloqueo**: chequeo temprano en cada camino de entrada — `ChatService.onReceived`
  gana `if (blockRepo.isBlocked(peerId)) return null` **antes** del lookup de
  contacto; el punto de entrada de `LikeService` gana el mismo guard (es el camino que
  más importa, ya es alcanzable por desconocidos); el aceptar llamada/video de
  `CallService` igual. UI: `BlockedPeersScreen.kt` (lista + desbloquear, calca
  `ConversationsScreen.kt`), acción "Bloquear" junto a "Eliminar contacto" en
  `ChatScreens.kt` reutilizando `ConfirmDeleteDialog` tal cual, y la misma acción
  disponible desde una tarjeta del tablón **antes** de llegar a match. Bloquear un
  contacto existente llama a `ChatService.deleteContact` y luego persiste
  `BlockedPeerEntity`.
- **Reportar** (bloqueo + exportar local, decisión confirmada — sin canal al relay):
  reutiliza el patrón de exportación cifrada ya existente en `BackupManager.kt`/
  `IdentityBackup.kt`, pero como paquete separado y más simple (el usuario exporta su
  propio texto plano ya descifrado para entregárselo a quien decida — un candado de
  contraseña aquí no aporta confidencialidad real). Contenido: PeerID reportado, una
  porción de conversación seleccionada localmente, timestamp, nota libre opcional.
  Documentar explícitamente la limitación (sin backend de moderación) en la Fase 6, no
  disimularla.
  **Revisado 14 ago — la variante solo-local queda descartada.** Al fijarse que la
  distribución es solo Play (decisión 8), el reporte sin canal deja de ser una opción con
  riesgo asumible: la política de contenido generado por usuarios exige un sistema
  **dentro de la app** para denunciar y bloquear *y* que el desarrollador pueda actuar
  sobre lo denunciado, Play es notablemente más estricto con apps de citas, y sin canal
  alternativo un rechazo no tiene salida. Diseño que cumple **sin** traicionar la postura
  de "cero servidor de contenido": el reporte viaja al nodo como un **sobre cifrado a la
  clave pública del operador** (el nodo no puede leerlo en reposo; solo quien opere la
  moderación), con cuota y TTL como todo lo demás, y la exportación local se mantiene
  **además**, para que el usuario conserve su copia. Sigue sin haber cuentas ni identidad
  real, pero existe un canal y existe capacidad de actuar: expulsar un PeerID del tablón.
  Lo que sí hay que documentar honestamente es el alcance de esa moderación — se actúa
  sobre el tablón, no sobre las conversaciones, que son E2EE y el operador no puede leer.
- **Gate de edad 18+** (decisión confirmada — toda la app, no solo descubrimiento):
  diálogo de autodeclaración al primer inicio, flag persistido localmente (nuevo
  `AgeGate.kt`, mismo patrón SharedPreferences que `AppLock`/`ThemePreference`), guard
  a nivel de navegación en el arranque antes de cualquier pantalla, incluida la
  mensajería general. Documentar como limitación real: solo autodeclaración, no hay
  verificación posible sin backend de identidad — dejarlo explícito para el
  cuestionario de clasificación de contenido de Play.
- **Descubrimiento/perfil/like UI**: `DiscoveryScreen.kt` (selector de categoría +
  lista de tarjetas) con `DiscoveryViewModel.kt` (mismo patrón de orquestación Hilt que
  `ChatViewModel`) sobre un nuevo `BoardService` en `:p2p-signaling`.
  `ProfileEditorScreen.kt` edita `MyProfilePrefs`, "Publicar" llama a `publishCard`.
  `DiscoveryCard.kt` (apodo/franja de edad/intereses/bio, "Me interesa" → `sendLike`,
  overflow → Bloquear/Reportar). Transición de match como `Flow` sobre
  `LikeRepository` (mismo patrón reactivo que `ChatService.observeContacts()`),
  muestra un momento "¡Nuevo match!" y navega al chat recién desbloqueado. Iconos
  nuevos en `NyxIcons.kt` (like/corazón, bloquear, reportar/bandera) siguiendo el
  patrón `ImageVector.Builder` ya usado.

---

## Fase 5 — Identidad visual (superficie de implementación)

- `ui/theme/Color.kt` — nueva semilla (dirección índigo/ciruela/dorado rosado),
  regenerar **todos** los roles M3 (no solo `primary`, tal como ya advierte el propio
  comentario del archivo). `AvatarColors` puede quedarse o ajustarse levemente.
- `ui/theme/Theme.kt` — sin cambio estructural más allá del rename (`KryptaTheme` →
  `NyxTheme`).
- `res/drawable/ic_launcher_background.xml`/`ic_launcher_foreground.xml` — nuevo glifo
  (diseño final: tarea creativa de seguimiento), regenerar `mipmap-*/` con el Image
  Asset tool.
- `res/drawable/ic_stat_krypta.xml` → renombrado + nuevo glifo monocromo de
  notificación (silueta, regla de Android); actualizar referencias en
  `NyxNotifications.kt`.
- `themes.xml`/`values-night/themes.xml`/`colors.xml` (`krypta_window_background*`) —
  renombrados y repintados.
- `strings.xml` `app_name` → "Nyx" (es la **única** entrada del archivo; todo el resto del
  copy está hardcodeado en Compose). El sed de la Fase 1 ya sustituye la marca en esos
  literales — verificado que hay ~20 en `HelpContent.kt`, `SettingsScreen.kt`,
  `ChatScreens.kt`, `LockScreen.kt`, `ConversationsScreen.kt`, `ScreenSecurity.kt`,
  `KryptaNotifications.kt` — así que el grep posterior es de comprobación, no de trabajo
  manual. La extracción completa de strings a recursos sigue siendo un esfuerzo aparte,
  fuera de este plan.
- **`ui/HelpContent.kt` no es un rename, es una reescritura** (*añadido 14 ago*): el FAQ
  está escrito para un mensajero ("¿pide teléfono o correo?", "¿qué es un PeerID?",
  "¿puedo capturar pantalla?") y le faltan por completo las preguntas de una app de citas:
  qué es el tablón y **qué ve de mí el nodo mientras mi tarjeta está publicada**, cómo
  funciona el like mutuo, cómo bloqueo y denuncio, qué pasa con mi avatar, por qué se pide
  ser mayor de 18. Es además donde la divulgación de privacidad tiene que aparecer en
  lenguaje llano, no solo en el HTML de la política. `HelpContentTest` verifica cobertura
  por categoría, así que las categorías nuevas hay que añadirlas también ahí.
- No existen assets de ilustración hoy; las pantallas nuevas de descubrimiento/match
  van a querer al menos estado vacío y celebración de match — tarea creativa de
  seguimiento, no bloqueante para el resto del plan.

---

## Fase 6 — Documentación

- **`CLAUDE.md`**: pase completo — intro reescrita para la naturaleza dual
  mensajero+descubrimiento de Nyx, rutas de "Module structure", IDs de protocolo en
  "Native Go bridge", convenciones específicas de Krypta.
- **`docs/architecture.md`**: pase de renombrado + secciones nuevas: arquitectura del
  tablón, envelope Like + máquina de estados de match, diseño de bloqueo/reporte.
- **Documentos que el plan original no listaba** (*añadido 14 ago*; `docs/` contiene
  exactamente: `MANUAL.md`, `PLAN-senalizacion-descentralizada.md`, `PLAY-STORE.md`,
  `PRUEBAS-PENDIENTES.md`, `architecture.md`, `politica-privacidad.html`):
  - **`docs/MANUAL.md`** — es el manual de usuario y la comparativa con WhatsApp/Signal.
    Para un producto de citas está mal encuadrado de principio a fin; necesita pase de
    marca **y** una sección nueva de descubrimiento/likes/bloqueo, o queda como
    documentación engañosa. No es opcional: es lo que un revisor de Play puede leer.
  - **`docs/PRUEBAS-PENDIENTES.md`** — el rebrand invalida su contenido (rutas, nombres,
    nodos). Vaciarlo y re-poblarlo con las pruebas en vivo de Nyx, o queda apuntando a
    infraestructura que ya no existe.
  - **`docs/PLAN-senalizacion-descentralizada.md`** — es el documento de diseño del
    transporte, **compartido y vigente en los dos proyectos**. No es historia: si Krypta
    evoluciona su señalización, este documento cambia allí y hay que traérselo (ver
    "Relación con Krypta a largo plazo"). Aquí se mantiene con una nota de cabecera
    ("escrito para Krypta, vigente para Nyx salvo los IDs de protocolo"), no se reescribe.
  - **URL pública de la política de privacidad**: la ficha de Play exige una URL accesible.
    La de Krypta no sirve para Nyx (habla de otro producto y de otra empresa de facto) —
    hace falta publicar la de Nyx en su propio hostname antes de crear la ficha.
- **`docs/politica-privacidad.html`**: pase de renombrado + **divulgación nueva** cerca
  de §3/§4 explicando que el tablón de descubrimiento es la **primera** función donde
  el operador del relay y otros usuarios **sí** ven texto plano (apodo/franja de
  edad/intereses/bio) durante su TTL — esto debe decirse de forma explícita, no
  mezclarse con el framing actual de "no vemos nada"; reconciliar §10 ("no dirigida a
  menores de 16") con el nuevo requisito de autodeclaración 18+ (corregir la
  inconsistencia, no solo añadir encima); §9 gana la descripción del mecanismo de
  bloqueo/reporte.
- **`docs/PLAY-STORE.md`**: pasa a ser **el documento de lanzamiento, sin alternativa**
  (decisión 8: canal único). El bloqueo pasa de "deseable" a bloqueante y el reporte con
  canal al operador también (política UGC de Play, más estricta para una app de citas);
  declarar el gate de edad autodeclarado como limitación conocida para el cuestionario de
  clasificación; describir qué puede y qué no puede moderar el operador (tablón sí,
  conversaciones E2EE no) antes de que lo pregunte la revisión; y señalar el diseño de
  Nyx (sin identidad real, perfil pseudónimo opcional, opt-in mutuo antes de chat) como
  fortaleza de cumplimiento a destacar en la ficha.
- **`docs/MONETIZACION.md`** (nuevo, solo diseño): (1) Freemium Humanizado — enumerar
  qué es siempre gratis (chat, E2EE, bloquear/reportar, descubrimiento básico, enviar
  likes) vs. posible capa de pago (ver quién te dio like, categorías/visibilidad
  extra, límites de archivo/nota de voz más altos, temas) con no-negociables
  explícitos: sin ads, sin pagar por seguridad, sin mecánicas de racha/escasez
  manipuladoras. (2) Micro-Aportes — las dos opciones reales sin elegir a ciegas: (i)
  Play Billing para un nivel de soporte/premium a nivel app, desacoplado de la
  identidad P2P — limpio y cumple; (ii) el campo `tipAddress` opcional en la tarjeta
  (ya especificado en Fase 2) como propina P2P voluntaria fuera de la app, con nota
  explícita de que su seguridad frente a política de Play depende de que la app se
  mantenga pasiva (nunca facilita ni cobra comisión, solo muestra un string
  autodeclarado, funcionalmente como un alias de Venmo en una bio) — revisar si la
  interpretación de política de Google cambia.

---

## Archivos críticos

- `native-bridge/libp2p/bridge.go` — fuente de verdad de IDs de protocolo; todo cambio
  de interoperabilidad Kotlin↔Go (rename, tablón, Likes) nace aquí.
- `infra/nyx-node/mailbox.go` (hoy `infra/node/mailbox.go`) — el patrón exacto de
  cuota/TTL/auth que `board.go` debe calcar, y el archivo donde vive la cuota por
  destinatario que los Likes **no** deben consumir.
- `p2p-signaling/.../IdentityBackup.kt` — magic `KRBK1` usado como AAD: el sed no lo ve.
- `.gitignore` — sus rutas `/infra/node/…` dejan de ser válidas al renombrar el módulo.
- `p2p-signaling/.../ChatService.kt` (líneas 602-654 confirmadas) — hub de
  orquestación; punto de inserción del guard de bloqueo y modelo para `LikeService`.
- `p2p-signaling/.../MessageEnvelope.kt` (prefijos `T/R/I/F/K/D/C` confirmados) — punto
  de extensión para el nuevo tipo `L`.
- `data/.../Migrations.kt` (última: `MIGRATION_3_4`, esquema v4 confirmado) y
  `data/.../di/DataModule.kt` — donde se conecta `MIGRATION_4_5` y las entidades
  nuevas.
- `app/build.gradle.kts` y `settings.gradle.kts` — ancla de identidad/versión de Gradle
  para todo el rebrand.

## Verificación

- Fase 1: cero `krypta` en código y configuración ejecutable (ver paso 9 corregido, no
  en todo el árbol); `./gradlew clean :app:assembleDebug` limpio;
  `./gradlew testDebugUnitTest` verde (ajustar los tests que citaban strings de
  dominio `krypta-*` y el magic `KRBK1`); smoke test contra nodo local; y, tras 1.13,
  prueba en vivo de emparejamiento/mensaje entre dos dispositivos contra el nuevo nodo
  `nyx-node` desplegado.
- Fase 2: build limpio con el esquema v5; test de migración `MIGRATION_4_5` (patrón ya
  usado para `MIGRATION_2_3`/`MIGRATION_3_4`).
- Fase 3: test Go `TestBoardPublishQuery`/similar en `infra/nyx-node` (mismo patrón que
  `TestMailboxStoreAndForward`); `ChatServiceTest`/nuevo `LikeServiceTest` cubriendo
  like unidireccional (no desbloquea), like mutuo (dispara match), rate-limit por
  peerId no-contacto.
- Fase 4: prueba en vivo del flujo completo en dos dispositivos — publicar tarjeta,
  descubrir, like unidireccional (sin chat), like mutuo (match + chat se abre),
  bloquear (deja de recibir likes/mensajes de ese peer), gate de edad al primer
  inicio.
- Fase 6: revisión de que `CLAUDE.md`/`docs/architecture.md` no contengan referencias
  residuales a "Krypta" ni a rutas de paquete viejas.

---

## Documento de tareas punto por punto (checklist de seguimiento)

Nota: este checklist es la fuente de verdad de seguimiento hasta que la Tarea 6.0 lo
transcriba a un documento aparte (`docs/NYX-TAREAS.md`) al estilo de
`docs/PRUEBAS-PENDIENTES.md`/`docs/PLAY-STORE.md`. **Cada tarea marcada como hecha debe
cerrar con su entrada correspondiente en `CLAUDE.md`** (narrativa, técnica, fechada,
con cómo se verificó — el mismo estilo que ya tiene Krypta hoy), no dejar la
documentación para el final.

### 0. Decisiones previas — **cerrada el 14 ago 2026**
Resultado completo en [docs/NYX-POLITICA-CONTENIDO.md](NYX-POLITICA-CONTENIDO.md).
- [x] 0.1 App de **citas 18+ con mensajería E2EE**, no app de contenido adulto. Tablón
      limpio y moderado; conversación privada sin censura (excepción de contenido sexual
      incidental). Ficha sin léxico adulto.
- [x] 0.2 Denuncia **con canal cifrado al operador** + expulsión del tablón. Descartada la
      variante solo-local.
- [x] 0.3 Canal único **Google Play**; mercado inicial **Latinoamérica** (deja fuera las
      leyes estadounidenses de verificación de edad; el gate 18+ autodeclarado basta).
- [ ] 0.4 **Términos de uso + Normas de comunidad** públicos, con prohibición explícita de
      CSAE y definición de contenido objetable (esqueleto en la sección 6 del documento).
      Requisito simultáneo de UGC y de Child Safety Standards.
- [ ] 0.5 **Punto de contacto de seguridad infantil** (correo real y atendido) y
      **procedimiento escrito de actuación**: plazos, quién decide, cómo se expulsa un
      PeerID, cómo se reporta a NCMEC. Sin esto la autocertificación de Console es falsa.
- [ ] 0.6 **Formulario Child Safety Standards** en Play Console (trámite aparte).

### 1. Rebrand mecánico
- [~] 1.0 **Primero de todo**: hecha la mitad protectora — `origin` (que apuntaba a
      `dasilvabalautaro/Krypta`, en producción) se renombró a `upstream` con
      `--push no_push`, así que **no hay `origin` y un push a Krypta falla al resolver
      la URL**. Falta lo que depende del autor: crear el repo de Nyx y
      `git remote add origin <url>`.
- [x] 1.1 Rama nueva, `git status` limpio.
- [x] 1.2 `git mv` de los 5 subárboles de paquete (`app` main/test/androidTest, `core`,
      `data`, `native-bridge`, `p2p-signaling`) de `chat/neto/krypta` a `chat/neto/nyx`.
- [x] 1.3 Sustitución `Krypta`→`Nyx` / `krypta`→`nyx` sobre `.kt`/`.xml` en esos `src/`,
      **excluyendo** el bloque de imports de `chat.neto.krypta.bridge.*` en
      `Libp2pNode.kt` (se toca en 1.6). **Corregido al ejecutarlo**: la previsión de que
      `DEFAULT_BOOTSTRAP` quedaría con hostnames nuevos y PeerID viejos se quedó corta —
      su primera línea era `/ip4/216.128.169.83/...`, la **IP literal del VPS de Krypta**,
      que ninguna sustitución de marca toca, así que habría sobrevivido intacta y los
      móviles de Nyx se habrían conectado a producción de Krypta. Se dejó la constante
      **vacía** (= solo LAN, comportamiento ya soportado por `savedBootstrap`) hasta 1.13.
- [x] 1.4 `settings.gradle.kts` → `rootProject.name = "Nyx"`.
- [x] 1.5 `app/build.gradle.kts` → `namespace`/`applicationId` = `chat.neto.nyx`,
      `versionCode = 1`, `versionName = "1.0"` (sin tocar `compileSdk`/`minSdk`/
      `targetSdk`/`ndkVersion`/`libs.versions.toml`); namespaces de `core`/`data`/
      `native-bridge`/`p2p-signaling`.
- [x] 1.6 `native-bridge/libp2p/bridge.go`: renombrar protocol IDs (`msg`, `call`,
      `video`, `mailbox put/get`, `wake`), context tags y los strings de
      `discovery_test.go`; `build-aar.sh:31` → `-javapkg=chat.neto.nyx`, artefacto
      `nyx-p2p.aar`; regenerar AAR; actualizar el import excluido en 1.3 **y**
      `native-bridge/build.gradle.kts:34` (+ sus dos comentarios);
      `proguard-rules.pro:8` → keep rule nueva; smoke-test `nativePing()`.
- [x] 1.7 `git mv infra/node infra/nyx-node` + mismos renombrados de protocol ID en
      `main.go:36`/`mailbox.go:39-40`/`wake.go:27`, verificados carácter a carácter
      contra `bridge.go`; actualizar las rutas `/infra/node/…` del `.gitignore`.
- [x] 1.7b `git rm -r infra/fdroid-repo` — limpieza de herramienta heredada de Krypta, no
      decisión de canal: Nyx se distribuye solo por Play (decisión 8). Sigue viva en el
      repo de Krypta.
- [x] 1.8 Separadores de dominio HKDF en `:p2p-signaling` (`SafetyNumber.DOMAIN`,
      `AesGcmMessageCipher` info, `RendezvousService` info, `CallService.CALL_KEY_SALT`)
      → `nyx-*`; actualizar tests con vectores hardcodeados.
- [x] 1.9 Identificadores de almacenamiento: SharedPreferences (`krypta_settings`,
      `krypta_identity`), Room DB (`krypta.db`), directorio de adjuntos
      (`krypta_files`, + `file_paths.xml`) → `nyx_*`.
- [x] 1.10 Manifest, `CallActionReceiver`, `KryptaNotifications.kt`→
      `NyxNotifications.kt` (canales nuevos en v1), esquema QR, tags de wake-lock/hilo,
      renombrar archivos `Krypta*.kt`→`Nyx*.kt`.
      `DEFAULT_BOOTSTRAP` se deja para el final (depende de 1.12).
- [x] 1.10b Respaldo de identidad: magic `"KRBK1"`→`"NYXB1"` (`IdentityBackup.kt:106`,
      es AAD), extensión `.krbk`→`.nybk`, nombre por defecto, menciones en
      `AndroidManifest.xml:42`/`backup_rules.xml`/`data_extraction_rules.xml`, y
      actualizar `IdentityBackupTest`.
- [~] 1.10c Keystore propio. El comentario del `.gitignore` ya apunta a
      `~/keystores/nyx/nyx.jks`, pero **`keystore.properties` sigue apuntando al keystore
      de Krypta y se ha dejado intacto a propósito**: contiene contraseñas del autor y
      crear el `.jks` nuevo exige elegir una contraseña, así que es acción suya. Mientras
      tanto, un `assembleRelease` desde este árbol **firmaría Nyx con la clave de
      Krypta** — no romperlo, pero acopla los dos productos en Play App Signing. Hacerlo
      antes del primer release; no bloquea nada de la Fase 1.
- [x] 1.11 (cubierto por 1.7 — el rename de `infra/node`, no una copia).
- [ ] 1.12 ⚠️ **Depende de una acción externa del autor: contratar el VPS.** Al llegar
      aquí hay que parar y avisarle; nada de esta tarea se puede adelantar sin la caja.
      Lo anterior (1.0–1.11, 1.15, 1.15b con nodo local) no depende de ella.
      Aprovisionar el **VPS propio de Nyx** (Vultr São Paulo, ≥2 GB — 4 GB si el
      presupuesto lo permite), usuario `nyx`, `/var/lib/nyx`, `nyx-node.service`,
      puertos 4001 TCP/UDP + ws 8081, `nyx.neto.chat` apuntando a su IP. Capturar el
      PeerID real.
- [ ] 1.12b En el aprovisionamiento, no después: `net.core.rmem_max` subido, copia de
      `node.key` fuera de la caja, límites finitos en el relay, y `deploy-vps.sh` con
      host destino explícito (imposible apuntarlo a Krypta por defecto).
- [ ] 1.13 Actualizar `DEFAULT_BOOTSTRAP` en `Libp2pNode.kt`: **una sola línea**, el
      multiaddr directo del nodo propio de 1.12 (sin Cloudflare), manteniendo el formato
      de lista para el segundo nodo futuro.
- [x] 1.14 Verificación: cero `krypta` en `*.kt`/`*.xml`/`*.go`/`*.kts`/`*.pro`/`*.sh`/
      `*.plist`/`*.service`/`*.json` (excluyendo `build/`, `.git/`, `.gradle/`); las
      únicas menciones restantes son las históricas de `CLAUDE.md`/`docs/`.
- [x] 1.15 `./gradlew clean :app:assembleDebug` + `./gradlew testDebugUnitTest` verdes.
- [x] 1.15b Smoke test funcional **contra un nodo local**, hecho en el TECNO el 14 ago:
      nodo `infra/nyx-node` en `tcp/4101` + `adb reverse` + bootstrap escrito en
      `nyx_settings.xml` con `run-as`. Nyx se instaló **junto a** Krypta (applicationId
      distinto, Krypta intacta), generó identidad Ed25519 propia y el estado WAN pasó a
      "conectado". Verificado por falsación además de por observación: al matar el nodo
      el estado cayó a "sin conexión" (~195 s, un ciclo completo de `wanLoop`) y al
      revivirlo volvió a "conectado" — o sea que el estado seguía de verdad a este nodo y
      no era un residuo. Prueba de que el AAR nuevo y los protocol IDs `/nyx/*` casan
      extremo a extremo.
- [ ] 1.16 Prueba en vivo de emparejamiento/mensaje entre dos dispositivos contra el
      nuevo nodo desplegado.
- [x] 1.17 Entrada en `CLAUDE.md`: recuadro de cabecera con qué cambió el rebrand, que
      Krypta sigue viva, por qué `DEFAULT_BOOTSTRAP` está vacío, el estado de los remotos
      y el aviso de que el resto del archivo aún describe Krypta (pase completo en 6.1).

### 2. Modelo de datos
- [ ] 2.1 `MyProfilePrefs.kt` (SharedPreferences): `nickname`, `ageMin`/`ageMax`,
      `interests`, `bio`, `tipAddress`, `avatarBytes`.
- [ ] 2.2 `LikeEntity` (Room): `peerId` PK, `sentAt`, `receivedAt`, `matchedAt`,
      `source` + `LikeDao` + `LikeRepository` (`:core`/`:data`).
- [ ] 2.3 `BlockedPeerEntity` (Room): `peerId` PK, `blockedAt`, `reason` + `BlockedPeerDao`
      + `BlockRepository`.
- [ ] 2.4 `MIGRATION_4_5` (crea ambas tablas), registrar en `DataModule.kt`, bump
      `@Database(version = 5, entities = [...])` en `NyxDatabase.kt`, y **commitear
      `data/schemas/5.json`** (hoy solo existe `4.json`).
- [ ] 2.5 Guard en `addContact`/flujo de match: `require(!blockRepo.isBlocked(peerId))`.
- [ ] 2.6 Test de migración `MIGRATION_4_5` (mismo patrón que `MIGRATION_3_4`).
- [ ] 2.7 Cerrar con entrada en `CLAUDE.md`.

### 3. Tablón + Like (Go/bridge)
- [ ] 3.1 `infra/nyx-node/board.go`: protocolos publish/query/**delete**, almacenamiento
      `boarddir/<categoría>/<peerId>.json` (sobreescribe, no append-only), TTL
      ajustable (`-boardttl`, default 48h), cuota por categoría, **tope de tamaño de
      tarjeta** (`-boardmaxcard`, sugerido 96 KiB).
- [ ] 3.2 Wiring en `main.go` (igual que el buzón).
- [ ] 3.3 Test Go `TestBoardPublishQuery` (mismo patrón que
      `TestMailboxStoreAndForward`).
- [ ] 3.4 `DiscoveryTopic` (Kotlin, `:p2p-signaling`): `HKDF("nyx-discover-v1", categoría)`.
- [ ] 3.5 `bridge.go`: `PublishCard`/`QueryBoard` expuestas a gomobile; regenerar AAR.
- [ ] 3.6 `Libp2pNode.kt`: `publishCard`/`queryBoard` suspend.
- [ ] 3.7 `MessageEnvelope.kt`: nuevo prefijo `L` (`"L\n<ts>"`) + `encodeLike`/decode.
- [ ] 3.8 `LikeService.kt` (`:p2p-signaling`): `sendLike(peerId)`, punto de entrada
      propio para envelopes `L` que intenta descifrar con `keyExchange.sharedSecretWith`
      aunque el peer no sea contacto todavía; persiste en `LikeEntity`; dispara
      transición de match mutuo.
- [ ] 3.9 Rate-limit por PeerID no-contacto en `LikeService`, **antes** de derivar el
      secreto X25519 (que es la parte cara), con caché del secreto por peer.
- [ ] 3.9b Bucket de cuota propio para Likes en el nodo (`/nyx/like/put/1.0.0`,
      sobreescribe por emisor), para que un abusador no pueda llenar la cuota del buzón
      de mensajes de su víctima con likes.
- [ ] 3.10 Tests: like unidireccional no desbloquea, like mutuo dispara match,
      rate-limit efectivo, cuota de likes no consume la del buzón.
- [ ] 3.12 Borrar tarjeta del tablón desde la app (no esperar al TTL).
- [ ] 3.13 `/nyx/report/1.0.0` en el nodo (sobre cifrado a la clave del operador, cuota +
      TTL) + expulsión de un PeerID del tablón. Es trabajo de nodo y sube aquí desde la
      Fase 4 porque, con canal único en Play, el reporte con destino real es requisito de
      publicación, no una mejora.
- [ ] 3.11 Cerrar con entrada en `CLAUDE.md`.

### 3b. Motor de avatar
- [ ] 3b.1 Definir/confirmar criterios de evaluación de la Alternativa A (calidad,
      tamaño de modelo, latencia, filtro de contenido). **Criterio endurecido por la
      Fase 0**: el avatar se publica en el tablón, que es la superficie regulada por
      Child Safety Standards, así que generar un rostro que parezca de menor tiene que
      ser *estructuralmente improbable*, no *estadísticamente raro*.
- [ ] 3b.2 Evaluar el modelo propio (Alternativa A) contra esos criterios.
- [ ] 3b.3 Decisión: A si cumple criterios, B (constructor paramétrico) si no.
- [ ] 3b.4 Implementar la alternativa elegida detrás de una interfaz común
      (`AvatarEngine` o similar) para no acoplar el resto de la app a cuál se usó.
- [ ] 3b.5 Integrar con `ImageCodec` existente para compresión/tope de tamaño.
- [ ] 3b.6 Cerrar con entrada en `CLAUDE.md` explicando qué alternativa se usó y por qué.

### 4. Funcionalidad de app
- [ ] 4.1 Guard de bloqueo temprano en `ChatService.onReceived`, `LikeService`,
      aceptar-llamada de `CallService`.
- [ ] 4.2 `BlockedPeersScreen.kt` (lista + desbloquear).
- [ ] 4.3 Acción "Bloquear" en `ChatScreens.kt` (reusa `ConfirmDeleteDialog`) y en la
      tarjeta de descubrimiento (antes de match).
- [ ] 4.4 Mecanismo de reporte que cumpla la política UGC de Play: bloqueo inmediato +
      evidencia (PeerID, fragmento de conversación, nota libre) **enviada al operador como
      sobre cifrado** (con capacidad de expulsar el PeerID del tablón), más exportación
      local para el usuario. Documentar el alcance real: se modera el tablón, no las
      conversaciones E2EE.
- [ ] 4.4b Denunciar accesible **desde cada pieza de contenido** (tarjeta del tablón,
      cabecera del chat y mensaje individual), no solo desde la tarjeta y el chat —
      requisito literal de la política UGC.
- [ ] 4.5 `AgeGate.kt`: autodeclaración 18+ al primer inicio, cubre toda la app, guard
      de navegación antes de cualquier pantalla (cubre de sobra el requisito de
      age-gating previo a las funciones de emparejamiento).
- [ ] 4.5b Pantalla de **aceptación de los Términos de uso** antes de poder publicar la
      primera tarjeta; no saltable, decisión persistida (patrón `AgeGate`).
- [ ] 4.6 `DiscoveryScreen.kt` + `DiscoveryViewModel.kt` + `BoardService`
      (`:p2p-signaling`).
- [ ] 4.7 `ProfileEditorScreen.kt` (edita `MyProfilePrefs`, incluye el flujo de
      creación de avatar de la Fase 3b, botón "Publicar").
- [ ] 4.8 `DiscoveryCard.kt` (apodo/edad/intereses/bio/avatar, "Me interesa" → `sendLike`,
      overflow → Bloquear/Reportar).
- [ ] 4.9 Flujo de match: `Flow` sobre `LikeRepository`, momento "¡Nuevo match!",
      navegación al chat desbloqueado.
- [ ] 4.10 Iconos nuevos en `NyxIcons.kt` (like/corazón, bloquear, reportar/bandera).
- [ ] 4.11 Prueba en vivo end-to-end: publicar tarjeta con avatar, descubrir, like
      unidireccional (sin chat), like mutuo (match + chat), bloquear, gate de edad.
- [ ] 4.12 Cerrar con entrada en `CLAUDE.md`.

### 5. Identidad visual
- [ ] 5.1 Nueva semilla + regenerar todos los roles M3 en `Color.kt`.
- [ ] 5.2 Rename `KryptaTheme`→`NyxTheme` en `Theme.kt` (si no quedó ya cubierto en 1.3).
- [ ] 5.3 Nuevo glifo de icono de launcher (`ic_launcher_background/foreground.xml`) +
      regenerar `mipmap-*/`.
- [ ] 5.4 Nuevo icono de notificación monocromo + referencias en `NyxNotifications.kt`.
- [ ] 5.5 Repintar `themes.xml`/`values-night/themes.xml`/`colors.xml`.
- [ ] 5.6 `strings.xml` `app_name` → "Nyx"; grep de comprobación de literales `"Krypta"`
      visibles en Compose (el sed de 1.3 ya los cubre).
- [ ] 5.6b Reescribir `ui/HelpContent.kt` para el producto nuevo (tablón y qué ve el
      nodo, like mutuo, bloquear/denunciar, avatar, 18+) y ampliar `HelpContentTest` con
      las categorías nuevas.
- [ ] 5.7 (Opcional, no bloqueante) Assets de estado vacío/celebración de match.
- [ ] 5.8 Cerrar con entrada en `CLAUDE.md`.

### 6. Documentación
- [ ] 6.0 Transcribir este checklist a un documento persistente del repo (p. ej.
      `docs/NYX-TAREAS.md`).
- [ ] 6.1 Pase completo de `CLAUDE.md` (intro, módulos, protocolos, convenciones).
- [ ] 6.2 Pase de `docs/architecture.md` + secciones nuevas (tablón, Like/match,
      bloqueo/reporte, motor de avatar).
- [ ] 6.3 `docs/politica-privacidad.html`: divulgación explícita del tablón (el nodo y
      otros usuarios ven en claro lo que se publica, incluido el avatar, durante el
      TTL), reconciliar §10 menores con el gate 18+, describir bloqueo/reporte.
- [ ] 6.4 `docs/PLAY-STORE.md`: bloqueo y denuncia como bloqueantes, gate de edad como
      limitación declarada, formulario Child Safety Standards, ficha revisada sin léxico
      adulto, y qué puede moderar el operador (tablón sí, conversaciones E2EE no).
- [ ] 6.5 `docs/MONETIZACION.md` (nuevo): Freemium Humanizado + micro-aportes, tal como
      se describe en la Fase 6 del plan. **Revisar contra el requisito UGC** de que la
      monetización no incentive conductas objetables (nada de pagar por visibilidad que
      premie el spam, ni mecánicas de escasez que empujen a insistir).
- [ ] 6.6 `docs/MANUAL.md`: pase de marca + sección de descubrimiento/likes/bloqueo
      (hoy describe un mensajero, no un producto de citas).
- [ ] 6.7 `docs/PRUEBAS-PENDIENTES.md`: vaciar y re-poblar con las pruebas en vivo de Nyx.
- [ ] 6.8 `docs/PLAN-senalizacion-descentralizada.md`: nota de cabecera ("escrito para
      Krypta, vigente para Nyx salvo IDs de protocolo"); es diseño compartido y vigente,
      no historia.
- [ ] 6.8b `docs/SYNC-KRYPTA.md` (nuevo): commit de Krypta / cómo se aplicó en Nyx
      (portado, no aplica, pendiente). Ver "Relación con Krypta a largo plazo".
- [ ] 6.9 Publicar la política de privacidad de Nyx en una URL propia (requisito de la
      ficha de Play).
- [ ] 6.10 Revisión final: en `CLAUDE.md`/docs, las únicas menciones a "Krypta" son las
      deliberadas (proyecto hermano, base común, procedimiento de sync), no rutas ni
      nombres vigentes de este proyecto.
