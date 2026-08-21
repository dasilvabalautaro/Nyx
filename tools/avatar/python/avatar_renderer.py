from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass

from PIL import Image, ImageDraw

from attributes import AvatarAttributes
from geometry import (
    Point,
    catmull_rom,
    ellipse_points,
    mirror,
)
from palette import (
    BACKGROUNDS,
    CLOTHING_COLORS,
    EYE_COLORS,
    GOLD,
    HAIR_COLORS,
    LINE,
    LIP,
    SCLERA,
    SKIN_TONES,
    TEETH,
    Rgb,
    hex_to_rgb,
    mix,
    shade,
)

# El dibujo se hace a este múltiplo del tamaño pedido y se reduce con Lanczos:
# los bordes quedan suavizados sin perder el plano de color.
SUPERSAMPLE = 4

# Anclajes de los rasgos sobre el lienzo de referencia de 256 px. Se mantienen
# fijos aunque cambie la silueta, para que la cara no se descomponga.
EYE_Y = 139.0
EYE_DX = 26.0
BROW_Y = 116.0
NOSE_TOP = 150.0
NOSE_BOTTOM = 170.0
MOUTH_Y = 186.0
CENTER = 128.0

Painter = Callable[[Sequence[Point], Rgb], None]
LinePainter = Callable[[Sequence[Point], Rgb, float], None]
BoxFn = Callable[[float, float, float, float], list[float]]


@dataclass(frozen=True, slots=True)
class FaceShape:
    """Medias anchuras de la silueta a cada altura, más el alto del rostro."""

    temple: float
    cheek: float
    jaw: float
    chin: float
    chin_y: float
    top: float


FACE_SHAPES: dict[str, FaceShape] = {
    "oval": FaceShape(temple=56, cheek=62, jaw=50, chin=24, chin_y=216, top=50),
    "round": FaceShape(temple=62, cheek=69, jaw=64, chin=37, chin_y=205, top=55),
    "square": FaceShape(temple=63, cheek=66, jaw=66, chin=48, chin_y=208, top=52),
    "heart": FaceShape(temple=64, cheek=66, jaw=42, chin=14, chin_y=215, top=52),
    "long": FaceShape(temple=52, cheek=56, jaw=47, chin=23, chin_y=231, top=42),
    "diamond": FaceShape(temple=47, cheek=70, jaw=43, chin=20, chin_y=217, top=55),
}


def face_half_width(shape: FaceShape, y: float) -> float:
    """Media anchura del rostro a la altura `y`, interpolando el contorno."""
    anchors: list[tuple[float, float]] = [
        (shape.top, 0.0),
        (shape.top + 44, shape.temple),
        (132.0, shape.cheek),
        (176.0, shape.jaw),
        (shape.chin_y - 12, shape.chin),
        (shape.chin_y, 0.0),
    ]
    if y <= anchors[1][0]:
        return shape.temple
    for (y0, w0), (y1, w1) in zip(anchors, anchors[1:], strict=False):
        if y0 <= y <= y1:
            return w0 + (w1 - w0) * (y - y0) / (y1 - y0)
    return shape.chin


def face_outline(shape: FaceShape) -> list[Point]:
    """Contorno cerrado y suave del rostro, simétrico respecto al eje central."""
    right: list[Point] = [
        (CENTER, shape.top),
        (CENTER + shape.temple * 0.72, shape.top + 12),
        (CENTER + shape.temple, shape.top + 44),
        (CENTER + shape.cheek, 132.0),
        (CENTER + shape.jaw, 176.0),
        (CENTER + shape.chin, shape.chin_y - 12),
        (CENTER, shape.chin_y),
    ]
    return catmull_rom([*right, *mirror(right)[1:-1]], samples=10)


