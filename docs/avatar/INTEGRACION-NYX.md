# El avatar en Nyx: dónde quedó cada cosa y qué se le añadió

El resto de `docs/avatar/` es la documentación **original del kit de AvatarFace**, tal como
vino. Este archivo es lo específico de Nyx: dónde acabó cada pieza, por qué ahí, y la parte
que no traía el kit.

Fecha de integración: 21 ago 2026. Origen: `avatarface-render-kit.zip`, ADR 0012 del
repositorio `dasilvabalautaro/Avatar`. Licencia Apache-2.0 (`tools/avatar/LICENSE`).

## Qué decide la Fase 3b del plan

El plan dejaba abierta una decisión entre dos alternativas: **A**, un modelo neuronal propio
en el dispositivo; **B**, un constructor paramétrico sin texto libre. El kit **resuelve la
decisión y no encaja del todo en ninguna de las dos casillas**:

- Descarta la A **con mediciones**, no por intuición: el mejor estudiante destilado
  (7,5 M parámetros) daba 4,46 s por imagen en INT8 produciendo ruido, y 11 s en FP32 contra
  un presupuesto de 5 s. El hallazgo de fondo es más interesante que los números: el modelo
  estaba condicionado **sólo por los atributos discretos del vocabulario cerrado**, así que no
  aportaba poder expresivo — era un renderizador caro y borroso de una tabla de categorías.
- Pero tampoco es la B, porque **sí acepta texto libre**: lo que hace es traducir el texto a
  los atributos discretos y dibujar. Se queda con la entrada expresiva de la A y con el coste,
  el determinismo y la ausencia de superficie de abuso de la B.

Consecuencia práctica: si alguien vuelve a proponer "usar IA para los avatares", el coste son
segundos por imagen y decenas de MB de pesos a cambio de imágenes **menos** nítidas. Está
medido en `por-que-sin-modelo.md`.

## Dónde quedó cada archivo

El kit venía con todo junto en `kotlin/`. Aquí se parte según lo que necesita Android, porque
eso decide **dónde se puede probar**:

| Del kit | Aquí | Por qué |
|---|---|---|
| `Geometry.kt`, `Palette.kt`, `AvatarAttributes.kt`, `AvatarPrompt.kt`, `AttributeParser.kt` | `core/…/core/avatar/` | Kotlin puro. En `:core` se prueban con `testDebugUnitTest`, en la JVM. |
| `AvatarRenderer.kt` | `app/…/nyx/avatar/` | Necesita `android.graphics` (Bitmap, Canvas, Path). |
| `python/`, `scripts/`, `assets/`, `referencia/` | `tools/avatar/` | Herramientas y verificación; no se compilan. |
| `AvatarActivity.kt` | `tools/avatar/ejemplo/` | El propio kit dice que es un ejemplo, no para copiar. |
| `docs/`, `LEEME.md` | `docs/avatar/` | |

Dos cambios al código del kit, ambos para que la mitad pura pueda vivir en `:core`:

1. **`Palette` ya no usa `android.graphics.Color`.** Las tres utilidades (`parse`, `mix`,
   `shade`) hacen la aritmética a mano sobre el mismo `Int` ARGB que consume `Paint.setColor`.
   Con la clase de Android, el parser y el filtro RF-09 habrían tenido que probarse
   instrumentados — y en este repositorio los tests instrumentados de `:app` **borran la
   identidad del teléfono** (ver CLAUDE.md).
2. **`AvatarAttributes` perdió su `fromJson(JSONObject)`**, que no usaba nadie y ataba `:core`
   a `org.json`; en un test JVM eso es el stub de `android.jar` y devuelve nulos. Si el test de
   comparación de píxeles necesita leer `gallery-specs.json`, ese mapeo va en el *source set*
   de test, no en el modelo de dominio.

## Lo que se le añadió: el avatar derivado del PeerID

El kit genera el rostro **desde texto libre**. Eso es *presentación*, y para la tarjeta del
tablón es exactamente lo correcto. Pero un avatar elegido no **identifica** a nadie: cualquiera
puede escribir la misma descripción y obtener la misma cara.

`AvatarIdentity` (en `:core`, con gemelo en `tools/avatar/python/identity.py`) cubre el otro
papel: deriva los 16 atributos de `SHA-256("nyx-avatar-v1" ‖ contador ‖ peerId)`, siguiendo el
mismo idioma de dominio separado que `SafetyNumber` y `DiscoveryTopic`. Su entrada es el PeerID
y **sólo** el PeerID, así que:

