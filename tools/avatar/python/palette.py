from __future__ import annotations

Rgb = tuple[int, int, int]


def hex_to_rgb(value: str) -> Rgb:
    text = value.lstrip("#")
    return int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16)


def mix(first: Rgb, second: Rgb, ratio: float) -> Rgb:
    """Interpola dos colores; `ratio` 0 devuelve el primero y 1 el segundo."""
    return (
        round(first[0] + (second[0] - first[0]) * ratio),
        round(first[1] + (second[1] - first[1]) * ratio),
        round(first[2] + (second[2] - first[2]) * ratio),
    )


def shade(color: Rgb, amount: float) -> Rgb:
    """Oscurece (`amount` < 0) o aclara (`amount` > 0) manteniendo el tono."""
    target: Rgb = (255, 255, 255) if amount > 0 else (0, 0, 0)
    return mix(color, target, abs(amount))


BACKGROUNDS: dict[str, str] = {
    "coral": "#F5907F",
    "mint": "#79D6AF",
    "sky": "#63C4EE",
    "lavender": "#B4A3E4",
    "sand": "#EEDCC0",
    "slate": "#7C8B9C",
    "rose": "#EFA8BE",
    "teal": "#4FB3AE",
}
SKIN_TONES: dict[str, str] = {
    "porcelain": "#F6DCC9",
    "light": "#F0C8A8",
    "beige": "#E8B98F",
    "golden": "#DCA771",
    "olive": "#C69163",
    "tan": "#BE8654",
    "brown": "#96613D",
    "deep": "#6E462F",
    "ebony": "#4E3122",
}
HAIR_COLORS: dict[str, str] = {
    "black": "#241F27",
    "brown": "#5B3A26",
    "auburn": "#8A3E27",
    "blonde": "#DDB463",
    "blue": "#31509E",
    "pink": "#DC5FA5",
    "gray": "#8D8D95",
    "red": "#C0472A",
    "silver": "#C8CBD2",
    "green": "#3F8F5E",
}
EYE_COLORS: dict[str, str] = {
    "brown": "#5C3A22",
    "blue": "#3277AC",
    "green": "#42804F",
    "gray": "#5F6C76",
    "hazel": "#8A6A32",
    "amber": "#B47826",
}
CLOTHING_COLORS: dict[str, str] = {
    "white": "#F3F3F1",
    "charcoal": "#3B4149",
    "red": "#D6564C",
    "blue": "#4A79BC",
    "green": "#4FA177",
    "mustard": "#DFAE45",
    "purple": "#7C5FB0",
}

LINE = "#2A2229"
SCLERA = "#FDFBF7"
LIP = "#B9695F"
TEETH = "#FDFBF7"
GOLD = "#EEBC49"