def _hair_cap(
    shape: FaceShape, volume: float, side_y: float, hairline: float, peak: float = 0.0
) -> list[Point]:
    """Casquete de pelo: sube por las sienes, corona la cabeza y baja a la frente.

    El contorno se recorre en un único sentido —lateral izquierdo hacia arriba,
    coronilla, lateral derecho hacia abajo y línea del pelo de vuelta— porque
    un polígono que salta de un lado a otro se cierra sobre sí mismo y abre una
    muesca en la coronilla.
    """
    width = max(shape.temple, face_half_width(shape, side_y) * 0.94) + 5
    # La coronilla es un arco elíptico real: un polígono de puntos sueltos
    # producía una silueta de caja en vez de pelo.
    crown = ellipse_points(
        (CENTER, shape.top + 30), width, 30 + volume + peak, 180, 360, steps=18
    )
    points: list[Point] = [
        (CENTER - width + 2, side_y),
        (CENTER - width - 1, side_y - 40),
        *crown,
        (CENTER + width + 1, side_y - 40),
        (CENTER + width - 2, side_y),
        (CENTER + width - 12, side_y - 4),
        (CENTER + width - 15, hairline + 14),
        (CENTER + width * 0.62, hairline),
        (CENTER, hairline - 7),
        (CENTER - width * 0.62, hairline),
        (CENTER - width + 15, hairline + 14),
        (CENTER - width + 12, side_y - 4),
    ]
    return catmull_rom(points, samples=8)


def _scalloped_crown(
    shape: FaceShape, width: float, volume: float, lobes: int, depth: float
) -> list[Point]:
    """Arco de coronilla con lóbulos: da rizo sin recortar círculos sueltos.

    Pocos lóbulos y anchos: muchos lóbulos estrechos hacen que el spline
    sobrepase en cada alternancia y el rizo se convierta en una corona de picos,
    que es como se veía a tamaño de pantalla.
    """
    base = ellipse_points((CENTER, shape.top + 30), width, 30 + volume, 180, 360, steps=lobes * 2)
    scalloped: list[Point] = []
    for index, (x, y) in enumerate(base):
        factor = 1.0 + (depth if index % 2 else -depth * 0.5)
        scalloped.append(
            (CENTER + (x - CENTER) * factor, (shape.top + 30) + (y - (shape.top + 30)) * factor)
        )
    return scalloped


def _draw_hair_back(
    fill: Painter, shape: FaceShape, attributes: AvatarAttributes, hair: Rgb
) -> None:
    """Masa de pelo por detrás de la cabeza; sólo la tienen los estilos largos."""
    style = attributes.hair_style
    dark = shade(hair, -0.18)
    right: list[Point]
    if style == "long":
        right = [
            (CENTER + 4, shape.top - 6),
            (CENTER + shape.temple + 18, shape.top + 40),
            (CENTER + shape.cheek + 22, 150.0),
            (CENTER + shape.cheek + 16, 232.0),
            (CENTER + 40, 244.0),
            (CENTER, 240.0),
        ]
        fill(catmull_rom([*right, *mirror(right)[1:-1]], samples=10), dark)
    elif style == "bob":
        right = [
            (CENTER + 4, shape.top - 4),
            (CENTER + shape.temple + 14, shape.top + 40),
            (CENTER + shape.cheek + 16, 148.0),
            (CENTER + shape.jaw + 16, 196.0),
            (CENTER + 34, 208.0),
            (CENTER, 210.0),
        ]
        fill(catmull_rom([*right, *mirror(right)[1:-1]], samples=10), dark)
    elif style == "afro":
        base = ellipse_points(
            (CENTER, shape.top + 36), shape.temple + 26, shape.temple + 29, 0, 360, steps=26
        )
        fill(
            catmull_rom(
                [
                    (
                        CENTER + (x - CENTER) * (1.06 if index % 2 else 0.98),
                        (shape.top + 36) + (y - (shape.top + 36)) * (1.06 if index % 2 else 0.98),
                    )
                    for index, (x, y) in enumerate(base)
                ],
                samples=4,
            ),
            dark,
        )
    elif style == "ponytail":
        right = [
            (CENTER + shape.temple, 96.0),
            (CENTER + shape.temple + 34, 118.0),
            (CENTER + shape.temple + 40, 178.0),
            (CENTER + shape.temple + 18, 206.0),
            (CENTER + shape.temple + 4, 168.0),
        ]
        fill(catmull_rom(right, samples=10), dark)
    elif style == "bun":
        fill(
            catmull_rom(
                [
                    (CENTER - 27, shape.top - 18),
                    (CENTER, shape.top - 40),
                    (CENTER + 27, shape.top - 18),
                    (CENTER, shape.top + 4),
                ],
                samples=12,
            ),
            dark,
        )


