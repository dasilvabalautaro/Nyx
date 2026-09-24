# Operar el nodo de Nyx (VPS)

> ✅ **La máquina existe desde el 14 de agosto de 2026** (tarea 1.12 del
> [plan](../../docs/PLAN-NYX.md)). Es una caja **propia de Nyx**, sin ninguna relación con la
> infraestructura de Krypta: identidad, buzón y clientes son otros.

Guía del día a día del nodo: dónde vive cada cosa, cómo entrar y qué mirar cuando algo va mal.
El **despliegue** (compilar, instalar, systemd, puertos) está en la sección "Nodo primario en
un VPS Linux" de [README.md](README.md); aquí se da por hecho que ya está montado.

### Nodo 1 — primario

| | |
|---|---|
| Proveedor / región | Vultr, São Paulo |
| IP | `216.238.104.36` |
| DNS | `nyx.neto.chat` (registro A, **proxy desactivado**: nube gris) |
| Hostname | `nyx-node-saopaulo` |
| SO | Ubuntu 24.04.4 LTS (1 vCPU, 2 GB, 47 GB disco) |
| PeerID | `12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3` |
| Multiaddr | `/dns4/nyx.neto.chat/tcp/4001/p2p/12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3` |
| Latencia desde La Paz | p50 ≈ 105 ms |
| Respaldo de `node.key` | `~/keys/nyx-node/node.key` |

### Nodo 2 — secundario (desde el 2 sep 2026)

| | |
|---|---|
| Proveedor / región | InterServer, Secaucus (Nueva Jersey, EE. UU.) |
| IP | `162.35.191.18` |
| DNS | `nyx2.neto.chat` (registro A, **proxy desactivado**: nube gris) |
| Hostname | `nyx-node-secaucus` |
| SO | Ubuntu 24.04.4 LTS (1 vCPU, 1,9 GB, 38 GB disco) |
| PeerID | `12D3KooWBCxhFMH5HjSWArXNXkhbWkD2JXkv1U1L4pVGgJWGYBpk` |
| Multiaddr | `/dns4/nyx2.neto.chat/tcp/4001/p2p/12D3KooWBCxhFMH5HjSWArXNXkhbWkD2JXkv1U1L4pVGgJWGYBpk` |
| Latencia desde La Paz | p50 = 135 ms, p95 = 142 ms, 0 pérdidas (n=50) |
| Respaldo de `node.key` | `~/keys/nyx-node/nyx2-secaucus-node.key` |
| SSH | **solo clave pública**; contraseña desactivada el 2 sep 2026 |

Las dos líneas de `Libp2pNode.DEFAULT_BOOTSTRAP`, **en ese orden**. El orden es la política de
reparto, no una preferencia: `MailboxPut`, `LikePut`, `PublishCard` y `SendReport` depositan en
el **primero que acepte**, mientras que `MailboxFetch` drena todos y `StartWake` mantiene un
stream por nodo. São Paulo va primero por estar 30 ms más cerca; Secaucus recoge cuando el otro
no está.

> **Las dos cajas corren el mismo binario desde el 2 sep 2026.** Durante unas horas no fue así
> —el primario venía del 21 ago, sin `report.go`— y eso destapó el fallo que trae de verdad el
> segundo nodo: las denuncias caían en Secaucus mientras `nyx-report` y el aviso de las 6 h
> preguntaban solo a São Paulo, así que habrían dicho **cero sin dar ningún error**. Con una
> caja, el sitio donde se guarda y el sitio donde miras eran el mismo; con dos, se separaron.
> Se cerró redesplegando el primario (mismo `node.key`, mismo PeerID, ~1 s de corte) y haciendo
> que las herramientas trabajen sobre **todas** las cajas.

Los dos respaldos de `node.key` están **verificados de verdad**, no solo copiados: mismo
SHA-256 que el fichero del VPS **y** cada uno deriva el PeerID real de su nodo
(`go run ./cmd/peerid-check <fichero>`). Ojo con no confundirlos al restaurar: darle a una caja
la identidad de la otra deja dos nodos con el mismo PeerID.

## Qué hay en la máquina

Esto es todo; no hay base de datos ni nada más.

