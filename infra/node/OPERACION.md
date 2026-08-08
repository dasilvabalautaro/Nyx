# Operar el nodo de São Paulo (VPS)

Guía del día a día del nodo primario: dónde vive cada cosa, cómo entrar y qué mirar cuando
algo va mal. El **despliegue** (compilar, instalar, systemd, puertos) está en la sección
"Nodo primario en un VPS Linux" de [README.md](README.md); aquí se da
por hecho que ya está montado.

| | |
|---|---|
| Proveedor / región | Vultr, São Paulo |
| IP | `216.128.169.83` |
| Hostname | `krypta-node-saopaulo` |
| SO | Ubuntu 24.04 LTS |
| PeerID | `12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5` |

Es la **primera línea** de `Libp2pNode.DEFAULT_BOOTSTRAP`, o sea el nodo primario: `MailboxPut`
deposita en el primero vivo. El Mac y el PC Windows quedan de respaldo, y como el bridge retira
y escucha de *todos* los nodos, que este se caiga no corta la entrega — solo la empeora.

## Qué hay en la máquina

Esto es todo; no hay base de datos ni nada más.

| Ruta | Qué es |
|---|---|
| `/usr/local/bin/krypta-node` | El binario Go (~38 MB). Lo reemplaza `deploy-vps.sh` en cada despliegue |
| `/var/lib/krypta/node.key` | **La identidad del nodo** (68 bytes). De aquí sale el PeerID que llevan los móviles |
| `/var/lib/krypta/mailbox/` | Los sobres E2EE en tránsito, un subdirectorio por destinatario |
| `/etc/systemd/system/krypta-node.service` | La unidad que lo mantiene vivo y lo arranca en el boot |

## Cómo entrar

Desde la terminal de la Mac, sin contraseña (la clave SSH ya está puesta):

```bash
ssh root@216.128.169.83
```

Si SSH no responde —por ejemplo, tras equivocarse con `ufw` y cerrarse la puerta—, el panel de
Vultr tiene un botón **"View Console"** que abre una consola por navegador, conectada por debajo
del firewall. Es la red de seguridad: por eso conviene no deshabilitar el acceso por contraseña
de root sin haber probado antes esa consola.

## Comandos de diagnóstico

```bash
# ¿Está vivo?
systemctl status krypta-node

# Los logs. El binario no escribe a ningún fichero: todo va al journal de systemd.
journalctl -u krypta-node -n 50 --no-pager    # últimas 50 líneas
journalctl -u krypta-node -f                  # en vivo, como un tail -f
journalctl -u krypta-node --since "1 hour ago"

# ¿Hay correo pendiente en el buzón?
find /var/lib/krypta/mailbox -type f | wc -l   # nº de sobres sin retirar
ls -la /var/lib/krypta/mailbox/                # un directorio por destinatario

# ¿Quién está conectado al nodo ahora mismo?
ss -tn state established '( sport = :4001 )'

# ¿Está escuchando donde debe? (4001 tcp+udp y 8081 ws local)
ss -tulnp | grep krypta-node

# Reiniciar (systemd lo revive solo si se cae, esto es para forzarlo)
systemctl restart krypta-node
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
ADDR=/ip4/216.128.169.83/tcp/4001/p2p/12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5

MBX_ADDR=$ADDR  go test -run TestMailboxFetchAgainstLiveNode -v ./...      # ¿responde el buzón?
WAKE_ADDR=$ADDR go test -run TestWakeAgainstLiveNode -v ./...              # ¿responde el wake?
MBX_ADDR=$ADDR  go test -run TestMailboxRoundTripAgainstLiveNode -v ./...  # ciclo completo real
PING_ADDR=$ADDR go test -run TestPingAgainstLiveNode -v ./...              # latencia
```

Referencia de latencia medida el 7 ago 2026 desde La Paz: **p50 = 107 ms, p95 = 119 ms**. Si
algún día sale bastante peor, es señal de problema de red o de que el VPS está saturado.

## Actualizar el binario

Desde la Mac, con el repo delante (rutas **desde la raíz del repo**). Es idempotente y **no
toca `node.key`**, así que el PeerID se conserva:

```bash
cd infra/node
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go1.22.12 build -o dist/krypta-node-linux-amd64 .
bash deploy-vps.sh root@216.128.169.83
```

Ojo con una asimetría fácil de olvidar: el Mac y el PC Windows corren **el mismo `main.go`**. Un
cambio de comportamiento del nodo (por ejemplo, ponerle topes al relay) desplegado solo aquí
hace que una llamada se comporte distinto según por qué relay pase, y como no se controla cuál
escoge, sale un fallo intermitente difícil de diagnosticar. O se despliegan los tres, o se
asume la diferencia a propósito.

## Tres cosas que conviene tener claras

**El buzón no se puede leer, y eso es lo correcto.** Si abres un `.json` de
`/var/lib/krypta/mailbox/` verás `id`, `from`, `ts` y un `blob` en base64 que es puro
ciphertext. Ni el dueño del servidor puede descifrarlo: es lo que promete la §3 de la
[política de privacidad](../../docs/politica-privacidad.html), y aquí se puede comprobar a ojo.

**`node.key` es lo único irreemplazable de la máquina.** El binario se recompila, la unidad
systemd está en el repo, el buzón es tránsito. Pero si esa clave se pierde el nodo cambia de
PeerID y los móviles ya instalados dejan de encontrarlo: habría que publicar otra versión de la
app. **Ya está respaldada (8 ago 2026)** en `~/keystores/krypta/krypta-node-saopaulo.key` en la
Mac del autor, verificada (mismo SHA-256 y deriva el PeerID real, no es solo un fichero
copiado). Para restaurar: ponerla en `/var/lib/krypta/node.key` con dueño `krypta:krypta` y
permisos `600` **antes** de arrancar el servicio — si arranca sin ella, se genera una identidad
nueva y el PeerID cambia.

**No edites ficheros con el servicio corriendo.** El proceso tiene su estado en memoria y puede
sobrescribir lo que toques. Si hay que cambiar algo de `/var/lib/krypta/`: `systemctl stop
krypta-node`, el cambio, y `systemctl start krypta-node`.

## Pendientes en esta máquina

- **Topes finitos al relay.** Hoy [main.go](main.go) usa
  `EnableRelayService(relayv2.WithInfiniteLimits())` — necesario porque el tope por defecto
  (128 KiB / 2 min) cortaba las llamadas a los ~20 s, pero regala ancho de banda a cualquier
  nodo libp2p de internet, y ahora con factura de por medio. Al dimensionarlos hay que contar
  con los caudales reales: **43 MB/hora** una llamada de voz relayada, **225 MB/hora** una de
  vídeo. Y recordar que el tope por circuito no es por sí solo protección contra abuso: quien
  quiera abusar abre muchos circuitos, así que lo que acota el gasto son los límites de
  `Resources` (máximo de reservas y circuitos, y reservas por peer/IP).
- **`net.core.rmem_max` bajo.** Al arrancar, quic-go avisa *"failed to sufficiently increase
  receive buffer size (was: 208 kiB, wanted: 7168 kiB, got: 416 kiB)"*. No bloquea nada, pero
  puede limitar el rendimiento de QUIC bajo carga. Se arregla con un sysctl.
