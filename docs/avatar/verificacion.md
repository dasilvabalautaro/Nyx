# Cómo mantener las implementaciones sincronizadas

El trazado y el parser existen en **Python y Kotlin**. Es la fuente de error
más probable al integrar: un ajuste en un lenguaje y no en el otro hace que la
app y las herramientas produzcan avatares distintos para la misma persona.

Hay dos comprobaciones automáticas, y ambas están incluidas en el kit.

## 1. Paridad del trazado

`assets/gallery-specs.json` fija doce personas. Se dibujan en las dos
plataformas y se comparan píxel a píxel.

```bash
python scripts/render_gallery.py --output-dir referencia            # Python
# … dibujar las mismas doce en la app y descargarlas …
python scripts/compare_android_render.py --android <dir> --python referencia
```

El comparador exige **≤6/255 de diferencia media** y ≤10/255 en el peor caso.
El margen absorbe el antialiasing distinto de Pillow y Skia —la igualdad exacta
no es alcanzable ni necesaria— pero delata cualquier rasgo desplazado o color
equivocado, que se disparan muy por encima.

Última medición conocida: **0.43 % de diferencia media**, peor caso 1.09 %.

## 2. Paridad del parser

`assets/parser-cases.json` contiene diez frases con los atributos que debe
producir cada una, generadas desde Python con `scripts/dump_parser_cases.py`.
La app las parsea con su implementación y reporta cualquier diferencia.

Última medición conocida: **0 discrepancias** en 10 frases × 17 atributos.

## Recomendación para Nyx

Montar ambas comprobaciones en el flujo de integración continua o, como
mínimo, ejecutarlas a mano tras cualquier cambio en coordenadas, paleta o
patrones del parser. En el repositorio de origen las dos van en una sola orden
(`scripts/render-on-device.sh <serial>`), que regenera los assets, compila,
dibuja en el dispositivo y verifica las dos paridades.

Si sólo se integra una plataforma, la comprobación del parser sigue siendo
útil como prueba de regresión: garantiza que las mismas frases siguen
produciendo los mismos avatares tras cualquier refactor.