| Ruta | Qué es |
|---|---|
| `/usr/local/bin/nyx-node` | El binario Go (~38 MB). Lo reemplaza `deploy-vps.sh` en cada despliegue |
| `/var/lib/nyx/node.key` | **La identidad del nodo** (68 bytes). De aquí sale el PeerID que llevan los móviles |
| `/var/lib/nyx/mailbox/` | Los sobres E2EE en tránsito, un subdirectorio por destinatario |
| `/etc/systemd/system/nyx-node.service` | La unidad que lo mantiene vivo y lo arranca en el boot |

## Cómo entrar

Desde la terminal de la Mac, sin contraseña (la clave SSH ya está puesta):

```bash
ssh root@nyx.neto.chat
```

(Es la **misma clave SSH** que se usa con el nodo de Krypta; el `authorized_keys` de la caja
se aprovisionó con ella.)

Si SSH no responde —por ejemplo, tras equivocarse con `ufw` y cerrarse la puerta—, el panel de
Vultr tiene un botón **"View Console"** que abre una consola por navegador, conectada por debajo
del firewall. Es la red de seguridad: por eso conviene no deshabilitar el acceso por contraseña
de root sin haber probado antes esa consola.

## Comandos de diagnóstico

```bash
# ¿Está vivo?
systemctl status nyx-node

# Los logs. El binario no escribe a ningún fichero: todo va al journal de systemd.
journalctl -u nyx-node -n 50 --no-pager    # últimas 50 líneas
journalctl -u nyx-node -f                  # en vivo, como un tail -f
journalctl -u nyx-node --since "1 hour ago"

# ¿Hay correo pendiente en el buzón?
find /var/lib/nyx/mailbox -type f | wc -l   # nº de sobres sin retirar
ls -la /var/lib/nyx/mailbox/                # un directorio por destinatario

# ¿Quién está conectado al nodo ahora mismo?
ss -tn state established '( sport = :4001 )'

# ¿Está escuchando donde debe? (4001 tcp+udp y 8081 ws local)
ss -tulnp | grep nyx-node

# Reiniciar (systemd lo revive solo si se cae, esto es para forzarlo)
systemctl restart nyx-node
```

Sobre el buzón: que esté **vacío es lo normal**, no señal de avería. Un sobre solo existe entre
que el emisor lo deposita y el destinatario lo retira; el `sweep()` del nodo pasa cada hora y
borra lo caducado (TTL 7 días) y los directorios que quedan vacíos.

Sobre las conexiones: que `ss` no devuelva ninguna fila tampoco es alarmante. Los móviles no
mantienen una conexión permanente por TCP directo — el stream de wake se recicla y los OEM
agresivos (Transsion/TECNO, Xiaomi) suspenden la red con la pantalla apagada. Para comprobar
que un móvil concreto llega al nodo, lo fiable es abrir la app en ese móvil y mirar si aparece
su conexión.

## Comprobar el nodo desde la Mac (sondas)

Sin tocar el servidor. Estas cuatro son las que se usaron para validarlo antes de promoverlo a
primario (rutas **desde la raíz del repo**, no desde este directorio):

```bash
export PATH="/usr/local/bin:$HOME/go/bin:$PATH"
cd native-bridge/libp2p
# Nodo 1 (São Paulo). Para el nodo 2, cambia esta línea por:
#   ADDR=/dns4/nyx2.neto.chat/tcp/4001/p2p/12D3KooWBCxhFMH5HjSWArXNXkhbWkD2JXkv1U1L4pVGgJWGYBpk
ADDR=/dns4/nyx.neto.chat/tcp/4001/p2p/12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3

MBX_ADDR=$ADDR  go test -run TestMailboxFetchAgainstLiveNode -v ./...      # ¿responde el buzón?
WAKE_ADDR=$ADDR go test -run TestWakeAgainstLiveNode -v ./...              # ¿responde el wake?
MBX_ADDR=$ADDR  go test -run TestMailboxRoundTripAgainstLiveNode -v ./...  # ciclo completo real
PING_ADDR=$ADDR go test -run TestPingAgainstLiveNode -v ./...              # latencia
```

El **nodo 2** pasó las cuatro el 2 sep 2026, por IP y por nombre, antes de entrar en
`DEFAULT_BOOTSTRAP`: buzón y wake responden, el ciclo completo verifica carga y remitente, y la
latencia dio `p50 = 135 ms / p95 = 142 ms` (n=50, 0 pérdidas) por IP y `136/140 ms` por nombre.