- **Tu avatar es tuyo sin hacer nada.** Un perfil recién creado ya tiene un rostro propio y
  estable en vez de un hueco.
- **No se falsifica cambiando el texto**, porque no hay texto.
- **Reduce la superficie de RF-09**: un rostro derivado de un hash no puede pedir una persona
  menor de edad ni parecerse a alguien real a propósito. Sólo el camino de texto tiene ese
  riesgo, y por eso el derivado debería ser el punto de partida por defecto.

### Los dos papeles no se pueden mezclar

Es tentador concluir que entonces basta con mirar la cara para verificar a un contacto.
**No basta, y no conviene construir UI que lo insinúe.** Medido:

| | Entropía |
|---|---|
| Total derivada | ~39,5 bits |
| Perceptiva, viendo el avatar en grande | ~17 bits |
| En la lista de conversaciones, a ~40 dp | **~9 bits** |

Generar una identidad Ed25519 cuesta microsegundos: dar con un PeerID cuyo rostro *se parezca*
al de una víctima son miles de intentos, menos de un segundo. La verificación de verdad sigue
siendo [`SafetyNumber`] y el QR — 199 bits, y ese es el motivo de que el número sea largo y
aburrido de comparar.

Donde sí sirve, y mucho, es contra el **error**: pegar el PeerID equivocado, añadir dos veces a
la misma persona, abrir el chat que no era. Ahí el fallo salta a la vista.

El corolario de diseño es que un avatar **elegido** nunca debe presentarse como señal de
identidad. Si la gente aprende a reconocer contactos por una cara que el contacto eligió, el
atacante sólo tiene que escribir la misma descripción — sería una regresión frente al trabajo
que ya hay en `SafetyNumber`.

### Los pesos, y por qué existen

La primera galería derivada con muestreo **uniforme** salió con 8 de cada 10 con gafas, pelo
azul o verde por todas partes y barbas de colores. No era un fallo del mapeo: **un catálogo
curado no es una distribución uniforme**. Las doce personas de referencia del kit las eligió
una persona; sortear entre todas las combinaciones saca justo las que nadie elegiría.

Los vocabularios llevan ahora pesos (`AvatarIdentity.Choice`). Se paga en entropía y se paga a
gusto, precisamente porque la tabla de arriba ya establece que esa entropía no compra seguridad.
Todos los valores siguen siendo alcanzables; sólo dejan de ser equiprobables.

Una cosa que se decidió **no** hacer: correlacionar el vello facial con el peinado. Saldrían
rostros más "coherentes", pero es codificar una norma de género en el avatar por defecto de una
app de citas. Se bajó la frecuencia y quien quiera otra cosa la escribe.

### Verificación

- `AvatarIdentityTest` (11 tests) y `AvatarPromptTest` (7), en la JVM. Incluyen un **caso
  dorado** cuyos valores salen de ejecutar el gemelo de Python, así que fija el contrato
  (dominio, orden, pesos) **y** comprueba que las dos implementaciones coinciden — la regla 4.2
  del kit. Falsificado a mano cambiando `DOMAIN` a `nyx-avatar-v2`: falla ese test y sólo ese.
- `tools/avatar/scripts/render_identity_gallery.py` dibuja los rostros de una lista de PeerID.
  Las referencias versionadas están en `tools/avatar/referencia/identidades-derivadas.png` (24
  identidades) y `identidades-casi-iguales.png`, que incluye a propósito dos PeerID que sólo
  difieren en el último carácter — el error real que este avatar tiene que cazar.

## Lo que queda pendiente

- **Nada de esto está enchufado a la UI todavía.** Es Fase 4 del plan (`ProfileEditorScreen`,
  `DiscoveryCard`), y el avatar derivado añade una decisión de producto que antes no existía:
  dónde se muestra el elegido y dónde el derivado.
- **Contraste a tamaño pequeño**: con piel oscura y pelo oscuro, algunas caras se leen como una
  mancha a 40 dp. Se ve en `identidades-derivadas.png`. Una regla de contraste mínimo entre pelo
  y piel lo arreglaría; no se ha hecho porque acopla atributos y conviene decidirlo mirando la
  lista real.
- **Comparación de píxeles Android↔Python** (`compare_android_render.py`): requiere renderizar
  en Android, o sea un test instrumentado. En `:app` eso es destructivo; hazlo en un emulador.

[`SafetyNumber`]: ../../p2p-signaling/src/main/java/chat/neto/nyx/p2p/SafetyNumber.kt
