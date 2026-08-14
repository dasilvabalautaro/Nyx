      # Nyx infra node — despliegue (sin Docker)

Nodo **bootstrap + DHT server + Circuit Relay v2 + buzón store-and-forward + wake** de
Nyx (semilla de Fase 1). No lee mensajes (todo E2EE): es punto de encuentro, relé y
buzón de blobs opacos. Los móviles se le conectan para descubrirse por WAN cuando no están
en la misma LAN, para depositar/retirar mensajes cuando el destinatario está offline, y
mantienen un stream ligero de **wake** por el que el nodo avisa al instante cuando llega
un depósito (sin polling; la app recibe con la UI cerrada gracias a su Foreground Service).

> Este README cubre el **despliegue**. Para el día a día del nodo — dónde vive cada fichero,
> cómo entrar, qué mirar cuando algo va mal y cómo actualizarlo — ver
> [OPERACION.md](OPERACION.md).

> ⚠️ **Qué de este documento aplica a Nyx.** Viene del README de Krypta y conserva secciones
> que describen **la topología de Krypta**, no la de Nyx. Para Nyx solo aplica la sección
> **"Nodo primario en un VPS Linux"**: caja propia, IP pública, TCP+QUIC directo, sin
> Cloudflare Tunnel (decisión 2 del [plan](../../docs/PLAN-NYX.md)).
>
> Las secciones de **macOS Catalina**, **Cloudflare Tunnel / `wss`** y **segundo nodo en
> Windows** se conservan como referencia técnica —el mecanismo es correcto y puede hacer falta
> el día que Nyx monte su segundo nodo— pero **no se siguen tal cual**: describen desplegar en
> el Mac y el PC del autor, que son las máquinas donde corre Krypta. Montar un nodo de Nyx ahí
> viola el aislamiento acordado. El segundo nodo de Nyx será otra caja propia.

> **Clave (relay tras Cloudflare):** el nodo se compila con `libp2p.ForceReachabilityPublic()`.
> Tras Cloudflare Tunnel no tiene IP pública directa, así que AutoNAT lo creería "privado" y el
> servicio de relay v2 **no ofrecería el protocolo `hop`** → los móviles no podrían reservar slot
> y la mensajería tras NAT fallaría (`protocols not supported: [/libp2p/circuit/relay/0.2.0/hop]`).
> Forzar reachability=public mantiene el `hop` siempre activo. Si redespliegas, **conserva el
> mismo `node.key`** (el PeerID va cableado en la app como bootstrap por defecto).

## Buzón E2EE store-and-forward (entrega offline)

Si el destinatario no está conectado, el móvil emisor **deposita el ciphertext** en este
nodo (`/nyx/mbx/put/1.0.0`) y el receptor lo **retira al conectarse**
(`/nyx/mbx/get/1.0.0`, con ack — solo se borra lo confirmado). Propiedades:

- **E2EE intacto**: el nodo guarda blobs opacos (base64 de AES-256-GCM); no puede descifrar.
- **Autenticación gratis por libp2p**: el GET solo entrega los sobres dirigidos al PeerID
  del stream, y el remitente del sobre lo fija el nodo desde el stream (no suplantable).
- **Anti-abuso**: blob ≤ 64 KiB; por destinatario ≤ 200 mensajes y ≤ 5 MiB; **TTL 7 días**
  (barrido horario).
- **Almacenamiento**: archivos JSON en `-mailboxdir` (default: `<dir del node.key>/mailbox`,
  o sea `~/nyx/mailbox` con el plist estándar — no hay que tocar nada al desplegar).

## Wake integrado (aviso instantáneo de buzón)