Las cuatro pasaron el 14 ago 2026 antes de fijar el nodo en `DEFAULT_BOOTSTRAP`, **dos veces**:
primero con `/ip4/216.238.104.36/…` y después con `/dns4/nyx.neto.chat/…`, que es la forma que
llevan los móviles. Latencia desde La Paz: **p50 ≈ 105 ms, p95 ≤ 125 ms** (n=50, 0 pérdidas) —
algo mejor que los 107/119 ms del nodo equivalente de Krypta, misma región y mismo camino TCP
directo. Si en el futuro sale bastante peor, es señal de problema de red o de VPS saturado.

Si una sonda falla, la primera pregunta es **si el problema es el nodo o el nombre**: repite con
el multiaddr `/ip4/…` de la tabla de arriba. Si por IP funciona y por nombre no, el fallo está
en el DNS (registro borrado, o alguien activó la nube naranja: el proxy de Cloudflare solo
entiende HTTP y devolvería sus propias IP, rompiendo el TCP+Noise del 4001).

## Actualizar el binario

Desde la Mac, con el repo delante (rutas **desde la raíz del repo**). Es idempotente y **no
toca `node.key`**, así que el PeerID se conserva:

```bash
cd infra/nyx-node
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go1.22.12 build -o dist/nyx-node-linux-amd64 .
bash deploy-vps.sh root@nyx.neto.chat
```

Mientras Nyx tenga **un solo nodo** no hay asimetría posible entre relays. En cuanto exista el
segundo, cuidado con desplegar un cambio de comportamiento (p. ej. topes al relay) en uno solo:
la llamada se comportaría distinto según por qué relay pase, y no se controla cuál escoge el
cliente — fallo intermitente difícil de diagnosticar. O se despliegan los dos, o se asume la
diferencia a propósito.

## Tres cosas que conviene tener claras

**El buzón no se puede leer, y eso es lo correcto.** Si abres un `.json` de
`/var/lib/nyx/mailbox/` verás `id`, `from`, `ts` y un `blob` en base64 que es puro
ciphertext. Ni el dueño del servidor puede descifrarlo: es lo que promete la §3 de la
[política de privacidad](../../docs/politica-privacidad.html), y aquí se puede comprobar a ojo.

**`node.key` es lo único irreemplazable de la máquina.** El binario se recompila, la unidad
systemd está en el repo, el buzón es tránsito. Pero si esa clave se pierde el nodo cambia de
PeerID y los móviles ya instalados dejan de encontrarlo: habría que publicar otra versión de la
app. **Ya está respaldada (14 ago 2026)** en `~/keys/nyx-node/node.key` (permisos `600`) en la
Mac del autor, verificada de las dos formas: mismo SHA-256 que la de la caja
(`691520f0…6678`) y, deserializándola, deriva el PeerID real — no es solo un fichero copiado.
Conviene además una copia fuera de la Mac: hoy sigue habiendo un único soporte.
Para restaurar: ponerla en `/var/lib/nyx/node.key` con dueño `nyx:nyx` y
permisos `600` **antes** de arrancar el servicio — si arranca sin ella, se genera una identidad
nueva y el PeerID cambia.

**No edites ficheros con el servicio corriendo.** El proceso tiene su estado en memoria y puede
sobrescribir lo que toques. Si hay que cambiar algo de `/var/lib/nyx/`: `systemctl stop
nyx-node`, el cambio, y `systemctl start nyx-node`.

## Estado de la deuda heredada de Krypta

Los tres puntos siguientes se descubrieron sobre la marcha en el nodo de Krypta. Los tres
están ya cerrados en esta caja: dos **al aprovisionar** (14 ago 2026) y el tercero el
16 ago 2026.

- ✅ **Copia del `node.key` fuera de la caja**, verificada por SHA-256 y por derivación del
  PeerID — ver el apartado anterior.
- ✅ **`net.core.rmem_max`**: subido a 7 500 000 (y `wmem_max` igual) en
  `/etc/sysctl.d/99-nyx-quic.conf`, así que sobrevive a reinicios. Comprobado en el arranque
  siguiente: quic-go ya no emite el aviso *"failed to sufficiently increase receive buffer
  size"*. Desde el 16 ago lo pone **`deploy-vps.sh`** solo, para que el segundo nodo no
  dependa de que alguien se acuerde de hacerlo a mano.
- ✅ **Topes finitos al relay (16 ago 2026)** — ver el apartado siguiente.

## Topes del relay

