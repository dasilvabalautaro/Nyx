# Contenido del paquete

```
avatarface-render-kit/
├── LEEME.md                       Guía de integración: empezar aquí
├── LICENSE, NOTICE                Apache-2.0
├── kotlin/                        Implementación para Android (sin dependencias)
│   ├── Geometry.kt                Splines Catmull-Rom, arcos elípticos, simetría
│   ├── Palette.kt                 Paleta y utilidades de color
│   ├── AvatarAttributes.kt        Los 17 atributos y sus valores por defecto
│   ├── AvatarPrompt.kt            Contrato del prompt y filtro RF-09 (sólo adultos)
│   ├── AttributeParser.kt         Texto libre → atributos
│   ├── AvatarRenderer.kt          El dibujo; devuelve un Bitmap
│   └── AvatarActivity.kt          Ejemplo de pantalla, no para copiar tal cual
├── python/                        Implementación equivalente (sólo requiere Pillow)
│   ├── geometry.py, palette.py, avatar_renderer.py
│   ├── attributes.py              Vocabulario y parser de texto
│   └── prompt_models.py           AvatarPrompt con el filtro RF-09
├── assets/
│   ├── vocabulario.json           17 atributos, valores, defectos y términos RF-09
│   ├── parser-cases.json          10 frases con los atributos que deben producir
│   └── gallery-specs.json         Las 12 personas de referencia
├── referencia/
│   ├── galeria.png                Cómo deben verse las 12 personas
│   ├── persona-01..12.png         Cada una por separado, 256 px
│   ├── dispositivo-texto.png      Captura real: se escribe y aparece el avatar
│   ├── dispositivo-barba.png      Captura real: otra descripción
│   └── dispositivo-rechazo-menor.png  Captura real: RF-09 rechazando
├── scripts/
│   ├── render_gallery.py          Dibuja la galería de referencia
│   ├── dump_parser_cases.py       Congela los casos del parser
│   └── compare_android_render.py  Compara el trazado entre plataformas
└── docs/
    ├── por-que-sin-modelo.md      La decisión y sus mediciones
    ├── vocabulario.md             Atributos, patrones de texto y ejemplos
    ├── reglas-de-estilo.md        Las doce reglas del dibujo
    ├── verificacion.md            Cómo mantener las dos implementaciones sincronizadas
    └── estructura.md              Este archivo
```

## Orden de lectura sugerido

1. `LEEME.md` — qué es y qué copiar.
2. `docs/por-que-sin-modelo.md` — por qué no hay red neuronal, con los números.
3. `docs/vocabulario.md` — qué se puede describir y cómo se escribe.
4. `docs/reglas-de-estilo.md` — antes de tocar cualquier coordenada.
5. `docs/verificacion.md` — antes de dar por buena la integración.
