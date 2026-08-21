# AvatarFace · kit de integración del generador de avatares

> **Nota de Nyx (21 ago 2026):** el kit ya está integrado, así que las rutas que este
> documento menciona (`kotlin/`, `python/`, `assets/`…) **ya no son las de este repositorio**.
> Dónde acabó cada archivo, qué se le cambió y qué se le añadió — el avatar derivado del
> PeerID — está en [INTEGRACION-NYX.md](INTEGRACION-NYX.md). Lee eso primero; el resto de este
> archivo se conserva tal cual porque el razonamiento sigue siendo válido.

Este paquete contiene todo lo necesario para incorporar el generador de
avatares de AvatarFace a otro proyecto (Nyx). Está escrito para que un agente
de código lo aplique sin tener que reconstruir el razonamiento que llevó hasta
aquí.

Fecha del paquete: 2026-08-21. Origen: repositorio `dasilvabalautaro/Avatar`,
decisión rectora `ADR 0012`.

## 1. Qué es esto en una frase

Un generador de avatares de rostro **dibujado por código**: texto libre →
atributos estructurados → dibujo vectorial plano. **No hay modelo neuronal, no
hay pesos, no hay red.** Dibuja un avatar de 256 px en ~11 ms en un portátil y
en **18–20 ms en un teléfono de gama baja** (TECNO KM5s, medido).

## 2. Lo primero que hay que entender: por qué no hay modelo

No es una simplificación ni un atajo. Es el resultado de descartar la vía
neuronal con medición, y conviene saberlo para no reabrirla por intuición:

- Se destiló Würstchen v2 a un estudiante compacto en cinco formulaciones
  distintas a lo largo de ~28 h de RTX 4090.
- El mejor estudiante (7.5 M parámetros) medido en el dispositivo daba
  **4.46 s por imagen en INT8 y producía ruido**; en FP32 daba imágenes
  correctas pero **11 s**, contra un presupuesto de 5 s.
- El hallazgo decisivo fue de diseño: el modelo estaba condicionado
  **únicamente por los atributos discretos** del vocabulario cerrado. No
  aportaba poder expresivo sobre el vocabulario; funcionaba como un
  renderizador caro y borroso de una tabla de valores categóricos.

El detalle completo está en `docs/por-que-sin-modelo.md`.

**Consecuencia práctica para Nyx:** si alguien propone «usar IA para generar
los avatares», el coste es de segundos por imagen y decenas de MB de pesos, a
cambio de imágenes menos nítidas. El dibujo por código es mejor en todos los
ejes medibles y además garantiza el funcionamiento offline por construcción.

## 3. Qué copiar

### Ruta rápida (app Android)

Copiar los cinco archivos de `kotlin/` al proyecto y cambiarles el paquete:

| Archivo | Qué hace |
|---|---|
| `Geometry.kt` | splines Catmull-Rom, arcos elípticos, simetría |
| `Palette.kt` | paleta y utilidades de color |
| `AvatarAttributes.kt` | los 17 atributos y sus valores por defecto |
| `AvatarPrompt.kt` | **contrato del prompt y filtro de sólo adultos (RF-09)** |
| `AttributeParser.kt` | texto libre → atributos |
| `AvatarRenderer.kt` | el dibujo; devuelve un `Bitmap` |

`AvatarActivity.kt` se incluye como **ejemplo de uso**, no para copiarlo tal
cual: monta una pantalla mínima con campo de texto y vista previa que se
redibuja en cada pulsación.

Uso mínimo:

```kotlin
when (val result = AvatarPrompt.validate(userText)) {
    is AvatarPrompt.Result.Invalid -> mostrarMotivo(result.reason)
    is AvatarPrompt.Result.Valid -> {
        val attributes = AttributeParser.parse(result.text)
        imageView.setImageBitmap(AvatarRenderer(256).render(attributes))
    }
}
```

Sin dependencias externas: sólo `android.graphics`. No añade permisos ni red.

### Ruta de servidor o herramientas (Python)

`python/` tiene la implementación equivalente. Requiere sólo Pillow.

```python
from attributes import attributes_from_text
from avatar_renderer import FlatVectorAvatarRenderer

attributes = attributes_from_text("smiling adult with curly pink hair and round glasses")
FlatVectorAvatarRenderer(image_size=256).render(attributes).save("avatar.png")
```

`prompt_models.py` contiene `AvatarPrompt`, que aplica el filtro RF-09 antes
de dibujar; en el flujo de producto hay que pasar por él.

## 4. Reglas que no se pueden romper

### 4.1 Sólo adultos (RF-09)

**El producto genera únicamente rostros de personas adultas.** El filtro está
en `AvatarPrompt` y actúa **antes** de dibujar: rechaza 41 términos que
sugieren minoría de edad (en español e inglés, normalizados sin diacríticos) y
también edades explícitas menores de 18 («16 años», «17 y/o»). La lista está
en `assets/vocabulario.json`, campo `terminos_menores_rf09`.

Si Nyx añade otra vía de entrada (selectores, importación, plantillas), esa vía
**también** tiene que pasar por la validación. Un selector que permita
componer un avatar infantil sin pasar por el texto sería un agujero en el
requisito.

### 4.2 Las implementaciones van juntas

El trazado y el parser existen en **dos lenguajes**. Si se cambia uno, hay que
cambiar el otro, o las imágenes divergen entre plataformas. `docs/verificacion.md`
explica el mecanismo automático que lo detecta y que ya está montado.

### 4.3 Las doce reglas de estilo

Están en `docs/reglas-de-estilo.md`. Salieron de errores concretos y visibles
(sombras que parecían manchas, orejas de elfo, barbas con forma de bufanda,
rizos como corona de picos). Cualquier ajuste de coordenadas debe respetarlas.

## 5. Vocabulario

17 atributos, **13,226,976,000,000 combinaciones**. El detalle legible por
máquina está en `assets/vocabulario.json`; la explicación con frases de
ejemplo, en `docs/vocabulario.md`.

Resumen: expresión (6), forma de cara (6), tono de piel (9), estilo de pelo
(12), color de pelo (10), color de ojos (6), forma de ojos (5), accesorio
heredado (6), fondo (8), cejas (5), nariz (5), vello facial (6), gafas (5),
pendientes (3), pecas (3), prenda (5), color de prenda (7).

## 6. Verificación incluida

- `assets/parser-cases.json` — 10 frases con los atributos que debe producir
  cada una. Sirve como prueba de regresión del parser en cualquier lenguaje.
- `assets/gallery-specs.json` — 12 personas de referencia.
- `referencia/` — cómo deben verse esas 12 personas (`galeria.png` y los PNG
  sueltos) y tres capturas reales del teléfono, incluida la del rechazo por
  minoría de edad.
- `scripts/` — generación de la galería, volcado de casos del parser y
  comparador de trazados entre plataformas.

Con eso, Nyx puede verificar que su integración produce exactamente los mismos
avatares que la referencia.

## 7. Licencia

Código y documentación: Apache-2.0 (`LICENSE`, `NOTICE`). El dibujo es
íntegramente propio: no usa activos, pesos ni datos de terceros, y no contiene
ni deriva de rostros reales.
