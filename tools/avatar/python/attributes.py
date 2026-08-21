from __future__ import annotations

import re
from dataclasses import dataclass, fields

from prompt_models import AvatarPrompt

# Vocabulario del avatar. Está pensado como sustituto de una foto de perfil, así
# que la combinatoria debe ser lo bastante amplia para que una persona se
# reconozca en el resultado. El orden de cada tupla es canónico y no debe
# reordenarse sin crear una versión nueva del vocabulario.
#
# Los nueve primeros atributos son los del generador procedimental v3 y siguen
# apareciendo en los manifiestos de dataset ya congelados (`LEGACY_ATTRIBUTES`);
# el resto se añadió con el ADR 0012 y tiene valor por defecto.
ATTRIBUTE_VOCABULARIES: dict[str, tuple[str, ...]] = {
    "expression": ("smiling", "calm", "happy", "confident", "serious", "friendly"),
    "face_shape": ("round", "oval", "square", "heart", "long", "diamond"),
    "skin_tone": (
        "porcelain",
        "light",
        "beige",
        "golden",
        "olive",
        "tan",
        "brown",
        "deep",
        "ebony",
    ),
    "hair_style": (
        "short",
        "buzz",
        "curly",
        "wavy",
        "side-parted",
        "bob",
        "long",
        "ponytail",
        "bun",
        "afro",
        "undercut",
        "bald",
    ),
    "hair_color": (
        "black",
        "brown",
        "auburn",
        "blonde",
        "blue",
        "pink",
        "gray",
        "red",
        "silver",
        "green",
    ),
    "eye_color": ("brown", "blue", "green", "gray", "hazel", "amber"),
    "eye_shape": ("almond", "round", "narrow", "wide", "hooded"),
    "accessory": (
        "none",
        "round glasses",
        "square glasses",
        "sunglasses",
        "earrings",
        "freckles",
    ),
    "background": ("coral", "mint", "sky", "lavender", "sand", "slate", "rose", "teal"),
    # Añadidos por el ADR 0012.
    "brow_style": ("natural", "arched", "thick", "thin", "angled"),
    "nose_style": ("straight", "small", "button", "wide", "pointed"),
    "facial_hair": ("none", "stubble", "mustache", "goatee", "short beard", "full beard"),
    "glasses": ("none", "round", "square", "sunglasses", "rectangular"),
    "earrings": ("none", "studs", "hoops"),
    "freckles": ("none", "light", "heavy"),
    "clothing": ("crew neck", "v-neck", "collared shirt", "hoodie", "turtleneck"),
    "clothing_color": ("white", "charcoal", "red", "blue", "green", "mustard", "purple"),
}
ATTRIBUTE_ORDER: tuple[str, ...] = tuple(ATTRIBUTE_VOCABULARIES)

# Atributos presentes en los manifiestos de dataset congelados (v2.x y la
# release de destilación); los consumidores de esos manifiestos deben usar
# esta lista, no `ATTRIBUTE_ORDER`.
LEGACY_ATTRIBUTES: tuple[str, ...] = (
    "expression",
    "face_shape",
    "skin_tone",
    "hair_style",
    "hair_color",
    "eye_color",
    "eye_shape",
    "accessory",
    "background",
)
ATTRIBUTE_CARDINALITIES: tuple[int, ...] = tuple(
    len(ATTRIBUTE_VOCABULARIES[name]) for name in LEGACY_ATTRIBUTES
)

DEFAULT_ATTRIBUTES: dict[str, str] = {
    "expression": "calm",
    "face_shape": "oval",
    "skin_tone": "light",
    "hair_style": "short",
    "hair_color": "brown",
    "eye_color": "brown",
    "eye_shape": "almond",
    "accessory": "none",
    "background": "sky",
    "brow_style": "natural",
    "nose_style": "straight",
    "facial_hair": "none",
    "glasses": "none",
    "earrings": "none",
    "freckles": "none",
    "clothing": "crew neck",
    "clothing_color": "blue",
}

_CAPTION_PREFIX = "flat vector avatar face of an adult, "

_FACE_PATTERN = re.compile(r"\b(round|oval|square|heart|long|diamond)\s+face\b")
_SKIN_PATTERN = re.compile(
    r"\b(porcelain|light|beige|golden|olive|tan|brown|deep|ebony)\s+skin\b"
)
_HAIR_STYLES_ALT = "short|buzz|curly|wavy|side-parted|bob|long|ponytail|bun|afro|undercut|bald"
_HAIR_COLORS_ALT = "black|brown|auburn|blonde|blue|pink|gray|red|silver|green"
_HAIR_PATTERN = re.compile(
    rf"\b(?:({_HAIR_STYLES_ALT})\s+)?({_HAIR_COLORS_ALT})\s+hair\b"
)
_HAIR_STYLE_PATTERN = re.compile(rf"\b({_HAIR_STYLES_ALT})\s+hair\b")
_EYES_PATTERN = re.compile(
    r"\b(?:(brown|blue|green|gray|hazel|amber)\s+)?"
    r"(?:(almond|round|narrow|wide|hooded)\s+)?eyes\b"
)
_EXPRESSION_PATTERN = re.compile(r"\b(smiling|calm|happy|confident|serious|friendly)\b")
_BACKGROUND_PATTERN = re.compile(
    r"\b(coral|mint|sky|lavender|sand|slate|rose|teal)\s+background\b"
)
_BROW_PATTERN = re.compile(r"\b(natural|arched|thick|thin|angled)\s+(?:eye)?brows\b")
_NOSE_PATTERN = re.compile(r"\b(straight|small|button|wide|pointed)\s+nose\b")
_CLOTHING_PATTERN = re.compile(r"\b(crew neck|v-neck|collared shirt|hoodie|turtleneck)\b")
_CLOTHING_COLOR_PATTERN = re.compile(
    r"\b(white|charcoal|red|blue|green|mustard|purple)\s+(?:shirt|top|hoodie|sweater)\b"
)
_GLASSES_PATTERN = re.compile(r"\b(round|square|rectangular)\s+glasses\b")


