"""Dibuja los avatares derivados de una lista de PeerID.

Sirve para dos cosas distintas:

1. **Mirar.** El avatar derivado sólo vale si los rostros de identidades distintas se
   distinguen de un vistazo. Eso no lo demuestra un test de unidad — hay que verlo.
2. **Comparar.** Los mismos PeerID renderizados en el teléfono deben dar la misma imagen;
   si no, `AvatarIdentity.kt` e `identity.py` han divergido (regla 4.2 del kit).

Uso:

    python3 scripts/render_identity_gallery.py salida.png [peer-id ...]

Sin PeerID toma los de ejemplo de abajo, que son identidades reales del proyecto.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "python"))

from PIL import Image, ImageDraw  # noqa: E402

from avatar_renderer import FlatVectorAvatarRenderer  # noqa: E402
from identity import attributes_for  # noqa: E402

# Identidades reales del proyecto, para que la galería no sea de PeerID inventados:
# el nodo de Nyx en São Paulo, el nodo de Krypta, el segundo nodo de Krypta en Windows,
# y varias derivadas para llenar la rejilla.
EJEMPLOS = [
    "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3",
    "12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5",
    "12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm",
    "12D3KooWNzL3eMiVfs6zfGfXD6pvQz8T6SCg5BR73tF57EcAvvFm",
    "12D3KooWH8AqHT5Z78AGHBKkmC8DNis5NjYqz7Zgqth4BGY46sXR",
    "12D3KooWDYUpyKimQ8eTFeQZaBngiL1UWafuWw75UWVhwfR3AwQX",
    "12D3KooWF3FGy6STm1df1Et4RzKGGQEM2YM4fhjpVxvkikNKBVcq",
    "12D3KooWpruebaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1",
]

# Dos PeerID que sólo difieren en el último carácter. Van juntos a propósito: es el error
# real que este avatar tiene que cazar — pegar el identificador equivocado — y la galería
# debe dejar claro de un vistazo que producen caras distintas.
CASI_IGUALES = [
    "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY3",
    "12D3KooWAyAVyXAdPnj4NScf2PU4iV45j1skg1B2u9gswUpfziY4",
]

TILE = 224
PAD = 8


def render_grid(peer_ids: list[str], columns: int = 4) -> Image.Image:
    renderer = FlatVectorAvatarRenderer(image_size=TILE)
    rows = (len(peer_ids) + columns - 1) // columns
    width = columns * TILE + (columns + 1) * PAD
    height = rows * (TILE + 22) + (rows + 1) * PAD
    sheet = Image.new("RGB", (width, height), (250, 249, 247))
    draw = ImageDraw.Draw(sheet)

    for index, peer_id in enumerate(peer_ids):
        column, row = index % columns, index // columns
        x = PAD + column * (TILE + PAD)
        y = PAD + row * (TILE + 22 + PAD)
        sheet.paste(renderer.render(attributes_for(peer_id)), (x, y))
        # Las últimas 8 letras bastan para identificar la fila sin llenar la imagen.
        draw.text((x + 2, y + TILE + 5), f"…{peer_id[-8:]}", fill=(60, 55, 60))

    return sheet


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    destination = Path(sys.argv[1])
    peer_ids = sys.argv[2:] or (EJEMPLOS + CASI_IGUALES)
    render_grid(peer_ids).save(destination)
    print(f"escritos {len(peer_ids)} avatares derivados en {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
