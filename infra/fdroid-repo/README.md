# Repo F-Droid autoalojado de Krypta

Repo F-Droid **propio y privado** (no el índice oficial de F-Droid) para distribuir el
`.apk` firmado de Krypta a un grupo de pruebas sin pasar por Google Play — relevante desde
que Google empieza a exigir verificación de desarrollador (identidad, contrato, cuota) a
partir de septiembre para que un APK instale en dispositivos certificados, sea cual sea el
canal. El APK sigue firmado con el mismo `keystore.jks` de siempre (ver
[../../keystore.properties](../../keystore.properties)); este directorio añade, aparte,
el **keystore del propio repo** (firma el índice, no la app) que genera `fdroid init`.

Se sirve en `127.0.0.1:8082` en la misma Mac Catalina que ya aloja el nodo de
señalización, expuesto por el mismo túnel de Cloudflare bajo un **subdominio distinto**:
`fdroid.neto.chat` — aislado a propósito del nodo libp2p en `krypta.neto.chat:8081`
(que habla WebSocket de libp2p, no HTTP genérico; mezclarlos en el mismo puerto rompería
la conexión WAN ya verificada).

## 1. Un solo Public Hostname en Cloudflare (dashboard, una vez)

Igual que se hizo para `krypta.neto.chat` (ver
[../node/README.md](../node/README.md#detrás-de-cloudflare-tunnel-sin-ip-pública--vía-wss)),
sin tocar `cloudflared` desde archivo:

**Cloudflare → Zero Trust → Networks → Tunnels → (tu túnel) → Public Hostname → Add a
public hostname**
- Subdomain `fdroid` · Domain `neto.chat`
- Service: **HTTP** → `localhost:8082`

Nada más — no requiere reiniciar `cloudflared` ni tocar Nginx.

## 2. Desplegar/actualizar el repo (script)

En la Mac Catalina, con el repo sincronizado:

1. Copia el APK firmado desde la máquina de build (el `.apk` no vive en este repo, pesa
   ~100 MB):
   ```bash
   scp app/build/outputs/apk/release/app-release.apk catalina:/tmp/
   ```
2. En la Catalina:
   ```bash
   bash infra/fdroid-repo/deploy-catalina.sh /tmp/app-release.apk
   ```

El script ([deploy-catalina.sh](deploy-catalina.sh)):
1. La primera vez, instala `fdroidserver` (`pip3 install --user`) e inicializa el repo en
   `~/krypta-fdroid` con `fdroid init` (genera su propio keystore de firma del **índice**
   — distinto del keystore de la app; no lo pierdas, lo necesitas para toda actualización
   futura del repo).
2. Copia el `.apk` a `~/krypta-fdroid/repo/` y corre `fdroid update`, que **lee el
   manifiesto del APK** (paquete, versionCode/Name, permisos, icono) para generar el
   índice firmado.
3. Instala/recarga el LaunchAgent ([chat.neto.krypta.fdroid.plist](chat.neto.krypta.fdroid.plist))
   que sirve `~/krypta-fdroid/repo/` (**solo esa carpeta**, ver aviso abajo) en
   `127.0.0.1:8082` con `KeepAlive`.

> **`fdroid update` necesita leer el APK** (`aapt` del SDK de Android, o `androguard` como
> alternativa pura-Python). Si el script se queja de `aapt` y no quieres instalar el SDK de
> Android en la Catalina solo para esto: `pip3 install --user androguard`, que
> `fdroidserver` usa en su lugar.

Para pararlo: `launchctl unload ~/Library/LaunchAgents/chat.neto.krypta.fdroid.plist`.

## 3. Actualizar a una versión nueva

Sube el `versionCode`/`versionName` en `app/build.gradle.kts` (como para Play), genera un
APK nuevo firmado con el **mismo keystore de siempre**, y repite el paso 2 — `fdroid
update` detecta la versión nueva por el `versionCode` del manifiesto y la añade al índice
sin tocar nada más. El cliente F-Droid la ofrece como actualización igual que Play.

> ⚠️ **No cambies nunca el keystore de la app** (`krypta.jks`) entre versiones publicadas
> aquí: F-Droid (como Android en general) rechaza una "actualización" firmada con una
> clave distinta a la ya instalada.

## 4. Aviso de seguridad: qué carpeta se sirve

El LaunchAgent apunta **expresamente** a `~/krypta-fdroid/repo/`, no a `~/krypta-fdroid/`
completo. La carpeta padre contiene `config.yml` (con la contraseña del keystore del
índice en texto plano) y `keystore.p12` (la clave privada de firma del índice) —
**nunca deben quedar servidos públicamente**. Si en algún momento cambias el `.plist` a
mano, respeta ese límite.

## 5. En los móviles de prueba

1. Instala el cliente **F-Droid** (o alguno compatible, como Droid-ify).
2. Añade un repo nuevo con la URL: `https://fdroid.neto.chat`
3. Krypta aparece en ese repo — instalar/actualizar desde ahí, sin pasar por Play.

## Relación con el índice oficial de F-Droid

Esto es un repo **propio**, no una entrega a `fdroiddata` (el índice oficial). Para entrar
ahí, F-Droid exige build reproducible 100% desde código dentro de su propio entorno — cosa
que hoy no cumplimos: `native-bridge/libs/krypta-p2p.aar` es un binario compilado aparte
con `gomobile` y comiteado al repo, no algo que su infraestructura de build pueda
regenerar por sí sola. No es necesario para lo que se busca ahora (repartir un build de
pruebas a un grupo pequeño sin bloqueo de Google); si algún día se persigue el índice
oficial, habría que adaptar el pipeline de build de `native-bridge` a su formato de receta
reproducible.