class CaptionParseError(ValueError):
    """El caption no sigue la plantilla del generador procedimental v3."""


@dataclass(frozen=True, slots=True)
class AvatarAttributes:
    """Atributos estructurados de un rostro adulto; entrada del dibujo (ADR 0012)."""

    expression: str
    face_shape: str
    skin_tone: str
    hair_style: str
    hair_color: str
    eye_color: str
    eye_shape: str
    accessory: str
    background: str
    brow_style: str = "natural"
    nose_style: str = "straight"
    facial_hair: str = "none"
    glasses: str = "none"
    earrings: str = "none"
    freckles: str = "none"
    clothing: str = "crew neck"
    clothing_color: str = "blue"

    def __post_init__(self) -> None:
        for name in ATTRIBUTE_ORDER:
            value = getattr(self, name)
            if value not in ATTRIBUTE_VOCABULARIES[name]:
                raise ValueError(f"Valor de {name} fuera del vocabulario: {value!r}.")

    def indices(self) -> tuple[int, ...]:
        """Índices de los atributos heredados, en el orden de `LEGACY_ATTRIBUTES`."""
        return tuple(
            ATTRIBUTE_VOCABULARIES[name].index(getattr(self, name))
            for name in LEGACY_ATTRIBUTES
        )

    def with_values(self, **changes: str) -> AvatarAttributes:
        current = {field.name: getattr(self, field.name) for field in fields(self)}
        return AvatarAttributes(**{**current, **changes})

    @property
    def effective_glasses(self) -> str:
        """Gafas pedidas de forma explícita o a través del atributo heredado."""
        if self.glasses != "none":
            return self.glasses
        return {
            "round glasses": "round",
            "square glasses": "square",
            "sunglasses": "sunglasses",
        }.get(self.accessory, "none")

    @property
    def effective_earrings(self) -> str:
        if self.earrings != "none":
            return self.earrings
        return "studs" if self.accessory == "earrings" else "none"

    @property
    def effective_freckles(self) -> str:
        if self.freckles != "none":
            return self.freckles
        return "light" if self.accessory == "freckles" else "none"

    def caption(self) -> str:
        """Caption con la plantilla del generador v3, compatible hacia atrás."""
        accessory = (
            " without accessories"
            if self.accessory == "none"
            else f" with {self.accessory}"
        )
        return (
            f"flat vector avatar face of an adult, {self.expression} expression, "
            f"{self.face_shape} face, {self.skin_tone} skin tone, "
            f"{self.hair_style} {self.hair_color} hair, {self.eye_color} "
            f"{self.eye_shape} eyes{accessory}, {self.background} background"
        )

    def describe(self) -> str:
        """Descripción completa, incluidos los atributos del ADR 0012."""
        parts = [
            f"adulto de rostro {self.face_shape}",
            f"piel {self.skin_tone}",
            f"pelo {self.hair_style} {self.hair_color}",
            f"ojos {self.eye_shape} {self.eye_color}",
            f"cejas {self.brow_style}",
            f"nariz {self.nose_style}",
            f"expresión {self.expression}",
        ]
        if self.facial_hair != "none":
            parts.append(f"vello facial {self.facial_hair}")
        if self.effective_glasses != "none":
            parts.append(f"gafas {self.effective_glasses}")
        if self.effective_earrings != "none":
            parts.append(f"pendientes {self.effective_earrings}")
        if self.effective_freckles != "none":
            parts.append(f"pecas {self.effective_freckles}")
        parts.append(f"{self.clothing} {self.clothing_color}")
        parts.append(f"fondo {self.background}")
        return ", ".join(parts)


def _strip_suffix(segment: str, suffix: str) -> str:
    if not segment.endswith(suffix):
        raise CaptionParseError(f"Segmento inesperado: {segment!r} (se esperaba …{suffix!r}).")
    return segment[: -len(suffix)]


