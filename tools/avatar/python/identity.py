"""Avatar derivado del PeerID — gemelo de `AvatarIdentity.kt`.

Existe por la regla 4.2 del kit: el trazado y las decisiones viven en dos lenguajes y, si
uno cambia sin el otro, las imágenes divergen entre plataformas. Aquí eso importa más de lo
normal, porque el avatar derivado **es** la seña de identidad de una persona: si Kotlin y
Python no coinciden, la galería de referencia deja de decir la verdad sobre lo que ve un
teléfono.

El razonamiento de diseño (por qué se deriva del PeerID, qué entropía tiene de verdad y por
qué esto NO sustituye al número de seguridad) está en el KDoc de `AvatarIdentity.kt`, que es
la copia autoritativa. No lo dupliques aquí: duplicarlo es cómo empiezan a divergir.
"""

import hashlib

from attributes import DEFAULT_ATTRIBUTES, AvatarAttributes
from palette import HAIR_COLORS, SKIN_TONES

DOMAIN = b"nyx-avatar-v1"
BYTES_PER_ATTRIBUTE = 4
ACCESSORY_FIXED = "none"

# Mismo orden y MISMOS PESOS que VOCABULARIES en AvatarIdentity.kt. El orden y los pesos son
# parte del contrato: cambiar cualquiera de los dos le cambia el rostro a todo el mundo.
#
# Los pesos salieron de mirar la primera galería derivada con muestreo uniforme: 8 de cada 10
# con gafas, pelo azul o verde por todas partes, barbas de colores. Un catálogo curado no es
# una distribución uniforme. El razonamiento completo, en el KDoc de AvatarIdentity.kt.


def _uniform(*values):
    return [(value, 1) for value in values]


VOCABULARIES = [
    ("expression", _uniform("smiling", "calm", "happy", "confident", "serious", "friendly")),
    ("face_shape", _uniform("round", "oval", "square", "heart", "long", "diamond")),
    ("skin_tone", _uniform("porcelain", "light", "beige", "golden", "olive", "tan",
                           "brown", "deep", "ebony")),
    ("hair_style", _uniform("short", "buzz", "curly", "wavy", "side-parted", "bob",
                            "long", "ponytail", "bun", "afro", "undercut", "bald")),
    ("hair_color", [("black", 6), ("brown", 6), ("auburn", 4), ("blonde", 5), ("gray", 3),
                    ("red", 3), ("silver", 3), ("blue", 1), ("pink", 1), ("green", 1)]),
    ("eye_color", _uniform("brown", "blue", "green", "gray", "hazel", "amber")),
    ("eye_shape", _uniform("almond", "round", "narrow", "wide", "hooded")),
    ("background", _uniform("coral", "mint", "sky", "lavender", "sand", "slate", "rose", "teal")),
    ("brow_style", _uniform("natural", "arched", "thick", "thin", "angled")),
    ("nose_style", _uniform("straight", "small", "button", "wide", "pointed")),
    ("facial_hair", [("none", 10), ("stubble", 3), ("mustache", 1), ("goatee", 2),
                     ("short beard", 3), ("full beard", 2)]),
    ("glasses", [("none", 10), ("round", 3), ("square", 3), ("sunglasses", 1),
                 ("rectangular", 3)]),
    ("earrings", [("none", 6), ("studs", 3), ("hoops", 2)]),
    ("freckles", [("none", 7), ("light", 3), ("heavy", 1)]),
    ("clothing", _uniform("crew neck", "v-neck", "collared shirt", "hoodie", "turtleneck")),
    ("clothing_color", _uniform("white", "charcoal", "red", "blue", "green", "mustard", "purple")),
]


def _choose(vocabulary, value: int) -> str:
    """Selección por peso acumulado; con todos los pesos a 1 equivale a un módulo normal."""
    total = sum(weight for _, weight in vocabulary)
    remaining = value % total
    for name, weight in vocabulary:
        remaining -= weight
        if remaining < 0:
            return name
    return vocabulary[-1][0]  # inalcanzable


# Peinados que el trazado dibuja largos: con uno de estos se apaga el vello facial.
# Regla de coherencia visual y también normativa, así que va a la vista. Sólo afecta al avatar
# derivado; quien quiera barba con melena la escribe.
LONG_HAIR = {"bob", "long", "ponytail", "bun"}

# Contraste pelo/piel. Dos umbrales, y el motivo está en el KDoc de AvatarIdentity.kt: el suelo
# general va bajo a propósito para no perder `ebony`+`black` (Δ22), que se lee bien; el exigente
# sólo se aplica cuando piel y pelo son los dos pálidos, que es donde no queda borde.
PALE_LUMA = 150
MIN_DELTA = 20
MIN_PALE_DELTA = 25


def _luma(hex_color: str) -> int:
    """Brillo percibido, en enteros: la misma cuenta en Kotlin y aquí da el mismo resultado."""
    value = int(hex_color.lstrip("#"), 16)
    red, green, blue = (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF
    return (299 * red + 587 * green + 114 * blue) // 1000


def _hair_colors_for(skin_tone: str, vocabulary):
    """Colores de pelo utilizables sobre `skin_tone`; la lista entera si el filtro la vacía."""
    skin = _luma(SKIN_TONES[skin_tone])
    usable = []
    for name, weight in vocabulary:
        hair = _luma(HAIR_COLORS[name])
        needed = MIN_PALE_DELTA if (skin > PALE_LUMA and hair > PALE_LUMA) else MIN_DELTA
        if abs(hair - skin) >= needed:
            usable.append((name, weight))
    return usable or vocabulary


def _stream(peer_id: str, length: int) -> bytes:
    """SHA-256(DOMAIN || contador || peerId) por bloques, igual que el lado Kotlin."""
    out = bytearray()
    counter = 0
    while len(out) < length:
        digest = hashlib.sha256(
            DOMAIN + bytes([counter & 0xFF]) + peer_id.encode("utf-8")
        ).digest()
        out.extend(digest)
        counter += 1
    return bytes(out[:length])


def attributes_for(peer_id: str) -> AvatarAttributes:
    """Atributos del avatar que le corresponden a `peer_id`. Determinista y sin estado."""
    if not peer_id.strip():
        raise ValueError("el PeerID no puede estar vacío")

    raw = _stream(peer_id, len(VOCABULARIES) * BYTES_PER_ATTRIBUTE)
    values = [
        int.from_bytes(raw[i * BYTES_PER_ATTRIBUTE:(i + 1) * BYTES_PER_ATTRIBUTE], "big")
        for i in range(len(VOCABULARIES))
    ]

    attributes = dict(DEFAULT_ATTRIBUTES)
    attributes["accessory"] = ACCESSORY_FIXED

    # El orden importa: el pelo se elige sabiendo ya la piel, y el vello sabiendo el peinado.
    for index, (name, vocabulary) in enumerate(VOCABULARIES):
        if name == "hair_color":
            vocabulary = _hair_colors_for(attributes["skin_tone"], vocabulary)
        attributes[name] = _choose(vocabulary, values[index])

    if attributes["hair_style"] in LONG_HAIR:
        attributes["facial_hair"] = "none"

    # AvatarAttributes valida el vocabulario en __post_init__, así que un mapeo mal hecho
    # revienta aquí y no más tarde, dibujando un rasgo por defecto en silencio.
    return AvatarAttributes(**attributes)
