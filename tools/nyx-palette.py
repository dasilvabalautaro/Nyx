#!/usr/bin/env python3
"""Genera la paleta Nyx rotando el matiz de la paleta base M3 (seed #6750A4) en LCh.

La base la generó Google con HCT, así que sus tonos (L*) y cromas ya cumplen los
contrastes del sistema M3; rotar solo el matiz (h) en CIELAB los conserva.
  - familia primary / neutral-variant: -10 grados  (violeta -> indigo)
  - familia secondary:                 +25 grados  (lavanda -> ciruela)
  - familia tertiary:                  +35 grados  (malva  -> dorado rosado)
  - neutros: matiz fijado al del primario rotado con un croma minimo (velo indigo
    de noche), L* intacto
  - error: sin tocar
"""
import math

def srgb_to_lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def lin_to_srgb(c):
    c = max(0.0, min(1.0, c))
    v = 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
    return round(v * 255)

M = [[0.4124564, 0.3575761, 0.1804375],
     [0.2126729, 0.7151522, 0.0721750],
     [0.0193339, 0.1191920, 0.9503041]]
MI = [[3.2404542, -1.5371385, -0.4985314],
      [-0.9692660, 1.8760108, 0.0415560],
      [0.0556434, -0.2040259, 1.0572252]]
WN = (0.95047, 1.0, 1.08883)  # D65

def hex_to_lch(h):
    r, g, b = (int(h[i:i+2], 16) for i in (0, 2, 4))
    rl, gl, bl = srgb_to_lin(r), srgb_to_lin(g), srgb_to_lin(b)
    x, y, z = (M[i][0]*rl + M[i][1]*gl + M[i][2]*bl for i in range(3))
    def f(t):
        return t ** (1/3) if t > 0.008856 else (7.787 * t + 16/116)
    fx, fy, fz = f(x/WN[0]), f(y/WN[1]), f(z/WN[2])
    L = 116 * fy - 16
    a = 500 * (fx - fy)
    bb = 200 * (fy - fz)
    C = math.hypot(a, bb)
    H = math.degrees(math.atan2(bb, a)) % 360
    return L, C, H

def lch_to_hex(L, C, H):
    a = C * math.cos(math.radians(H))
    bb = C * math.sin(math.radians(H))
    fy = (L + 16) / 116
    fx = fy + a / 500
    fz = fy - bb / 200
    def fi(t):
        t3 = t ** 3
        return t3 if t3 > 0.008856 else (t - 16/116) / 7.787
    x, y, z = fi(fx) * WN[0], fi(fy) * WN[1], fi(fz) * WN[2]
    r, g, b = (MI[i][0]*x + MI[i][1]*y + MI[i][2]*z for i in range(3))
    return "%02X%02X%02X" % (lin_to_srgb(r), lin_to_srgb(g), lin_to_srgb(b))

def rot(h, d):
    L, C, H = hex_to_lch(h)
    return lch_to_hex(L, C, (H + d) % 360)

def neutral(h, hue, cmin):
    L, C, H = hex_to_lch(h)
    return lch_to_hex(L, max(C, cmin), hue)

P, S, T = -10, +25, +25

# Paleta base M3 (seed 6750A4): [rol, hex, familia]
LIGHT = [
    ("primary", "6750A4", "P"), ("onPrimary", "FFFFFF", "-"),
    ("primaryContainer", "EADDFF", "P"), ("onPrimaryContainer", "21005D", "P"),
    ("secondary", "625B71", "S"), ("onSecondary", "FFFFFF", "-"),
    ("secondaryContainer", "E8DEF8", "S"), ("onSecondaryContainer", "1D192B", "S"),
    ("tertiary", "7D5260", "T"), ("onTertiary", "FFFFFF", "-"),
    ("tertiaryContainer", "FFD8E4", "T"), ("onTertiaryContainer", "31111D", "T"),
    ("error", "BA1A1A", "-"), ("onError", "FFFFFF", "-"),
    ("errorContainer", "FFDAD6", "-"), ("onErrorContainer", "410002", "-"),
    ("background", "FEF7FF", "N"), ("onBackground", "1C1B1F", "N"),
    ("surface", "FEF7FF", "N"), ("onSurface", "1C1B1F", "N"),
    ("surfaceVariant", "E7E0EC", "V"), ("onSurfaceVariant", "49454F", "V"),
    ("outline", "79747E", "V"), ("outlineVariant", "CAC4D0", "V"),
    ("inverseSurface", "313033", "N"), ("inverseOnSurface", "F4EFF4", "N"),
    ("inversePrimary", "D0BCFF", "P"),
    ("surfaceContainerLowest", "FFFFFF", "-"), ("surfaceContainerLow", "F7F2FA", "N"),
    ("surfaceContainer", "F3EDF7", "N"), ("surfaceContainerHigh", "ECE6F0", "N"),
    ("surfaceContainerHighest", "E6E0E9", "N"),
]
DARK = [
    ("primary", "D0BCFF", "P"), ("onPrimary", "381E72", "P"),
    ("primaryContainer", "4F378B", "P"), ("onPrimaryContainer", "EADDFF", "P"),
    ("secondary", "CCC2DC", "S"), ("onSecondary", "332D41", "S"),
    ("secondaryContainer", "4A4458", "S"), ("onSecondaryContainer", "E8DEF8", "S"),
    ("tertiary", "EFB8C8", "T"), ("onTertiary", "492532", "T"),
    ("tertiaryContainer", "633B48", "T"), ("onTertiaryContainer", "FFD8E4", "T"),
    ("error", "FFB4AB", "-"), ("onError", "690005", "-"),
    ("errorContainer", "93000A", "-"), ("onErrorContainer", "FFDAD6", "-"),
    ("background", "141218", "N"), ("onBackground", "E6E1E5", "N"),
    ("surface", "141218", "N"), ("onSurface", "E6E1E5", "N"),
    ("surfaceVariant", "49454F", "V"), ("onSurfaceVariant", "CAC4D0", "V"),
    ("outline", "938F99", "V"), ("outlineVariant", "49454F", "V"),
    ("inverseSurface", "E6E1E5", "N"), ("inverseOnSurface", "313033", "N"),
    ("inversePrimary", "6750A4", "P"),
    ("surfaceContainerLowest", "0F0D13", "N"), ("surfaceContainerLow", "1D1B20", "N"),
    ("surfaceContainer", "211F26", "N"), ("surfaceContainerHigh", "2B2930", "N"),
    ("surfaceContainerHighest", "36343B", "N"),
]

# Matiz del primario rotado, para teñir los neutros
prim_hue = hex_to_lch("6750A4")[2] + P

def convert(rows, dark):
    out = []
    for name, hexv, fam in rows:
        if fam == "P":
            v = rot(hexv, P)
        elif fam == "S":
            v = rot(hexv, S)
        elif fam == "T":
            v = rot(hexv, T)
        elif fam == "V":
            v = rot(hexv, P)
        elif fam == "N":
            v = neutral(hexv, prim_hue % 360, 4.0 if dark else 2.5)
        else:
            v = hexv
        out.append((name, v))
    return out

for label, rows, dark in (("light", LIGHT, False), ("dark", DARK, True)):
    print(f"// {label}")
    for name, v in convert(rows, dark):
        print(f"val md_{label}_{name} = Color(0xFF{v})")
    print()

for h in ("4F378B", "21005D", "EFB8C8", "D0BCFF"):
    print(h, "->", rot(h, P if h in ("4F378B", "21005D", "D0BCFF") else T))