def parse_caption(caption: str) -> AvatarAttributes:
    """Parser estricto de la plantilla del generador; falla ante cualquier desvío."""
    if not caption.startswith(_CAPTION_PREFIX):
        raise CaptionParseError("El caption no empieza con la plantilla del generador v3.")
    segments = caption[len(_CAPTION_PREFIX) :].split(", ")
    if len(segments) != 6:
        raise CaptionParseError(f"El caption tiene {len(segments)} segmentos; se esperaban 6.")
    hair_words = _strip_suffix(segments[3], " hair").split(" ")
    if len(hair_words) != 2:
        raise CaptionParseError(f"Segmento de pelo inválido: {segments[3]!r}.")
    eyes_segment = segments[4]
    if eyes_segment.endswith(" eyes without accessories"):
        eyes_words = eyes_segment.removesuffix(" eyes without accessories").split(" ")
        accessory = "none"
    elif " eyes with " in eyes_segment:
        eyes_part, accessory = eyes_segment.split(" eyes with ", 1)
        eyes_words = eyes_part.split(" ")
    else:
        raise CaptionParseError(f"Segmento de ojos inválido: {eyes_segment!r}.")
    if len(eyes_words) != 2:
        raise CaptionParseError(f"Segmento de ojos inválido: {eyes_segment!r}.")
    try:
        return AvatarAttributes(
            expression=_strip_suffix(segments[0], " expression"),
            face_shape=_strip_suffix(segments[1], " face"),
            skin_tone=_strip_suffix(segments[2], " skin tone"),
            hair_style=hair_words[0],
            hair_color=hair_words[1],
            eye_color=eyes_words[0],
            eye_shape=eyes_words[1],
            accessory=accessory,
            background=_strip_suffix(segments[5], " background"),
        )
    except ValueError as error:
        raise CaptionParseError(str(error)) from error


def _match_facial_hair(normalized: str) -> str | None:
    for value in ("full beard", "short beard", "stubble", "mustache", "goatee"):
        if value in normalized:
            return value
    if "beard" in normalized:
        return "short beard"
    if "clean shaven" in normalized or "clean-shaven" in normalized:
        return "none"
    return None


def attributes_from_text(text: str) -> AvatarAttributes:
    """Parser tolerante para prompts libres; usa valores neutros para lo ausente.

    Los atributos ambiguos («brown», «round», «light») se resuelven por
    contexto («brown hair», «round face»); un término suelto sin contexto no
    asigna nada. La validación de sólo-adultos (RF-09) ocurre antes, en
    `AvatarPrompt`; usar `attributes_from_prompt` para el flujo del producto.
    """
    normalized = text.lower()
    values = dict(DEFAULT_ATTRIBUTES)
    for accessory in ATTRIBUTE_VOCABULARIES["accessory"][1:]:
        if accessory in normalized:
            values["accessory"] = accessory
            break
    if match := _EXPRESSION_PATTERN.search(normalized):
        values["expression"] = match.group(1)
    if match := _FACE_PATTERN.search(normalized):
        values["face_shape"] = match.group(1)
    if match := _SKIN_PATTERN.search(normalized):
        values["skin_tone"] = match.group(1)
    if match := _HAIR_PATTERN.search(normalized):
        if match.group(1):
            values["hair_style"] = match.group(1)
        values["hair_color"] = match.group(2)
    if match := _HAIR_STYLE_PATTERN.search(normalized):
        values["hair_style"] = match.group(1)
    if "bald" in normalized:
        values["hair_style"] = "bald"
    if match := _EYES_PATTERN.search(normalized):
        if match.group(1):
            values["eye_color"] = match.group(1)
        if match.group(2):
            values["eye_shape"] = match.group(2)
    if match := _BACKGROUND_PATTERN.search(normalized):
        values["background"] = match.group(1)
    if match := _BROW_PATTERN.search(normalized):
        values["brow_style"] = match.group(1)
    if match := _NOSE_PATTERN.search(normalized):
        values["nose_style"] = match.group(1)
    if facial_hair := _match_facial_hair(normalized):
        values["facial_hair"] = facial_hair
    if match := _GLASSES_PATTERN.search(normalized):
        values["glasses"] = match.group(1)
    elif "sunglasses" in normalized:
        values["glasses"] = "sunglasses"
    elif "glasses" in normalized:
        values["glasses"] = "round"
    if "hoops" in normalized:
        values["earrings"] = "hoops"
    elif "studs" in normalized or "earrings" in normalized:
        values["earrings"] = "studs"
    if "freckles" in normalized:
        values["freckles"] = "heavy" if "many freckles" in normalized else "light"
    if match := _CLOTHING_PATTERN.search(normalized):
        values["clothing"] = match.group(1)
    elif "hoodie" in normalized:
        values["clothing"] = "hoodie"
    if match := _CLOTHING_COLOR_PATTERN.search(normalized):
        values["clothing_color"] = match.group(1)
    return AvatarAttributes(**values)


def attributes_from_prompt(prompt: AvatarPrompt) -> AvatarAttributes:
    """Flujo del producto: el prompt ya pasó el contrato y el filtro RF-09."""
    return attributes_from_text(prompt.text)


def vocabulary_size() -> int:
    """Número de avatares distintos que el vocabulario puede describir."""
    total = 1
    for values in ATTRIBUTE_VOCABULARIES.values():
        total *= len(values)
    return total