El móvil mantiene abierto un stream `/nyx/wake/1.0.0`; cuando alguien deposita en su
buzón, el nodo le escribe `{"wake":true}` y el móvil retira al segundo. El nodo envía
`{"ping":true}` cada 50 s como keepalive (Cloudflare Free corta la wss a ~100 s de idle);
el móvil reconecta solo cuando Cloudflare recicla la conexión (~10 min) y retira el buzón
en cada reconexión, así ningún aviso se pierde. El aviso no lleva payload ni remitente, y
nadie puede suscribirse al wake de otro (el registro es el PeerID autenticado del stream).
No hay servidor de push separado: al vivir el buzón en este nodo, el aviso sale de aquí.

## Binario para macOS Catalina (10.15)

go-libp2p exige Go ≥ 1.25, cuyos binarios piden macOS ≥ 11. Por eso el nodo se compila con
**go-libp2p v0.38 + Go 1.22** (ver `go.mod`), produciendo un binario con `minos 10.13` que
**sí corre en Catalina**. Interopera con los móviles (go-libp2p v0.48): los protocolos
libp2p (Kademlia, Noise, Relay v2) son compatibles entre versiones.

`dist/` **no lleva binario de Catalina**: los que había eran los de Krypta y se borraron el
14 ago 2026 (protocol IDs `/krypta/*`, otro producto). Nyx no usa hoy ningún nodo doméstico —
si hiciera falta uno, se compila:

Compilar (en una Mac con Go 1.22 instalado vía `go install golang.org/dl/go1.22.12@latest && go1.22.12 download`):

```bash
cd infra/nyx-node
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 \
  go1.22.12 build -o dist/nyx-node-catalina .
# Verifica que pide macOS viejo:  otool -l dist/nyx-node-catalina | grep minos   → 10.13
```

> `GOTOOLCHAIN=local` es obligatorio: sin él, Go se auto-actualiza a una versión nueva y el
> binario vuelve a pedir macOS ≥ 11.

## Pasos en la Mac Catalina

1. **Copia** `nyx-node-catalina` a la Mac, p. ej. en `~/nyx/`:
   ```bash
   mkdir -p ~/nyx && cp nyx-node-catalina ~/nyx/ && chmod +x ~/nyx/nyx-node-catalina
   ```
2. **Arráncalo** en un puerto fijo (4001). La primera vez crea `node.key` (identidad estable):
   ```bash
   cd ~/nyx
   ./nyx-node-catalina -listen /ip4/0.0.0.0/tcp/4001 -key ~/nyx/node.key
   ```
   Anota el **PeerID** que imprime (`12D3KooW…`). No cambia entre reinicios.
3. **Permiso de firewall**: Preferencias → Seguridad y privacidad → Firewall → permitir
   conexiones entrantes para `nyx-node-catalina` (o desactívalo si la Mac está en una red
   de confianza).
4. **Puerto accesible desde internet**:
   - Si la Mac tiene IP pública directa: basta con el firewall de macOS.
   - Si está tras un router: reenvía el **puerto 4001 (TCP y UDP)** a la IP LAN de la Mac
     (NAT/port-forwarding en el router). UDP es para QUIC.
5. **Tu multiaddr de bootstrap** (lo que pondrás en la app, en "Nodo WAN"):
   ```
   /ip4/<TU_IP_PUBLICA>/tcp/4001/p2p/<PeerID>
   ```
   (Averigua la IP pública con `curl ifconfig.me`.)

   > El path `/ip4/.../tcp/4001` solo sirve con **IP pública directa** (o LAN). Si te expones
   > con **Cloudflare** (sin IP pública), usa la sección de abajo (WebSocket/`wss`).

## Detrás de Cloudflare Tunnel (sin IP pública) — vía `wss`

Cloudflare **no** transporta TCP crudo/Noise ni UDP/QUIC; solo HTTP/HTTPS y **WebSocket sobre
443**. El nodo ya escucha en WebSocket (`/ip4/0.0.0.0/tcp/8081/ws`, puerto configurable con
`-wsport`). El handshake Noise de libp2p viaja **dentro** del WebSocket → Cloudflare no puede
leer (E2EE) ni suplantar al nodo. Los móviles no necesitan recompilarse (su libp2p ya trae el
transporte WebSocket).

