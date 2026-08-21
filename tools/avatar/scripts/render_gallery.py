#!/usr/bin/env python3
"""Galería de revisión visual del dibujo de avatares (ADR 0012).

Genera doce personas deliberadamente distintas —tono de piel, pelo, vello
facial, gafas, prenda— para juzgar el estilo de un vistazo. Es la herramienta
de revisión del trazado: cualquier ajuste de coordenadas debe comprobarse aquí
antes de darlo por bueno.

Uso:
  avatar-face-gallery  →  python scripts/render_gallery.py --output-dir artifacts/render-demo
"""

from __future__ import annotations

import sys
from pathlib import Path as _KitPath

# El kit es plano: los módulos del dibujo viven en ../python.
sys.path.insert(0, str(_KitPath(__file__).resolve().parent.parent / "python"))

import argparse
from pathlib import Path

from PIL import Image

from attributes import DEFAULT_ATTRIBUTES, AvatarAttributes
from avatar_renderer import FlatVectorAvatarRenderer

PEOPLE: tuple[dict[str, str], ...] = (
    dict(face_shape="oval", skin_tone="light", hair_style="side-parted", hair_color="brown",
         eye_color="blue", eye_shape="almond", expression="friendly", glasses="rectangular",
         clothing="collared shirt", clothing_color="white", background="sky"),
    dict(face_shape="round", skin_tone="deep", hair_style="afro", hair_color="black",
         eye_color="brown", eye_shape="round", expression="happy", earrings="hoops",
         clothing="crew neck", clothing_color="mustard", background="coral"),
    dict(face_shape="square", skin_tone="tan", hair_style="buzz", hair_color="black",
         eye_color="hazel", eye_shape="narrow", expression="serious", facial_hair="full beard",
         clothing="turtleneck", clothing_color="charcoal", background="slate"),
    dict(face_shape="heart", skin_tone="porcelain", hair_style="long", hair_color="red",
         eye_color="green", eye_shape="wide", expression="smiling", freckles="heavy",
         clothing="v-neck", clothing_color="green", background="mint"),
    dict(face_shape="long", skin_tone="olive", hair_style="wavy", hair_color="gray",
         eye_color="gray", eye_shape="hooded", expression="calm", glasses="round",
         clothing="hoodie", clothing_color="blue", background="sand"),
    dict(face_shape="diamond", skin_tone="brown", hair_style="bun", hair_color="black",
         eye_color="amber", eye_shape="almond", expression="confident", earrings="studs",
         clothing="collared shirt", clothing_color="red", background="teal"),
    dict(face_shape="oval", skin_tone="beige", hair_style="curly", hair_color="auburn",
         eye_color="brown", eye_shape="round", expression="smiling", brow_style="thick",
         clothing="crew neck", clothing_color="purple", background="rose"),
    dict(face_shape="square", skin_tone="ebony", hair_style="undercut", hair_color="black",
         eye_color="brown", eye_shape="almond", expression="confident", facial_hair="goatee",
         glasses="sunglasses", clothing="hoodie", clothing_color="charcoal",
         background="lavender"),
    dict(face_shape="round", skin_tone="golden", hair_style="ponytail", hair_color="pink",
         eye_color="green", eye_shape="wide", expression="happy", earrings="studs",
         clothing="v-neck", clothing_color="white", background="sky"),
    dict(face_shape="oval", skin_tone="light", hair_style="bald", hair_color="brown",
         eye_color="blue", eye_shape="hooded", expression="calm", facial_hair="short beard",
         glasses="square", clothing="turtleneck", clothing_color="green", background="sand"),
    dict(face_shape="heart", skin_tone="beige", hair_style="bob", hair_color="silver",
         eye_color="gray", eye_shape="almond", expression="friendly", brow_style="arched",
         clothing="crew neck", clothing_color="red", background="teal"),
    dict(face_shape="long", skin_tone="brown", hair_style="short", hair_color="blue",
         eye_color="amber", eye_shape="narrow", expression="serious", facial_hair="stubble",
         nose_style="wide", clothing="collared shirt", clothing_color="blue", background="coral"),
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/render-demo"))
    parser.add_argument("--image-size", type=int, default=256)
    parser.add_argument("--columns", type=int, default=4)
    parser.add_argument(
        "--dump-specs",
        type=Path,
        default=None,
        help="escribe las especificaciones en JSON; el APK las lee como asset para "
        "dibujar exactamente la misma galería y poder comparar ambos trazados",
    )
    args = parser.parse_args()

    if args.dump_specs is not None:
        import json

        args.dump_specs.parent.mkdir(parents=True, exist_ok=True)
        args.dump_specs.write_text(
            json.dumps(
                [
                    {**DEFAULT_ATTRIBUTES, **spec, "identifier": f"persona-{index + 1:02d}"}
                    for index, spec in enumerate(PEOPLE)
                ],
                indent=2,
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"specs_ok personas={len(PEOPLE)} salida={args.dump_specs}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    renderer = FlatVectorAvatarRenderer(image_size=args.image_size)
    rows = (len(PEOPLE) + args.columns - 1) // args.columns
    sheet = Image.new(
        "RGB", (args.columns * args.image_size, rows * args.image_size), "#FFFFFF"
    )
    for index, spec in enumerate(PEOPLE):
        attributes = AvatarAttributes(**{**DEFAULT_ATTRIBUTES, **spec})
        image = renderer.render(attributes)
        image.save(args.output_dir / f"persona-{index + 1:02d}.png")
        sheet.paste(
            image,
            ((index % args.columns) * args.image_size,
             (index // args.columns) * args.image_size),
        )
    gallery = args.output_dir / "galeria.png"
    sheet.save(gallery)
    print(f"galeria_ok personas={len(PEOPLE)} salida={gallery}")


if __name__ == "__main__":
    main()