El relay ya **no** corre con `WithInfiniteLimits()`: el código es del 16 ago 2026, pero
**esta caja no lo tuvo hasta el redespliegue del 21 ago** — durante esos cinco días el
repositorio decía una cosa y producción hacía otra. De ahí que el nodo los **imprima al
arrancar** (`journalctl -u nyx-node | grep "Relay v2 topes"`): es la única forma fiable de
saber con qué arrancó una caja, porque mirar `relay.go` solo dice con qué *debería*.

| Tope | Valor | Por qué ese |
|---|---|---|
| Datos por dirección y circuito | 1 GiB | ~4,5 h de vídeo o ~24 h de voz seguidas, con los caudales reales (**225 MB/h** vídeo, **43 MB/h** voz). El default de go-libp2p son 128 KiB: ~20 s de llamada. |
| Vida máxima del circuito | 6 h | Segundo techo, para el circuito ocioso que nadie cierra. Default: 2 min. |
| Reservas simultáneas | 512 | Teléfonos con slot a la vez. Default: 128. |
| Circuitos por teléfono | 8 | Uno por conversación activa sobra. Default: 16. |
| Reservas por IP / por ASN | 32 / 512 | **Muy** por encima del default (8 / 32) a propósito: los usuarios entran por CGNAT móvil, donde una operadora entera comparte unas pocas IPs y **un solo ASN** — con el default, el usuario 33 de Entel se quedaría sin relay. |

Los dos primeros se pueden ajustar sin recompilar, con `-relaydata <MiB>` y `-relayduration
<dur>` en el `ExecStart` de la unidad.

**Lo que esto NO protege**, y conviene no engañarse: son topes *por circuito* y de
*concurrencia*. relayv2 no tiene un límite agregado de tráfico, así que quien quiera abusar
reconecta y sigue. Lo que se acota es el coste de un circuito suelto y cuántos puede haber a
la vez. El respaldo real de la factura es una **alerta de egress en el panel de Vultr** — eso
sigue pendiente de configurar.

## Segundo nodo — HECHO el 2 sep 2026

> Queda como receta para el tercero, o para rehacer el segundo. Lo que se hizo el 2 sep está
> arriba, en la tabla del nodo 2; lo único que falta de la tarea es la **prueba de failover en
> vivo** (paso 6).

Un solo nodo era punto único de fallo del **buzón**, del **wake** y del **relay**: si se caía,
no había entrega offline, ni avisos, ni travesía de NAT. El cliente ya estaba preparado y **no
hizo falta tocar código**: `MailboxPut` hace failover al primer nodo vivo, `MailboxFetch` drena
todos, `StartWake` mantiene un stream por nodo y `StartDHT` da por buena la conexión con ≥1
bootstrap vivo.

Sí cuesta **operación**, y conviene saberlo antes de contratar y no después: al haber dos
cajas, la expulsión del tablón y la revisión de denuncias dejan de ser correctas tal como
están escritas hoy. Está detallado al final de este apartado, en "Lo que cambia el día que
haya dos nodos" — el punto 1 es un agujero, no una molestia.

Lo que falta es la caja, y estos pasos.

### Paso 1 — Contratar la caja

**Qué pedir**, para que sea gemela del primario y el runbook siga valiendo igual:

| | |
|---|---|
| Plan | 1 vCPU / 2 GB RAM / ~50 GB disco (el mismo del primario; sobra de largo) |
| SO | Ubuntu 24.04 LTS |
| Arquitectura | **amd64**, salvo que salga mucho más barata la ARM — entonces compila `dist/nyx-node-linux-arm64` y pasa `arm64` como segundo argumento al script de despliegue |
| IPv4 | pública y **dedicada** (el multiaddr es una IP, no un `Host:` HTTP: nada de IP compartida) |
| Puertos | 4001/tcp, 4001/udp y 443/tcp abiertos en el firewall del proveedor |
| Hostname | `nyx-node-<ciudad>`, como `nyx-node-saopaulo` |

**Dónde**: en un **centro de datos distinto** al de São Paulo. Ese es el punto entero del
segundo nodo — si comparte sala con el primario, cubre la caída del proceso pero no la del
centro de datos, que es el fallo que deja sin buzón, sin wake y sin relay a todo el parque.

