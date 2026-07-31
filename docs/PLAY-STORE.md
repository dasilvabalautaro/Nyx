# Salida a Google Play — estado y pendientes

Auditoría del 23 jul 2026 sobre el build de release real (`:app:bundleRelease`). Marca las
casillas según se vayan cerrando; lo que está hecho lleva la fecha de verificación.

## Registro de versiones generadas

El repo no está bajo git, así que **anota aquí cada AAB que se genere**: Play rechaza un
`versionCode` repetido, y sin este registro no hay forma de saber cuál se subió. Los huecos
en la numeración sí están permitidos (solo tiene que ser creciente).

| versionCode | versionName | Fecha        | Estado                     |
|-------------|-------------|--------------|----------------------------|
| 3           | 1.2         | 23 jul 2026  | generado (¿subido?)        |
| 4           | 1.3         | 31 jul 2026  | generado, listo para subir |

## Listo

- [x] **Keystore de producción**: `keystore.properties` (git-ignored) relleno y apuntando a
      `~/keystores/krypta/krypta.jks`; `hasReleaseKeystore` en
      [app/build.gradle.kts](../app/build.gradle.kts) selecciona la firma real y cae al
      keystore de depuración solo si falta. AAB firmado generado (~82 MB).
- [x] **R8 activo** con las reglas que preservan el puente gomobile (`go.**`,
      `chat.neto.krypta.bridge.**`), verificado en dispositivo.
- [x] **Símbolos nativos para Play**: `ndk { debugSymbolLevel = "FULL" }` (libgojni.so no
      pasa por el build nativo de AGP; sin esto Play no recibe símbolos).
- [x] **targetSdk 36** (por encima del mínimo exigido a apps nuevas).
- [x] **Páginas de 16 KB** (23 jul): `build-aar.sh` enlaza con
      `-extldflags=-Wl,-z,max-page-size=16384`; las 4 ABIs de `libgojni.so` a `0x4000` y
      `zipalign -c -P 16 -v 4` en verde. Requisito de Play para targetSdk ≥ 35 desde el
      1 nov 2025. **Al regenerar el AAR hay que volver a comprobarlo.**
- [x] **Sin copia automática de Google** (23 jul): `allowBackup="false"` + exclusiones
      explícitas en los dos XML. Antes la identidad Ed25519 y la base de mensajes subían al
      Drive del usuario. Verificado: `dumpsys package` ya no lista `ALLOW_BACKUP`.
- [x] **Política de privacidad redactada**: [politica-privacidad.html](politica-privacidad.html).

## Bloqueantes de ficha (trámite, no código)

- [ ] **Hospedar la política** en una URL pública (p. ej. `krypta.neto.chat/privacidad`) —
      Play exige URL, no un archivo del repo.
- [ ] **Formulario de Seguridad de los Datos** coherente con la política (sin recogida, sin
      terceros, E2EE en tránsito y en el buzón).
- [ ] **Clasificación de contenido** (cuestionario) y público objetivo.
- [ ] **Cumplimiento de exportación de cifrado** (la pregunta que hace Play por usar E2EE).
- [ ] **Declaración del FGS `specialUse`**: Google la revisa **a mano** y puede rechazarla si
      cree que encaja otro tipo. El `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` del manifiesto ya trae
      la justificación; conviene tener plan B por si la deniegan.
- [ ] **Justificar `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** (entrega de mensajes en 2.º plano
      sin push de terceros).
- [ ] **Prueba cerrada previa**: si la cuenta de desarrollador es personal y posterior a
      nov 2023, Play pide 12 testers durante 14 días antes de habilitar producción. Son dos
      semanas de calendario: conviene arrancarla cuanto antes.
- [ ] **Assets**: icono 512, gráfico destacado 1024×500, capturas, descripción corta y larga
      (reciclables de [MANUAL.md](MANUAL.md)).

## Producto / política de contenido

- [ ] **Bloquear contacto**. La política de contenido generado por usuarios pide bloqueo o
      denuncia en apps de comunicación. Hoy solo hay "Eliminar contacto". Juega a favor que
      `ChatService.onReceived` descarta a quien no es contacto (nadie desconocido puede
      escribir), y conviene decirlo en la ficha, pero un "Bloquear" explícito evita la
      discusión con el revisor.
- [ ] **Textos en `strings.xml`**: hoy todo el UI está hardcodeado en Kotlin y solo en
      español. No bloquea publicar; bloquea traducir.

## Infraestructura (el riesgo real, no lo mira Play)

- [ ] **Nodos en máquinas domésticas**: el Mac (Catalina) y el PC Windows del autor tras
      Cloudflare Free. Si se caen, se cae el buzón, el wake y el relay de **todos** los
      usuarios; además Cloudflare Free recicla los WebSocket y su ToS no contempla tráfico
      continuo no-HTML. Antes de abrir al público: al menos un VPS como nodo primario.
      **(23 jul) El despliegue está preparado, falta contratar la máquina**: binarios Linux
      amd64/arm64 en `infra/node/dist/`, unidad systemd `krypta-node.service`, script
      `deploy-vps.sh` (un comando desde la Mac) y runbook en la sección "Nodo primario en un
      VPS Linux" de [../infra/node/README.md](../infra/node/README.md). 1–2 vCPU y 2 GB
      bastan; lo que importa es IP pública con UDP abierto y **región cerca de los usuarios**
      (es un relay de voz/vídeo). Con IP pública se puede quitar Cloudflare: QUIC real, mejor
      DCUtR y adiós al reciclado de WebSocket que hoy obliga al ciclo de 30 s.
- [ ] **Relay abierto sin límites**: [infra/node/main.go](../infra/node/main.go) usa
      `EnableRelayService(relayv2.WithInfiniteLimits())` — necesario para que no se cortaran
      las llamadas (el tope por defecto es 128 KiB / 2 min), pero es ancho de banda gratis
      para **cualquier** nodo libp2p de internet, no solo para Krypta. Poner topes generosos
      pero finitos y/o una ACL.
- [ ] **Depósito en buzón sin restricción de origen**: hay cuota por destinatario (200 msgs /
      5 MiB / TTL 7 días) pero cualquiera puede depositar a cualquiera → vector de spam.
- [ ] **Monitorización/alertas** de los nodos (hoy no hay).

## Pruebas en vivo que no publicaría sin cerrar

Ver [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md):

- [ ] §2 Verificación anti-MITM real entre dos móviles (la del 23 jul no vale: se comparó
      contra el propio PeerID).
- [ ] §3 Reinicio del móvil, cambio de red y persistencia > 6 h.
- [ ] Failover multinodo (apagar el nodo del Mac y comprobar entrega por `krypta2`).