def _draw_scalp_highlight(fill: Painter, shape: FaceShape, skin: Rgb) -> None:
    """Brillo de coronilla: sin él, una cabeza rapada parece una frente enorme."""
    fill(
        catmull_rom(
            [
                (CENTER - 30, shape.top + 26),
                (CENTER - 6, shape.top + 9),
                (CENTER + 22, shape.top + 20),
                (CENTER - 2, shape.top + 32),
            ],
            samples=10,
        ),
        shade(skin, 0.12),
    )


def _draw_hair_front(
    fill: Painter, shape: FaceShape, attributes: AvatarAttributes, hair: Rgb
) -> None:
    style = attributes.hair_style
    light = shade(hair, 0.16)
    if style == "bald":
        return
    if style == "buzz":
        fill(_hair_cap(shape, volume=2, side_y=126, hairline=100), hair)
        return
    if style == "undercut":
        fill(_hair_cap(shape, volume=15, side_y=104, hairline=96), hair)
        return
    if style == "curly":
        width = max(shape.temple, face_half_width(shape, 142.0) * 0.94) + 5
        crown = _scalloped_crown(shape, width, 13, lobes=6, depth=0.10)
        fill(
            catmull_rom(
                [
                    (CENTER - width + 2, 142.0),
                    (CENTER - width - 1, 108.0),
                    *crown,
                    (CENTER + width + 1, 108.0),
                    (CENTER + width - 2, 142.0),
                    (CENTER + width - 12, 138.0),
                    (CENTER + width - 15, 114.0),
                    (CENTER + width * 0.62, 100.0),
                    (CENTER, 93.0),
                    (CENTER - width * 0.62, 100.0),
                    (CENTER - width + 15, 114.0),
                    (CENTER - width + 12, 138.0),
                ],
                samples=8,
            ),
            hair,
        )
        return
    if style == "afro":
        fill(_hair_cap(shape, volume=10, side_y=140, hairline=104), hair)
        return
    if style == "wavy":
        fill(_hair_cap(shape, volume=18, side_y=150, hairline=100), hair)
        fill(
            catmull_rom(
                [
                    (CENTER - shape.temple + 4, 98.0),
                    (CENTER - 24, 110.0),
                    (CENTER + 8, 94.0),
                    (CENTER + shape.temple - 6, 108.0),
                    (CENTER + shape.temple - 2, 82.0),
                    (CENTER - shape.temple + 6, 78.0),
                ],
                samples=10,
            ),
            shade(hair, 0.09),
        )
        return
    if style == "side-parted":
        fill(_hair_cap(shape, volume=14, side_y=142, hairline=99), hair)
        fill(
            catmull_rom(
                [
                    (CENTER - 30, 92.0),
                    (CENTER + shape.temple - 4, 82.0),
                    (CENTER + shape.temple + 2, 112.0),
                    (CENTER + 14, 96.0),
                ],
                samples=10,
            ),
            light,
        )
        return
    if style in {"long", "bob", "ponytail", "bun"}:
        side = 130.0 if style == "bun" else 150.0
        fill(_hair_cap(shape, volume=13, side_y=side, hairline=98), hair)
        return
    # short
    fill(_hair_cap(shape, volume=12, side_y=138, hairline=100), hair)
    fill(
        catmull_rom(
            [
                (CENTER - 34, 92.0),
                (CENTER + 6, 82.0),
                (CENTER + shape.temple - 8, 96.0),
                (CENTER - 6, 96.0),
            ],
            samples=10,
        ),
        light,
    )


def _facial_hair_color(attributes: AvatarAttributes, hair: Rgb, skin: Rgb) -> Rgb:
    return mix(skin, hair, 0.45) if attributes.facial_hair == "stubble" else shade(hair, -0.04)