Ahora bien, "otra región" no significa "donde sea". El nodo hace de **relay de voz y vídeo**,
y ahí la latencia es la que se nota: los 105 ms p50 desde La Paz del primario salen de estar
en Sudamérica. Un nodo en Fráncfort da redundancia de verdad, pero una llamada que caiga en él
irá peor. Criterio: **mide antes de contratar**, con la página de test de latencia por región
que publican los proveedores (Vultr y los demás tienen una), desde la conexión del autor en La
Paz. Si hay una región sudamericana que no sea São Paulo, es la candidata obvia; si no, se
elige la menos mala y se **asume conscientemente** que el failover degrada las llamadas —
degradado sigue siendo mejor que caído.

**Lo que no se debe hacer:**

- **Máquina doméstica.** Es exactamente lo que dejó a Krypta con puntos únicos de fallo, y
  además esas cajas son de Krypta: un nodo de Nyx alojado ahí ata los dos productos.
- **Reaprovechar el `node.key` del primario.** Dos nodos con el mismo PeerID no son dos nodos.
  El script genera uno nuevo por construcción; no lo copies "para que el multiaddr sea igual".
- **Olvidar que el gasto se duplica.** La alerta de egress de la tarea 1.19 hay que ponerla en
  **las dos** cajas, no solo en la primera.

### Paso 2 — Desplegar

```bash
cd infra/nyx-node
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go1.22.12 build -o dist/nyx-node-linux-amd64 .
bash deploy-vps.sh root@<ip-nueva>
```

Genera **`node.key` propio** en el primer arranque, así que el PeerID es nuevo por
construcción. El script pone también el sysctl de QUIC, abre los puertos si hay `ufw`, imprime
los topes del relay y recuerda al final el comando exacto para respaldar la clave. **Haz ese
respaldo antes de seguir**: el `node.key` *es* el PeerID, y el PeerID viaja compilado en cada
APK instalado.

### Paso 3 — El registro DNS en Cloudflare

En el panel de Cloudflare, zona **`neto.chat`** → *DNS* → *Records* → *Add record*:

| Campo | Valor |
|---|---|
| Type | `A` |
| Name | `nyx2` (queda `nyx2.neto.chat`) |
| IPv4 address | la IP del VPS nuevo |
| Proxy status | **DNS only — nube GRIS** |
| TTL | Auto |

Si el VPS trae IPv6 y lo vas a anunciar, el `AAAA` va **igual de gris**.

La nube gris no es una preferencia: el proxy de Cloudflare **solo entiende HTTP**. Si se queda
naranja, `nyx2.neto.chat` resuelve a las IP de Cloudflare y el 4001 deja de hablar TCP+Noise —
el nodo queda inalcanzable por nombre aunque la caja esté perfecta.

**Comprobación de que quedó bien**, antes de tocar nada más:

```bash
dig +short nyx2.neto.chat
```

Tiene que devolver **la IP del VPS**. Si devuelve algo tipo `104.21.x.x` o `172.67.x.x`, eso es
Cloudflare: el registro quedó en naranja. Es el mismo síntoma que describe el apartado de
sondas — funciona por IP y falla por nombre.

### Paso 4 — Validar antes de fijarlo

Las mismas cuatro sondas del apartado anterior (`TestMailboxFetchAgainstLiveNode`,
`TestMailboxRoundTripAgainstLiveNode`, `TestWakeAgainstLiveNode`, `TestPingAgainstLiveNode`),
por IP **y** por nombre, igual que se hizo con el primario. Anota la latencia: es el número que
dice cuánto degrada una llamada que caiga en este nodo.

### Paso 5 — Fijarlo en la app

Añadir la **segunda línea** a `Libp2pNode.DEFAULT_BOOTSTRAP` (el campo ya es una lista separada
por saltos de línea). **El orden importa**: `MailboxPut`, `LikePut`, `PublishCard` y
`SendReport` depositan en el **primero que acepte**, así que la línea 1 es el nodo que recibe
en condiciones normales y la 2 el que recoge cuando el otro no está.

### Paso 6 — Cerrar con el failover en vivo

Matar el primario y comprobar que un mensaje sigue llegando por el buzón del segundo (pasos en
[docs/PRUEBAS-PENDIENTES.md](../../docs/PRUEBAS-PENDIENTES.md)).

### Lo que cambia el día que haya dos nodos

Esto **no** es "una caja y ya": tres rutinas de operación dejan de ser correctas tal como están
escritas, y las tres fallan en silencio.

