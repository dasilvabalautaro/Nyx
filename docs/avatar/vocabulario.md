# Vocabulario de atributos

El avatar se describe con **17 atributos de vocabulario cerrado**. El parser de
texto reconoce estos valores en inglés; el vocabulario es la unidad de
expresividad del sistema, así que ampliarlo es la vía para que más personas se
reconozcan en su avatar.

La versión legible por máquina está en `assets/vocabulario.json`.

## Tabla

| Atributo | Valores | Por defecto |
|---|---|---|
| Expresión (`expression`) | `smiling`, `calm`, `happy`, `confident`, `serious`, `friendly` | `calm` |
| Forma de cara (`face_shape`) | `round`, `oval`, `square`, `heart`, `long`, `diamond` | `oval` |
| Tono de piel (`skin_tone`) | `porcelain`, `light`, `beige`, `golden`, `olive`, `tan`, `brown`, `deep`, `ebony` | `light` |
| Estilo de pelo (`hair_style`) | `short`, `buzz`, `curly`, `wavy`, `side-parted`, `bob`, `long`, `ponytail`, `bun`, `afro`, `undercut`, `bald` | `short` |
| Color de pelo (`hair_color`) | `black`, `brown`, `auburn`, `blonde`, `blue`, `pink`, `gray`, `red`, `silver`, `green` | `brown` |
| Color de ojos (`eye_color`) | `brown`, `blue`, `green`, `gray`, `hazel`, `amber` | `brown` |
| Forma de ojos (`eye_shape`) | `almond`, `round`, `narrow`, `wide`, `hooded` | `almond` |
| Accesorio (heredado) (`accessory`) | `none`, `round glasses`, `square glasses`, `sunglasses`, `earrings`, `freckles` | `none` |
| Fondo (`background`) | `coral`, `mint`, `sky`, `lavender`, `sand`, `slate`, `rose`, `teal` | `sky` |
| Cejas (`brow_style`) | `natural`, `arched`, `thick`, `thin`, `angled` | `natural` |
| Nariz (`nose_style`) | `straight`, `small`, `button`, `wide`, `pointed` | `straight` |
| Vello facial (`facial_hair`) | `none`, `stubble`, `mustache`, `goatee`, `short beard`, `full beard` | `none` |
| Gafas (`glasses`) | `none`, `round`, `square`, `sunglasses`, `rectangular` | `none` |
| Pendientes (`earrings`) | `none`, `studs`, `hoops` | `none` |
| Pecas (`freckles`) | `none`, `light`, `heavy` | `none` |
| Prenda (`clothing`) | `crew neck`, `v-neck`, `collared shirt`, `hoodie`, `turtleneck` | `crew neck` |
| Color de prenda (`clothing_color`) | `white`, `charcoal`, `red`, `blue`, `green`, `mustard`, `purple` | `blue` |

## Cómo se escribe el texto

El parser resuelve los términos ambiguos **por contexto**, no por aparición
suelta. «brown» sólo asigna color de pelo si aparece como `brown hair`, y tono
de piel si aparece como `brown skin`; «round» distingue `round face`,
`round eyes` y `round glasses`. Un término suelto sin contexto no asigna nada,
lo que evita interpretaciones sorprendentes.

Patrones que reconoce:

- `<estilo> <color> hair` — «curly pink hair», «side-parted black hair»
- `<color> <forma> eyes` — «green almond eyes»
- `<tono> skin` — «deep skin»
- `<forma> face` — «square face»
- `<estilo> glasses` — «round glasses», «rectangular glasses»; también
  «sunglasses» suelto, y «glasses» a secas da montura redonda
- `<estilo> brows` / `<estilo> eyebrows` — «thick brows»
- `<forma> nose` — «wide nose»
- vello facial: «stubble», «mustache», «goatee», «short beard», «full beard»;
  «beard» a secas da barba corta, y «clean shaven» la quita
- pendientes: «hoops», «studs», «earrings»
- pecas: «freckles»; «many freckles» las hace densas
- prenda: «crew neck», «v-neck», «collared shirt», «hoodie», «turtleneck»
- color de prenda: `<color> shirt|top|hoodie|sweater` — «charcoal hoodie»
- fondo: `<color> background` — «mint background»
- expresión: «smiling», «calm», «happy», «confident», «serious», «friendly»

Lo que no se menciona toma el valor por defecto de la tabla, así que una
descripción corta siempre produce un avatar válido.

## Frases de ejemplo probadas

Estas diez frases están congeladas en `assets/parser-cases.json` junto con los
atributos que deben producir; sirven como prueba de regresión del parser.

- `smiling adult with curly pink hair and round glasses`
- `serious adult with deep skin and full beard and thick brows`
- `confident adult, square face, olive skin, bald, sunglasses, charcoal hoodie`
- `friendly adult with long red hair, green wide eyes, many freckles, mint background`
- `calm adult with silver bob hair, gray eyes, hoops and collared shirt`
- `happy adult with afro black hair, brown round eyes, mustard sweater`
- `adult with wavy blonde hair, hazel eyes, arched brows, pointed nose`
- `adult with side-parted blue hair, amber narrow eyes, goatee, teal background`
- `an adult`
- `adult with tan skin, buzz black hair, stubble, rectangular glasses, turtleneck`

## Ampliar el vocabulario

Añadir un valor exige tocar cuatro sitios y verificar:

1. el vocabulario (`attributes.py` y `AvatarAttributes.kt`),
2. el parser, si necesita un patrón nuevo (`attributes.py` y `AttributeParser.kt`),
3. el dibujo, en **ambos** lenguajes,
4. los casos de referencia (`scripts/dump_parser_cases.py`).

Después, comprobar con la galería y el comparador (ver `verificacion.md`).