1. **Arranca el nodo** (escucha ws en 8081 además de tcp/quic):
   ```bash
   ./nyx-node-catalina -key ~/nyx/node.key -wsport 8081
   ```
2. **Añade un Public Hostname al túnel apuntando DIRECTO al nodo** (sin pasar por Nginx).

   **Túnel gestionado por dashboard (token).** Si arrancas cloudflared con
   `tunnel run --token …` (vía `launchd`/plist), **no hay `config.yml`**: las rutas se
   configuran en el panel. En **Cloudflare → Zero Trust → Networks → Tunnels → (tu túnel) →
   Public Hostname → Add a public hostname**:
   - Subdomain `nyx` · Domain `neto.chat`
   - Service: **HTTP** → `localhost:8081`

   Eso es todo: cloudflared hace el upgrade de WebSocket automáticamente, **no tocas Nginx**
   ni el `.plist`, y no interfiere con tus otros sitios (que siguen yendo a Nginx en :80).

   <details><summary>Alternativa: túnel con <code>config.yml</code> local</summary>

   Solo si gestionas el túnel con archivo (no es el caso del token):
   ```yaml
   ingress:
     - hostname: nyx.neto.chat
       service: http://localhost:8081
     # … tus otras reglas …
     - service: http_status:404
   ```
   `cloudflared tunnel route dns <TU_TUNEL> nyx.neto.chat` y reinicia el túnel.
   </details>
3. **Comprueba el camino** desde cualquier sitio (debe responder `101 Switching Protocols`):
   ```bash
   npx wscat -c wss://nyx.neto.chat        # o:  websocat wss://nyx.neto.chat
   ```
4. **Multiaddr de bootstrap** (lo que pones en "Nodo WAN" en la app):
   ```
   /dns4/nyx.neto.chat/tcp/443/wss/p2p/<PeerID>
   ```

> **Timeouts de Cloudflare (Free):** ~100 s de inactividad y ~10 min por conexión. La app
> **reconecta sola** (bucle WAN de `ChatService`). Y recuerda: cuando dos móviles logran
> conexión **directa por DCUtR**, el tráfico va P2P directo (no por Cloudflare) y el timeout no
> aplica; el cap de 10 min solo afecta al fallback por relay y al registro en la DHT.

## Mantenerlo vivo con launchd (en vez de Docker)

### Despliegue rápido (script)

En la Mac Catalina, con el repo ya sincronizado (para que llegue el `dist/`
fresco con listener `ws`), corre **un solo comando**:

```bash
bash infra/node/deploy-catalina.sh
```

El script ([deploy-catalina.sh](deploy-catalina.sh)) copia el binario a
`~/nyx`, instala/recarga el LaunchAgent ([chat.neto.nyx.node.plist](chat.neto.nyx.node.plist),
ya relleno con `-wsport 8081` y `KeepAlive`), y al final verifica que el nodo
escucha en `:8081` y que `node.log` lista la addr `.../tcp/8081/ws`. Aborta si el
binario es viejo (sin `-wsport`). Tras correrlo, anota el **PeerID** del
`node.log` — el bootstrap de la app es `/dns4/nyx.neto.chat/tcp/443/wss/p2p/<PeerID>`.

