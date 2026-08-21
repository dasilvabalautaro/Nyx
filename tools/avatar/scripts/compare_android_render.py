#!/usr/bin/env python3
"""Compara el trazado nativo de Android con el de Python (ADR 0012).

Los dos dibujos comparten la tabla de coordenadas de 256 px, pero los
rasterizadores no son el mismo (Pillow frente a Skia), así que la igualdad
exacta píxel a píxel no es alcanzable ni necesaria. Lo que se exige es que la
diferencia media sea pequeña y que no haya ningún avatar claramente distinto:
si un rasgo se dibuja en otro sitio, la diferencia se dispara.

Uso:
  python scripts/compare_android_render.py --android <dir> --python artifacts/render-demo
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image

# Umbrales sobre la diferencia media de píxel (0-255). El margen cubre las
# diferencias de antialiasing entre rasterizadores; un rasgo desplazado o un
# color equivocado quedan muy por encima.
MEAN_LIMIT = 6.0
WORST_LIMIT = 10.0


def load(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--android", type=Path, required=True)
    parser.add_argument("--python", type=Path, default=Path("artifacts/render-demo"))
    parser.add_argument("--diff-dir", type=Path, default=None)
    args = parser.parse_args()

    names = sorted(p.name for p in args.android.glob("persona-*.png"))
    if not names:
        raise SystemExit(f"No hay imágenes de Android en {args.android}")
    if args.diff_dir is not None:
        args.diff_dir.mkdir(parents=True, exist_ok=True)

    differences: list[tuple[str, float]] = []
    for name in names:
        android = load(args.android / name)
        reference = load(args.python / name)
        if android.shape != reference.shape:
            raise SystemExit(f"{name}: tamaños distintos {android.shape} vs {reference.shape}")
        delta = np.abs(android - reference)
        differences.append((name, float(delta.mean())))
        if args.diff_dir is not None:
            Image.fromarray(
                np.clip(delta * 4, 0, 255).astype(np.uint8)
            ).save(args.diff_dir / name)

    mean = float(np.mean([value for _, value in differences]))
    worst_name, worst = max(differences, key=lambda item: item[1])
    for name, value in differences:
        print(f"{name}  diferencia_media={value:.2f}/255 ({value / 255 * 100:.2f} %)")
    print(f"\nmedia={mean:.2f}/255 ({mean / 255 * 100:.2f} %)  límite={MEAN_LIMIT}")
    print(f"peor={worst_name} {worst:.2f}/255 ({worst / 255 * 100:.2f} %)  límite={WORST_LIMIT}")
    if mean > MEAN_LIMIT or worst > WORST_LIMIT:
        raise SystemExit("comparación_fallida: el trazado nativo difiere del de Python")
    print("comparacion_ok: los dos trazados coinciden dentro del margen de rasterizado")


if __name__ == "__main__":
    main()