def _draw_beard(
    fill: Painter, shape: FaceShape, attributes: AvatarAttributes, hair: Rgb, skin: Rgb
) -> None:
    style = attributes.facial_hair
    if style == "none":
        return
    color = _facial_hair_color(attributes, hair, skin)
    if style in {"stubble", "short beard", "full beard"}:
        top = {"stubble": 150.0, "short beard": 146.0, "full beard": 138.0}[style]
        # Un solo recorrido: mejilla izquierda, mandíbula, mentón, mandíbula
        # derecha y vuelta por el borde interior. Saltar de un lado a otro
        # cerraría el polígono sobre sí mismo y dibujaría una banda recta.
        points: list[Point] = [
            (CENTER - shape.cheek + 4, top),
            (CENTER - shape.jaw - 1, 178.0),
            (CENTER - shape.chin - 8, shape.chin_y - 10),
            (CENTER, shape.chin_y - 1),
            (CENTER + shape.chin + 8, shape.chin_y - 10),
            (CENTER + shape.jaw + 1, 178.0),
            (CENTER + shape.cheek - 4, top),
            (CENTER + shape.cheek - 22, top + 20),
            (CENTER + 31, MOUTH_Y - (16.0 if style == "full beard" else 2.0)),
            (CENTER, MOUTH_Y + 6),
            (CENTER - 31, MOUTH_Y - (16.0 if style == "full beard" else 2.0)),
            (CENTER - shape.cheek + 22, top + 20),
        ]
        fill(catmull_rom(points, samples=8), color)
    elif style == "goatee":
        fill(
            catmull_rom(
                [
                    (CENTER - 21, MOUTH_Y + 4),
                    (CENTER, MOUTH_Y - 2),
                    (CENTER + 21, MOUTH_Y + 4),
                    (CENTER + 15, shape.chin_y - 8),
                    (CENTER, shape.chin_y - 2),
                    (CENTER - 15, shape.chin_y - 8),
                ],
                samples=10,
            ),
            color,
        )

def _draw_mustache(
    fill: Painter, attributes: AvatarAttributes, hair: Rgb, skin: Rgb
) -> None:
    """Capa posterior a la boca: el bigote se apoya sobre el labio superior."""
    # La barba corta y la incipiente también cubren el labio superior: sin
    # bigote quedan con aire de barba de collar.
    if attributes.facial_hair == "none":
        return
    fill(
        catmull_rom(
            [
                (CENTER - 28, MOUTH_Y - 17),
                (CENTER, MOUTH_Y - 22),
                (CENTER + 28, MOUTH_Y - 17),
                (CENTER + 20, MOUTH_Y - 4),
                (CENTER, MOUTH_Y - 10),
                (CENTER - 20, MOUTH_Y - 4),
            ],
            samples=10,
        ),
        _facial_hair_color(attributes, hair, skin),
    )


def _eye_geometry(shape: str) -> tuple[float, float, float, float]:
    """Semiejes de la abertura, radio del iris y descenso del párpado."""
    return {
        "almond": (17.5, 10.0, 8.0, 0.0),
        "round": (15.0, 12.2, 8.6, 0.0),
        "narrow": (18.0, 7.2, 7.6, 0.0),
        "wide": (19.0, 12.5, 9.0, 0.0),
        "hooded": (17.5, 10.5, 8.0, 3.5),
    }[shape]


def _brow_points(style: str, sign: float) -> list[Point]:
    inner = CENTER + sign * 12
    outer = CENTER + sign * 44
    thickness, tilt, peak = {
        "natural": (7.0, 2.0, -2.0),
        "arched": (6.0, 1.0, -7.0),
        "thick": (10.5, 2.0, -2.0),
        "thin": (4.0, 2.0, -2.0),
        "angled": (7.0, 7.0, -1.0),
    }[style]
    top: list[Point] = [
        (inner, BROW_Y + tilt),
        (CENTER + sign * 28, BROW_Y + peak),
        (outer, BROW_Y + 1),
    ]
    bottom: list[Point] = [
        (outer, BROW_Y + 1 + thickness * 0.55),
        (CENTER + sign * 28, BROW_Y + peak + thickness),
        (inner, BROW_Y + tilt + thickness * 0.8),
    ]
    return catmull_rom([*top, *bottom], samples=8)