> **Comprobación rápida del origen** (debe dar `HTTP 400`/`426`, **no** "connection
> refused"):
> ```bash
> curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081
> ```
> Si da 400/426 pero `wss://nyx.neto.chat` sigue en 502, el problema está en la
> regla del túnel (Cloudflare → Zero Trust → Tunnels → Public Hostname →
> `HTTP localhost:8081`), no en el nodo.

Para pararlo: `launchctl unload ~/Library/LaunchAgents/chat.neto.nyx.node.plist`.

### Manual (sin el script)

El plist vive en [chat.neto.nyx.node.plist](chat.neto.nyx.node.plist) (ajusta
las rutas si tu usuario no es `davidsilva`). Cópialo y cárgalo:

```bash
cp infra/node/chat.neto.nyx.node.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/chat.neto.nyx.node.plist
launchctl start chat.neto.nyx.node
cat ~/nyx/node.log     # ver el PeerID
```

## Nodo primario en un VPS Linux (systemd) — recomendado para producción

> **Estado: DESPLEGADO el 14 ago 2026** (tarea 1.12 del [plan](../../docs/PLAN-NYX.md)). Esta
> es la sección que Nyx sí sigue. La caja: **Vultr São Paulo**, `216.238.104.36`, hostname
> `nyx-node-saopaulo`, Ubuntu 24.04.4, 1 vCPU / 2 GB / 47 GB, PeerID
> `12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3`. El día a día está en
> [OPERACION.md](OPERACION.md).
>
> Es la **única línea** de `Libp2pNode.DEFAULT_BOOTSTRAP`, como
> `/dns4/nyx.neto.chat/tcp/4001/p2p/<PeerID>`: TCP directo (con QUIC en 4001/udp y `ws` en 8081
> como caminos alternativos), **sin proxy** — el registro A de `nyx.neto.chat` está con la nube
> **gris**, porque el proxy de Cloudflare solo entiende HTTP y rompería el TCP+Noise del 4001.
> Va por nombre y no por IP literal a propósito: el multiaddr viaja compilado en cada APK, así
> que mover el nodo debe costar un registro DNS y no una release de Play. La seguridad no se
> apoya en el DNS — la da el `/p2p/<PeerID>`, y un nombre secuestrado hace fallar el handshake
> Noise. Validado con las cuatro sondas de abajo antes de fijarlo, por IP y por nombre: buzón,
> wake, ciclo completo y latencia **p50 ≈ 105 ms** desde La Paz — mejor que los 107/119 ms del
> nodo equivalente de Krypta y que los 146–163 ms de los nodos domésticos vía Cloudflare.
>
> Se eligió Vultr porque **DigitalOcean no tiene ningún datacenter en Sudamérica** (NYC, San
> Francisco, Toronto, Atlanta, Richmond, Kansas City, Amsterdam, Londres, Fráncfort,
> Singapur, Bangalore, Sídney): para un relay de voz/vídeo la región manda sobre la marca.
>
> Copia de seguridad de la identidad **hecha en el propio aprovisionamiento (14 ago)**:
> `node.key` está respaldada en `~/keys/nyx-node/node.key` en la Mac del autor (`600`, fuera
> del repo). Verificada de verdad: mismo SHA-256 que la del VPS y, al deserializarla, deriva
> el PeerID real del nodo. Para restaurar, cópiala a `/var/lib/nyx/node.key` (dueño
> `nyx:nyx`, permisos `600`) **antes** de arrancar el servicio; si el servicio arranca sin
> ella, se genera una identidad nueva y el PeerID cambia.
>
> `net.core.rmem_max`/`wmem_max` se subieron a 7,5 MB en el aprovisionamiento
> (`/etc/sysctl.d/99-nyx-quic.conf`), así que quic-go ya no avisa al arrancar. Sigue
> pendiente: **topes finitos al relay** antes de abrirlo al público (ver el aviso de tráfico
> arriba) y un **segundo nodo** — hoy este es punto único de fallo del buzón, el wake y el
> relay.

### Por qué un VPS cambia las cosas (no es solo uptime)

Con **IP pública** te quitas Cloudflare de en medio y desaparecen tres parches:

1. **QUIC/UDP de verdad.** El túnel solo lleva HTTP/WebSocket, por eso hoy todo va por
   `wss`. Con IP pública entra y sale UDP → DCUtR perfora mejor, más conexiones directas y
   **menos tráfico por el relay**.
2. **Se acaba el reciclado de WebSocket** de Cloudflare Free (~100 s de inactividad, ~10 min
   de vida), que es lo que obliga a `ChatService.wanLoop` a reconectar cada 30 s. Con un wake
   estable se puede relajar el ciclo → **menos batería** en el móvil.
3. **Se acaba la zona gris del ToS** de Cloudflare Free con tráfico continuo no-HTML.

### Qué contratar

El nodo es un proceso Go estático sin base de datos: **1–2 vCPU, 2 GB de RAM y 20–40 GB de
disco sobran**. Lo que importa de verdad:

- **IPv4 pública dedicada** y poder abrir puertos TCP **y UDP** (no todos los "VPS baratos"
  dejan UDP libre).
- **Región cerca de los usuarios**: esto es un relay de voz y vídeo, cada milisegundo se suma
  a los 146–180 ms medidos en la sonda de latencia. Para Latinoamérica, São Paulo o Miami;
  un VPS europeo mete ~200 ms extra y se nota en llamada.
- **Tráfico**: una llamada de voz relayada son ~6 KB/s por sentido ≈ **43 MB/hora** de salida;
  una videollamada a 250 kbps ≈ **225 MB/hora**. Con los cupos habituales (1–20 TB/mes) el
  uso legítimo es despreciable. **El riesgo es el relay abierto**: hoy
  `EnableRelayService(relayv2.WithInfiniteLimits())` (ver [main.go](main.go)) regala ancho de
  banda a cualquier nodo libp2p de internet. Antes de exponerlo con factura por tráfico, pon
  topes finitos o una ACL.

### Despliegue (un comando)

```bash
cd infra/nyx-node
# 1. Compila el binario Linux (Go puro, no hace falta Go en el VPS)
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go1.22.12 build -o dist/nyx-node-linux-amd64 .

# 2. Despliega (desde la Mac; pide sudo en el VPS)
bash deploy-vps.sh root@nyx.neto.chat         # o: usuario@host arm64
```

[`deploy-vps.sh`](deploy-vps.sh) sube el binario a `/usr/local/bin/nyx-node`, crea el
usuario de sistema `nyx` y `/var/lib/nyx`, instala
[`nyx-node.service`](nyx-node.service) (`Restart=always`, arranca en el boot,
`LimitNOFILE=65535` porque libp2p abre muchos sockets), abre los puertos en ufw si está
activo, y al final imprime el PeerID y los multiaddrs para pegar en la app. Es **idempotente**:
relanzarlo actualiza el binario sin tocar el `node.key`, así que **el PeerID se conserva**.

> Si el `node.key` se pierde, el nodo cambia de PeerID y **los móviles ya instalados dejan de
> encontrarlo**. Guárdalo (`/var/lib/nyx/node.key`) antes de reinstalar la máquina.

Puertos: **4001/tcp** y **4001/udp** (libp2p) al exterior; **8081** es WebSocket en claro y
**no se abre**, lo consume el proxy TLS local.

### Puerto QUIC fijo (`-quicport`)

El nodo escuchaba QUIC en `udp/0` — puerto **efímero**, que es lo correcto detrás de
Cloudflare (el túnel no lleva UDP, así que QUIC ni se usa). En un VPS hay que fijarlo, porque
el multiaddr de bootstrap debe ser estable entre reinicios. De ahí la bandera `-quicport`
(default `0` = comportamiento anterior); la unidad systemd pasa `-quicport 4001`.

### TLS en 443 (opcional pero recomendado)

Con IP pública el camino simple es TCP/QUIC en 4001 y listo. Aun así conviene dejar
`wss/443` como plan B: hay wifis públicas y redes corporativas que bloquean todo lo que no
sea 443. Lo más corto es **Caddy** (Let's Encrypt automático) delante del `ws` en claro:

```caddyfile
nyx3.tudominio.com {
    reverse_proxy localhost:8081
}
```

Con eso el bootstrap `wss` es `/dns4/nyx3.tudominio.com/tcp/443/wss/p2p/<PeerID>`, igual
que el de Cloudflare pero sin el reciclado de conexiones.

### Validación antes de ponerlo en `DEFAULT_BOOTSTRAP`

Las mismas sondas que se usaron con el nodo Windows, desde la Mac:

```bash
export PATH="/usr/local/bin:$HOME/go/bin:$PATH"
cd native-bridge/libp2p
MBX_ADDR=/ip4/<IP>/tcp/4001/p2p/<PeerID> go test -run TestMailboxFetchAgainstLiveNode -v ./...
WAKE_ADDR=/ip4/<IP>/tcp/4001/p2p/<PeerID> go test -run TestWakeAgainstLiveNode -v ./...
# Ciclo completo (A deposita → nodo → B retira, payload y remitente verificados). Las dos de
# arriba solo comprueban que el protocolo responde; esta prueba que un mensaje llega entero:
MBX_ADDR=/ip4/<IP>/tcp/4001/p2p/<PeerID> go test -run TestMailboxRoundTripAgainstLiveNode -v ./...
# Latencia real (es un relay de voz/vídeo: este número decide si la región elegida sirve):
PING_ADDR=/ip4/<IP>/tcp/4001/p2p/<PeerID> go test -run TestPingAgainstLiveNode -v ./...
```

Cuando pasen, añádelo en el móvil por Ajustes → "Nodos WAN (bootstrap)" y, una vez validado
en vivo, muévelo a `Libp2pNode.DEFAULT_BOOTSTRAP` **como primera línea** (nodo primario),
dejando el de Windows de secundario. La Mac Catalina puede jubilarse a máquina de desarrollo.

> Recuerda que `DEFAULT_BOOTSTRAP` **solo afecta a instalaciones nuevas**: `savedBootstrap()`
> cae al default únicamente si la preferencia está *ausente*, así que un móvil que ya guardó
> la suya se queda con la lista vieja (le pasó al TECNO en julio; se arregló borrando la pref
> con `run-as`). Con la app aún sin publicar da igual; si llega un nodo nuevo cuando ya haya
> usuarios, hará falta fusionar el default con la pref guardada.

## Segundo nodo en Windows (multi-nodo / failover)

> ⚠️ **No aplica a Nyx tal cual.** Esta sección describe el segundo nodo **de Krypta**, que
> corre en el PC Windows del autor. Se conserva porque el mecanismo (cross-compilar el `.exe`,
> exponerlo, probar el failover) es el mismo que hará falta cuando Nyx monte su segundo nodo
> — pero ese irá en **otra caja propia**, no en una máquina que ya sirve a Krypta. Los datos
> del nodo de Krypta (hostname y PeerID) se han quitado a propósito.

El nodo es Go puro: para Windows basta **cross-compilar un `.exe` y ejecutarlo** (no hay
que instalar Go en el PC). `dist/` **no lleva binario de Windows** (ver la nota de Catalina
arriba). Compilarlo:

```bash
cd infra/nyx-node
GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=windows GOARCH=amd64 \
  go1.22.12 build -o dist/nyx-node-windows-amd64.exe .
```

> Cada nodo tiene su **propia identidad**: el `node.key` de Windows se crea solo la primera
> vez y su PeerID es distinto del nodo de la Mac. No copies el `node.key` de un nodo a otro.

### Fase A — arrancarlo en la LAN

1. Copia el `.exe` al PC, p. ej. a `C:\nyx\`.
2. (Recomendado) Reserva la IP del PC en el router (DHCP reservation) para que no cambie.
3. Ábrelo desde `cmd`/PowerShell:
   ```bat
   cd C:\nyx
   nyx-node-windows-amd64.exe -key C:\nyx\node.key -wsport 8081
   ```
   Windows Defender Firewall preguntará al primer arranque: **permite redes privadas**
   (si no pregunta: Panel de control → Firewall → permitir una aplicación, o abre los
   puertos TCP 4001 y 8081 a mano). Anota el **PeerID** que imprime.
4. Que el PC no se duerma: Configuración → Sistema → Energía → suspensión **Nunca**
   (al menos enchufado).
5. Prueba desde la Mac (debe dar `400`/`426`, no "connection refused"):
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://<IP_LAN_DEL_PC>:8081
   ```
6. **Prueba LAN con el móvil en la misma WiFi**: añade en "Nodos WAN (bootstrap)" una
   segunda línea `/ip4/<IP_LAN_DEL_PC>/tcp/4001/p2p/<PeerID_windows>` y Aplicar.

### Fase B — exponerlo a internet (nodo WAN real)

Dos variantes; la B2 es la de verdad redundante:

- **B1 (rápida, reutiliza el túnel de la Mac):** en Cloudflare → Zero Trust → Tunnels →
  (túnel existente) → Public Hostname → Add: Subdomain `nyx2` · Domain `neto.chat` ·
  Service **HTTP** → `http://<IP_LAN_DEL_PC>:8081`. No se instala nada en Windows, pero si
  la Mac (cloudflared) cae, caen ambos nodos — vale para probar failover del *proceso* nodo.
- **B2 (independiente):** instala cloudflared en el PC (`winget install Cloudflare.cloudflared`),
  crea un **segundo túnel** en el dashboard (Zero Trust → Tunnels → Create), instálalo como
  servicio con el token que te da (`cloudflared service install <TOKEN>`, arranca con
  Windows) y añádele el Public Hostname `nyx2.neto.chat` → **HTTP** → `http://localhost:8081`.

Comprobación desde cualquier red (debe responder `101` + saludo multistream):
```bash
npx wscat -c wss://nyx2.neto.chat
```
Multiaddr del segundo nodo para la app:
```
/dns4/nyx2.neto.chat/tcp/443/wss/p2p/<PeerID_windows>
```

### Fase C — la prueba de failover (PRUEBAS-PENDIENTES, multi-nodo)

1. En **ambos** móviles, "Nodos WAN (bootstrap)" con las dos líneas (Mac + Windows) → Aplicar.
2. Verifica el caso normal: mensaje con el receptor cerrado → llega (por cualquiera).
3. **Apaga el nodo de la Mac** (`launchctl unload ~/Library/LaunchAgents/chat.neto.nyx.node.plist`).
4. Con la app del receptor cerrada, envía un mensaje: debe salir **SENT** (buzón del nodo
   Windows) y llegar con notificación al abrir/despertar el receptor.
5. Reactiva el nodo de la Mac (`launchctl load …`).

Cuando el nodo Windows quede estable, añadir su multiaddr a `Libp2pNode.DEFAULT_BOOTSTRAP`
(segunda línea) para que los móviles lo traigan de serie.

### Mantenerlo vivo al arrancar Windows (opcional)

Programador de tareas → Crear tarea básica → Desencadenador "Al iniciar el equipo" →
Acción "Iniciar un programa" → `C:\nyx\nyx-node-windows-amd64.exe` con argumentos
`-key C:\nyx\node.key -wsport 8081`. En Propiedades: "Ejecutar tanto si el usuario
inició sesión como si no" y desmarcar "Detener si se ejecuta más de…".

## En la app (ambos móviles)

Pega tu multiaddr `/ip4/<IP_PUBLICA>/tcp/4001/p2p/<PeerID>` en el campo **"Nodo WAN
(bootstrap)"** y pulsa OK. La app se une a la DHT y empieza a anunciarse/buscar por
rendezvous (`HKDF(sharedSecret, día)`) por cada contacto. Cuando dos contactos coinciden,
se conectan y los mensajes (E2EE) fluyen aunque no estén en la misma WiFi.

> Limitación actual: si ambos están tras NAT simétrico/CGNAT, hará falta que el tráfico pase
> por el **relay** del nodo (ya activado) y/o **DCUtR**; eso es el gate de NAT que se valida
> con 2 móviles en redes de operadora distintas.