1. **La expulsión del tablón hay que aplicarla en las DOS cajas, o no sirve de nada.**
   `banned.txt` es un fichero **por máquina** (`/var/lib/nyx/banned.txt`). Y cuando el nodo
   rechaza a un expulsado responde con un error, que el cliente trata como "ese nodo no me
   acepta" y **pasa al siguiente de la lista** (`PublishCard` recorre los nodos hasta que uno
   acepta). O sea: con la expulsión solo en el nodo A, el expulsado publica en el B **con el
   cliente de serie, sin hacer nada especial**. Y como `QueryBoard` drena todos los nodos y
   fusiona, esa tarjeta la ve todo el mundo. El filtro *al leer* tampoco salva: cada caja
   filtra con su propia lista.
2. **Las denuncias caen en cualquiera de las dos.** `SendReport` también entrega al primero que
   acepte, así que la rutina de [MODERACION.md](MODERACION.md) tiene que mirar **las dos**
   cajas; con mirar solo la primaria, una denuncia puede quedarse sin leer para siempre.
3. **Un cambio de comportamiento se despliega en las dos a la vez.** Ya está dicho arriba a
   propósito de los topes del relay: con topes distintos, la llamada se comporta distinto según
   por qué relay pase, y no se controla cuál escoge el cliente. Fallo intermitente, difícil de
   diagnosticar.

Los puntos 1 y 2 son deuda de **operación**, no de código: hoy se resuelven repitiendo el
mismo `ssh` en las dos cajas. Merece la pena, cuando llegue el segundo nodo, dejar un
`nyx-ban <PeerID>` que escriba en ambas de una vez — el fallo que hay que evitar es el humano,
no el técnico.

Ojo con un detalle que ya mordió en Krypta: un teléfono que **alguna vez** guardó una
preferencia de bootstrap se queda con ella y **no** hereda el nuevo default. Para probar el
segundo nodo en un móvil ya usado hay que borrar esa preferencia (o escribirla a mano en
Ajustes), no basta con instalar la build nueva.

## Denuncias y expulsión del tablón

La rutina de revisión —cómo enterarte, leer, decidir y dejar constancia— vive en su propio
manual: **[MODERACION.md](MODERACION.md)**. Aquí queda solo lo que es operación de la caja.

Las denuncias llegan por `/nyx/report/1.0.0` y se guardan en
`/var/lib/nyx/reports/<peerid-denunciante>/`, un JSON por denuncia, **cifradas a la clave del
operador**: ni el nodo ni quien entre en la caja pueden leerlas. Topes: sobre ≤64 KiB, ≤50 vivas
por denunciante (cuota **por denunciante**, así que agotarla no silencia a los demás), TTL 180
días. Lo que el nodo **sí** ve es quién entregó cada una — identidad del stream de libp2p, no
ocultable, y guardada a propósito porque sin eso no hay freno a las denuncias falsas.

Los expulsados son `/var/lib/nyx/banned.txt`, un PeerID por línea, con `#` para comentar. El nodo
lo **relee al cambiar el mtime**, sin reiniciar. Se edita con `nyx-report ban/unban` (ver el
manual) o a mano por SSH. La expulsión actúa en tres sitios, y el segundo es el que importa: al
publicar, **al consultar** y en el barrido — sin el de consulta, expulsar no retiraría la tarjeta
ya puesta hasta que caducara sola a las 48 h.

**No hay protocolo de recogida ni de administración** a propósito: los dos exigirían autenticar
al operador —otra identidad, otro secreto, otra superficie que puede fallar abierta— para
resolver algo que SSH ya resuelve.

### La clave del operador

Generada el **23 ago 2026** con `go run ./cmd/nyx-report keygen`. Privada en
`~/keys/nyx-operator/operator.key` (permisos `600`) en la máquina del autor; pública
—`b6de2a9b7cb0e6afd88d8912be5662e25cd3a22ef967d464cdbaf8c8bdd77b5d`— **compilada en el APK**.

**Nunca se copia al VPS.** Es de la familia de `node.key` y con una consecuencia peor: si se
pierde, todas las denuncias quedan ilegibles para siempre *y* cambiarla exige **publicar una
versión nueva en Play**, porque la pública viaja dentro de cada APK instalado. `keygen` se niega
a sobreescribir para que eso no pase por repetir un comando.

**Respaldada fuera de la máquina el 23 ago 2026**, igual que se hizo con `node.key`.
