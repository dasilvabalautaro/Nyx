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
    attributes = dict(DEFAULT_ATTRIBUTES)
    attributes["accessory"] = ACCESSORY_FIXED

    for index, (name, vocabulary) in enumerate(VOCABULARIES):
        offset = index * BYTES_PER_ATTRIBUTE
        value = int.from_bytes(raw[offset:offset + BYTES_PER_ATTRIBUTE], "big")
        attributes[name] = _choose(vocabulary, value)

    # AvatarAttributes valida el vocabulario en __post_init__, así que un mapeo mal hecho
    # revienta aquí y no más tarde, dibujando un rasgo por defecto en silencio.
    return AvatarAttributes(**attributes)
