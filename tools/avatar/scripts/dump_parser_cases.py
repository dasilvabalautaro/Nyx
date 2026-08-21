#!/usr/bin/env python3
"""Casos de referencia del parser de texto, para comprobar la paridad con Android.

El parser vive en dos lenguajes (Python y Kotlin) y ambos deben interpretar las
mismas frases igual. Este script congela un conjunto de frases con los atributos
que produce la implementación de Python; el APK las lee como asset, las parsea
con la suya e informa de cualquier diferencia (ADR 0012).
"""

from __future__ import annotations

import sys
from pathlib import Path as _KitPath

# El kit es plano: los módulos del dibujo viven en ../python.
sys.path.insert(0, str(_KitPath(__file__).resolve().parent.parent / "python"))

import argparse
import json
from pathlib import Path

from attributes import ATTRIBUTE_ORDER, attributes_from_text

PHRASES: tuple[str, ...] = (
    "smiling adult with curly pink hair and round glasses",
    "serious adult with deep skin and full beard and thick brows",
    "confident adult, square face, olive skin, bald, sunglasses, charcoal hoodie",
    "friendly adult with long red hair, green wide eyes, many freckles, mint background",
    "calm adult with silver bob hair, gray eyes, hoops and collared shirt",
    "happy adult with afro black hair, brown round eyes, mustard sweater",
    "adult with wavy blonde hair, hazel eyes, arched brows, pointed nose",
    "adult with side-parted blue hair, amber narrow eyes, goatee, teal background",
    "an adult",
    "adult with tan skin, buzz black hair, stubble, rectangular glasses, turtleneck",
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    cases = [
        {
            "text": text,
            "expected": {
                name: getattr(attributes_from_text(text), name) for name in ATTRIBUTE_ORDER
            },
        }
        for text in PHRASES
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(cases, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"parser_cases_ok casos={len(cases)} salida={args.output}")


if __name__ == "__main__":
    main()
