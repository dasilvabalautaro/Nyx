# Operar el nodo de Nyx (VPS)

> ✅ **La máquina existe desde el 14 de agosto de 2026** (tarea 1.12 del
> [plan](../../docs/PLAN-NYX.md)). Es una caja **propia de Nyx**, sin ninguna relación con la
> infraestructura de Krypta: identidad, buzón y clientes son otros.

Guía del día a día del nodo: dónde vive cada cosa, cómo entrar y qué mirar cuando algo va mal.
El **despliegue** (compilar, instalar, systemd, puertos) está en la sección "Nodo primario en
un VPS Linux" de [README.md](README.md); aquí se da por hecho que ya está montado.

| | |
|---|---|
| Proveedor / región | Vultr, São Paulo |
| IP | `216.238.104.36` |
| DNS | `nyx.neto.chat` (registro A, **proxy desactivado**: nube gris) |
| Hostname | `nyx-node-saopaulo` |
| SO | Ubuntu 24.04.4 LTS (1 vCPU, 2 GB, 47 GB disco) |
| PeerID | `12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3` |
| Multiaddr | `/dns4/nyx.neto.chat/tcp/4001/p2p/12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3` |

Es la **única línea** de `Libp2pNode.DEFAULT_BOOTSTRAP`. A diferencia de Krypta, que acabó con
tres nodos, Nyx arranca con uno solo: eso significa que **es punto único de fallo** del buzón,
del wake y del relay. El cliente ya soporta lista de nodos con failover, así que añadir el
segundo no cuesta código — cuesta una caja, y hace falta antes de abrir a público.

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
ADDR=/dns4/nyx.neto.chat/tcp/4001/p2p/12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3

MBX_ADDR=$ADDR  go test -run TestMailboxFetchAgainstLiveNode -v ./...      # ¿responde el buzón?
WAKE_ADDR=$ADDR go test -run TestWakeAgainstLiveNode -v ./...              # ¿responde el wake?
MBX_ADDR=$ADDR  go test -run TestMailboxRoundTripAgainstLiveNode -v ./...  # ciclo completo real
PING_ADDR=$ADDR go test -run TestPingAgainstLiveNode -v ./...              # latencia
```

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

## Segundo nodo (pendiente: falta la caja)

Un solo nodo es punto único de fallo del **buzón**, del **wake** y del **relay**: si se cae,
no hay entrega offline, no hay avisos y no hay travesía de NAT. Es bloqueante para abrir a
público (no para desarrollar). El cliente ya está preparado y **no hace falta tocar código**:
`MailboxPut` hace failover al primer nodo vivo, `MailboxFetch` drena todos, `StartWake`
mantiene un stream por nodo y `StartDHT` da por buena la conexión con ≥1 bootstrap vivo.

Lo que falta es la caja, y estos pasos:

1. **Contratar un segundo VPS en otra región/proveedor** (no una máquina doméstica: las de
   Krypta están documentadas como puntos únicos de fallo, y además son de Krypta). Otra
   región es lo que hace que el segundo nodo cubra una caída del centro de datos, no solo
   del proceso.
2. `bash infra/nyx-node/deploy-vps.sh usuario@<ip-nueva>`. Genera **`node.key` propio** en el
   primer arranque, así que el PeerID es nuevo por construcción — no hay riesgo de clonar la
   identidad del primario. El script pone también el sysctl de QUIC y recuerda al final el
   comando exacto para respaldar el `node.key`.
3. **Registro DNS propio** (p. ej. `nyx2.neto.chat` → A a la IP nueva), **nube gris**, por el
   mismo motivo que el primario: el proxy de Cloudflare solo entiende HTTP y rompería
   TCP+Noise en el 4001.
4. **Validar antes de fijarlo**, con las mismas cuatro sondas del apartado anterior
   (`TestMailboxFetchAgainstLiveNode`, `TestMailboxRoundTripAgainstLiveNode`,
   `TestWakeAgainstLiveNode`, `TestPingAgainstLiveNode`), por IP **y** por nombre.
5. Añadir la **segunda línea** a `Libp2pNode.DEFAULT_BOOTSTRAP` (el campo ya es una lista
   separada por saltos de línea; el orden importa: `MailboxPut` deposita en el primero vivo).
6. Cerrar con la prueba de failover en vivo: matar el primario y comprobar que un mensaje
   sigue llegando por el buzón del segundo (está anotada en
   [docs/PRUEBAS-PENDIENTES.md](../../docs/PRUEBAS-PENDIENTES.md)).

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

Respaldo pendiente fuera de la máquina, igual que se hizo con `node.key`.