@dataclass(frozen=True, slots=True)
class FlatVectorAvatarRenderer:
    """Dibuja el avatar desde los atributos, sin modelo neuronal (ADR 0012).

    Las coordenadas están sobre un lienzo de referencia de 256 px y se escalan
    al tamaño pedido; el rostro, el pelo y la barba se describen con splines
    para que las siluetas sean orgánicas y no un montaje de rectángulos.
    """

    image_size: int = 256

    def render(self, attributes: AvatarAttributes) -> Image.Image:
        factor = self.image_size * SUPERSAMPLE / 256
        canvas = round(256 * factor)
        background = hex_to_rgb(BACKGROUNDS[attributes.background])
        image = Image.new("RGB", (canvas, canvas), background)
        draw = ImageDraw.Draw(image)

        def fill(points: Sequence[Point], color: Rgb) -> None:
            draw.polygon([(x * factor, y * factor) for x, y in points], fill=color)

        def stroke(points: Sequence[Point], color: Rgb, width: float) -> None:
            draw.line(
                [(x * factor, y * factor) for x, y in points],
                fill=color,
                width=max(1, round(width * factor)),
                joint="curve",
            )

        def box(x0: float, y0: float, x1: float, y1: float) -> list[float]:
            return [x0 * factor, y0 * factor, x1 * factor, y1 * factor]

        def frame_width(value: float) -> int:
            return max(1, round(value * factor))

        shape = FACE_SHAPES[attributes.face_shape]
        skin = hex_to_rgb(SKIN_TONES[attributes.skin_tone])
        skin_shadow = shade(skin, -0.11)
        hair = hex_to_rgb(HAIR_COLORS[attributes.hair_color])
        cloth = hex_to_rgb(CLOTHING_COLORS[attributes.clothing_color])

        _draw_hair_back(fill, shape, attributes, hair)
        self._draw_body(fill, shape, attributes, cloth, skin, skin_shadow)
        for sign in (-1.0, 1.0):
            cx = CENTER + sign * (face_half_width(shape, 151.0) - 4)
            fill(ellipse_points((cx, 151.0), 9.0, 14.0, 0, 360, steps=24), skin)
        fill(face_outline(shape), skin)
        self._draw_eyes(fill, draw, box, attributes)
        self._draw_nose(fill, attributes, skin, skin_shadow)
        _draw_beard(fill, shape, attributes, hair, skin)
        self._draw_mouth(fill, attributes)
        _draw_mustache(fill, attributes, hair, skin)
        self._draw_freckles(draw, box, attributes, skin_shadow)
        if attributes.hair_style == "bald":
            _draw_scalp_highlight(fill, shape, skin)
        _draw_hair_front(fill, shape, attributes, hair)
        self._draw_glasses(draw, box, stroke, frame_width, shape, attributes)
        self._draw_earrings(draw, box, frame_width, shape, attributes)

        return image.resize((self.image_size, self.image_size), Image.Resampling.LANCZOS)

    @staticmethod
    def _draw_body(
        fill: Painter,
        shape: FaceShape,
        attributes: AvatarAttributes,
        cloth: Rgb,
        skin: Rgb,
        skin_shadow: Rgb,
    ) -> None:
        fill(
            catmull_rom(
                [
                    (CENTER - 24, shape.chin_y - 30),
                    (CENTER - 26, shape.chin_y + 18),
                    (CENTER - 30, shape.chin_y + 34),
                    (CENTER + 30, shape.chin_y + 34),
                    (CENTER + 26, shape.chin_y + 18),
                    (CENTER + 24, shape.chin_y - 30),
                ],
                samples=8,
            ),
            skin,
        )
        # Sombra de contacto: sólo bajo el mentón, que es donde el ojo la espera.
        fill(
            catmull_rom(
                [
                    (CENTER - 25, shape.chin_y - 6),
                    (CENTER, shape.chin_y + 13),
                    (CENTER + 25, shape.chin_y - 6),
                    (CENTER, shape.chin_y + 1),
                ],
                samples=10,
            ),
            skin_shadow,
        )
        garment = attributes.clothing
        top_y = {
            "crew neck": 220.0,
            "v-neck": 216.0,
            "collared shirt": 218.0,
            "hoodie": 212.0,
            "turtleneck": 204.0,
        }[garment]
        shoulders: list[Point] = [
            (CENTER - 118, 258.0),
            (CENTER - 94, 234.0),
            (CENTER - 44, 220.0),
            (CENTER, 216.0),
            (CENTER + 44, 220.0),
            (CENTER + 94, 234.0),
            (CENTER + 118, 258.0),
        ]
        body = [
            (x, max(y, top_y)) for x, y in catmull_rom(shoulders, samples=10, closed=False)
        ]
        fill([*body, (CENTER + 132, 264.0), (CENTER - 132, 264.0)], cloth)
        if garment == "v-neck":
            fill([(CENTER - 22, top_y - 1), (CENTER, top_y + 30), (CENTER + 22, top_y - 1)], skin)
        elif garment == "collared shirt":
            fill(
                [(CENTER - 11, top_y - 4), (CENTER, top_y + 15), (CENTER + 11, top_y - 4)],
                shade(cloth, -0.26),
            )
            highlight = shade(cloth, 0.14)
            for sign in (-1.0, 1.0):
                fill(
                    [
                        (CENTER + sign * 25, top_y - 5),
                        (CENTER + sign * 6, top_y + 1),
                        (CENTER + sign * 12, top_y + 17),
                    ],
                    highlight,
                )
        elif garment == "hoodie":
            fill(
                catmull_rom(
                    [
                        (CENTER - 62, top_y + 6),
                        (CENTER, top_y + 32),
                        (CENTER + 62, top_y + 6),
                        (CENTER, top_y - 8),
                    ],
                    samples=10,
                ),
                shade(cloth, -0.16),
            )
        elif garment == "turtleneck":
            fill(
                [
                    (CENTER - 29, top_y - 10),
                    (CENTER + 29, top_y - 10),
                    (CENTER + 31, top_y + 16),
                    (CENTER - 31, top_y + 16),
                ],
                shade(cloth, 0.13),
            )

    @staticmethod
    def _draw_eyes(
        fill: Painter, draw: ImageDraw.ImageDraw, box: BoxFn, attributes: AvatarAttributes
    ) -> None:
        half_w, half_h, iris_r, hood = _eye_geometry(attributes.eye_shape)
        iris = hex_to_rgb(EYE_COLORS[attributes.eye_color])
        dark = hex_to_rgb(LINE)
        brow = mix(hex_to_rgb(HAIR_COLORS[attributes.hair_color]), dark, 0.25)
        white = hex_to_rgb(SCLERA)
        for sign in (-1.0, 1.0):
            cx = CENTER + sign * EYE_DX
            draw.ellipse(box(cx - half_w, EYE_Y - half_h, cx + half_w, EYE_Y + half_h), fill=white)
            draw.ellipse(box(cx - iris_r, EYE_Y - iris_r, cx + iris_r, EYE_Y + iris_r), fill=iris)
            draw.ellipse(box(cx - 3.9, EYE_Y - 3.9, cx + 3.9, EYE_Y + 3.9), fill=dark)
            draw.ellipse(box(cx + 1.8, EYE_Y - 6.2, cx + 5.6, EYE_Y - 2.4), fill=white)
            # Párpado superior: cierra la forma y da carácter a la mirada.
            fill(
                catmull_rom(
                    [
                        (cx - half_w - 0.5, EYE_Y - half_h * 0.45),
                        (cx, EYE_Y - half_h - 1.0 + hood),
                        (cx + half_w + 0.5, EYE_Y - half_h * 0.45),
                        (cx, EYE_Y - half_h + 1.9 + hood),
                    ],
                    samples=8,
                ),
                dark,
            )
            fill(_brow_points(attributes.brow_style, sign), brow)

    @staticmethod
    def _draw_nose(
        fill: Painter,
        attributes: AvatarAttributes,
        skin_base: Rgb,
        skin_shadow: Rgb,
    ) -> None:
        style = attributes.nose_style
        width, tip = {
            "straight": (8.0, NOSE_BOTTOM),
            "small": (6.5, NOSE_BOTTOM - 5),
            "button": (9.0, NOSE_BOTTOM - 3),
            "wide": (12.0, NOSE_BOTTOM),
            "pointed": (6.5, NOSE_BOTTOM + 1),
        }[style]
        soft = mix(skin_shadow, skin_base, 0.3)
        # La nariz se resuelve como la base: una media luna fina bajo el tabique.
        # Una forma grande en mitad del rostro se lee como una mancha, y una
        # línea suelta como un arañazo.
        depth = 6.0 if style in {"button", "wide"} else 5.0
        fill(
            catmull_rom(
                [
                    (CENTER - width, tip - 5),
                    (CENTER, tip + depth * 0.55),
                    (CENTER + width, tip - 5),
                    (CENTER, tip - depth * 0.35),
                ],
                samples=12,
            ),
            soft,
        )
        if style == "pointed":
            fill(
                catmull_rom(
                    [
                        (CENTER - 2.2, NOSE_TOP + 4),
                        (CENTER + 2.2, NOSE_TOP + 4),
                        (CENTER + 2.6, tip - 6),
                        (CENTER - 2.6, tip - 6),
                    ],
                    samples=6,
                ),
                soft,
            )

    @staticmethod
    def _draw_mouth(fill: Painter, attributes: AvatarAttributes) -> None:
        lip = hex_to_rgb(LIP)
        expression = attributes.expression
        if expression in {"happy", "smiling"}:
            open_mouth = expression == "happy"
            fill(
                catmull_rom(
                    [
                        (CENTER - 25, MOUTH_Y - 4),
                        (CENTER, MOUTH_Y + (16 if open_mouth else 10)),
                        (CENTER + 25, MOUTH_Y - 4),
                        (CENTER, MOUTH_Y - 7),
                    ],
                    samples=10,
                ),
                lip,
            )
            if open_mouth:
                fill(
                    catmull_rom(
                        [
                            (CENTER - 17, MOUTH_Y - 3),
                            (CENTER, MOUTH_Y + 3),
                            (CENTER + 17, MOUTH_Y - 3),
                            (CENTER, MOUTH_Y - 5),
                        ],
                        samples=10,
                    ),
                    hex_to_rgb(TEETH),
                )
        elif expression == "friendly":
            fill(
                catmull_rom(
                    [
                        (CENTER - 22, MOUTH_Y - 3),
                        (CENTER, MOUTH_Y + 8),
                        (CENTER + 22, MOUTH_Y - 3),
                        (CENTER, MOUTH_Y + 1),
                    ],
                    samples=10,
                ),
                lip,
            )
        elif expression == "confident":
            fill(
                catmull_rom(
                    [
                        (CENTER - 22, MOUTH_Y + 2),
                        (CENTER + 4, MOUTH_Y + 6),
                        (CENTER + 22, MOUTH_Y - 4),
                        (CENTER + 2, MOUTH_Y + 1),
                    ],
                    samples=10,
                ),
                lip,
            )
        elif expression == "serious":
            fill(
                [
                    (CENTER - 20, MOUTH_Y - 1),
                    (CENTER + 20, MOUTH_Y - 1),
                    (CENTER + 20, MOUTH_Y + 3),
                    (CENTER - 20, MOUTH_Y + 3),
                ],
                lip,
            )
        else:  # calm
            fill(
                catmull_rom(
                    [
                        (CENTER - 20, MOUTH_Y),
                        (CENTER - 7, MOUTH_Y - 4),
                        (CENTER, MOUTH_Y - 1),
                        (CENTER + 7, MOUTH_Y - 4),
                        (CENTER + 20, MOUTH_Y),
                        (CENTER + 8, MOUTH_Y + 6),
                        (CENTER, MOUTH_Y + 7),
                        (CENTER - 8, MOUTH_Y + 6),
                    ],
                    samples=10,
                ),
                lip,
            )

    @staticmethod
    def _draw_freckles(
        draw: ImageDraw.ImageDraw,
        box: BoxFn,
        attributes: AvatarAttributes,
        skin_shadow: Rgb,
    ) -> None:
        density = attributes.effective_freckles
        if density == "none":
            return
        color = shade(skin_shadow, -0.22)
        rows = (0, 8) if density == "heavy" else (0,)
        for row in rows:
            for index in range(4):
                for sign in (-1.0, 1.0):
                    x = CENTER + sign * (22 + index * 9)
                    y = NOSE_TOP + 6 + row + (index % 2) * 5
                    draw.ellipse(box(x - 2.2, y - 2.2, x + 2.2, y + 2.2), fill=color)

    @staticmethod
    def _draw_glasses(
        draw: ImageDraw.ImageDraw,
        box: BoxFn,
        stroke: LinePainter,
        frame_width: Callable[[float], int],
        shape: FaceShape,
        attributes: AvatarAttributes,
    ) -> None:
        style = attributes.effective_glasses
        if style == "none":
            return
        frame = hex_to_rgb(LINE)
        # La montura se ajusta al hueco real entre el ojo y el borde del rostro:
        # una talla fija resulta enorme sobre las siluetas estrechas.
        edge = face_half_width(shape, EYE_Y)
        half = min(22.0, max(15.0, (edge - EYE_DX) * 0.86))
        center_y = EYE_Y + 2
        for sign in (-1.0, 1.0):
            cx = CENTER + sign * EYE_DX
            if style == "round":
                # La montura redonda ocupa más superficie a igual anchura, así
                # que se dibuja algo menor para no comerse el rostro.
                radius = half * 0.87
                draw.ellipse(
                    box(cx - radius, center_y - radius, cx + radius, center_y + radius),
                    outline=frame,
                    width=frame_width(3.2),
                )
            elif style == "rectangular":
                draw.rounded_rectangle(
                    box(cx - half - 1, center_y - half * 0.58, cx + half + 1,
                        center_y + half * 0.58),
                    radius=frame_width(4),
                    outline=frame,
                    width=frame_width(3.2),
                )
            elif style == "square":
                draw.rounded_rectangle(
                    box(cx - half, center_y - half * 0.82, cx + half, center_y + half * 0.82),
                    radius=frame_width(6),
                    outline=frame,
                    width=frame_width(3.2),
                )
            else:  # sunglasses
                draw.rounded_rectangle(
                    box(cx - half - 1, center_y - half * 0.78, cx + half + 1,
                        center_y + half * 0.7),
                    radius=frame_width(8),
                    fill=frame,
                )
        bridge = EYE_DX - half
        stroke([(CENTER - bridge, center_y - 3), (CENTER + bridge, center_y - 3)], frame, 3.2)
        outer = EYE_DX + half
        stroke([(CENTER - edge - 1, center_y - 5), (CENTER - outer, center_y - 2)], frame, 3.2)
        stroke([(CENTER + outer, center_y - 2), (CENTER + edge + 1, center_y - 5)], frame, 3.2)

    @staticmethod
    def _draw_earrings(
        draw: ImageDraw.ImageDraw,
        box: BoxFn,
        frame_width: Callable[[float], int],
        shape: FaceShape,
        attributes: AvatarAttributes,
    ) -> None:
        style = attributes.effective_earrings
        if style == "none":
            return
        gold = hex_to_rgb(GOLD)
        for sign in (-1.0, 1.0):
            cx = CENTER + sign * (face_half_width(shape, 168.0) + 6)
            if style == "studs":
                draw.ellipse(box(cx - 5, 164, cx + 5, 174), fill=gold)
            else:
                draw.ellipse(
                    box(cx - 10, 164, cx + 10, 190), outline=gold, width=frame_width(3.5)
                )
