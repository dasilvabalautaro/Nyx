from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

# Términos que solicitan o sugieren un avatar de una persona menor de edad
# (RF-09). Se comparan sobre el prompt normalizado en minúsculas y sin
# diacríticos, por lo que la lista se mantiene en ASCII.
MINOR_AGE_TERMS: frozenset[str] = frozenset(
    {
        "nino",
        "nina",
        "ninos",
        "ninas",
        "bebe",
        "bebes",
        "infante",
        "infantes",
        "infantil",
        "menor",
        "menores",
        "adolescente",
        "adolescentes",
        "preadolescente",
        "preadolescentes",
        "child",
        "children",
        "kid",
        "kids",
        "baby",
        "babies",
        "toddler",
        "toddlers",
        "infant",
        "infants",
        "teen",
        "teens",
        "teenager",
        "teenagers",
        "teenage",
        "preteen",
        "preteens",
        "underage",
        "minor",
        "minors",
        "boy",
        "boys",
        "girl",
        "girls",
        "loli",
        "shota",
    }
)

_MINOR_AGE_PATTERN = re.compile(r"\b(\d{1,2})\s*(?:anos|years?|yrs|y/?o)\b")


class InvalidPromptError(ValueError):
    """El prompt no cumple el contrato de generación."""


def _normalize_for_age_filter(text: str) -> str:
    decomposed = unicodedata.normalize("NFD", text.lower())
    return "".join(char for char in decomposed if not unicodedata.combining(char))


def _references_minor(normalized: str) -> bool:
    """Detecta solicitudes de menores por término o por edad explícita < 18."""
    words = set(re.findall(r"[a-z]+", normalized))
    if words & MINOR_AGE_TERMS:
        return True
    return any(int(match.group(1)) < 18 for match in _MINOR_AGE_PATTERN.finditer(normalized))


@dataclass(frozen=True, slots=True)
class AvatarPrompt:
    """Solicitud validada de generación de un rostro de avatar."""

    text: str
    seed: int = 42
    image_size: int = 256

    def __post_init__(self) -> None:
        normalized = self.text.strip()
        if not normalized:
            raise InvalidPromptError("El prompt no puede estar vacío.")
        if len(normalized) > 500:
            raise InvalidPromptError("El prompt no puede superar 500 caracteres.")
        if not 0 <= self.seed <= 2**32 - 1:
            raise InvalidPromptError("La seed debe estar entre 0 y 2^32-1.")
        if self.image_size not in {256, 512}:
            raise InvalidPromptError("El tamaño debe ser 256 o 512 píxeles.")
        if _references_minor(_normalize_for_age_filter(normalized)):
            raise InvalidPromptError(
                "El prompt sugiere un avatar de una persona menor de edad; "
                "sólo se generan rostros de adultos (RF-09)."
            )
        object.__setattr__(self, "text", normalized)


@dataclass(frozen=True, slots=True)
class AndroidDevice:
    """Dispositivo informado por Android Debug Bridge."""

    serial: str
    state: str
    attributes: tuple[tuple[str, str], ...] = ()

    @property
    def ready(self) -> bool:
        return self.state == "device"

    def attribute(self, name: str) -> str | None:
        return dict(self.attributes).get(name)


@dataclass(frozen=True, slots=True)
class AndroidEnvironment:
    """Resultado neutral de inspeccionar ADB y los dispositivos visibles."""

    adb_path: str | None
    adb_version: str | None
    devices: tuple[AndroidDevice, ...]
    error: str | None = None

    @property
    def ready_devices(self) -> tuple[AndroidDevice, ...]:
        return tuple(device for device in self.devices if device.ready)
