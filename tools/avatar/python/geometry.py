from __future__ import annotations

Point = tuple[float, float]


def catmull_rom(points: list[Point], samples: int = 14, closed: bool = True) -> list[Point]:
    """Suaviza una polilínea con splines Catmull-Rom.

    Permite describir cabezas, mechones y barbas con unos pocos puntos de
    control y obtener contornos orgánicos, que es lo que distingue un avatar
    cuidado de un montaje de rectángulos redondeados.
    """
    if len(points) < 3:
        return list(points)
    extended = (
        [points[-1], *points, points[0], points[1]]
        if closed
        else [points[0], *points, points[-1]]
    )
    curve: list[Point] = []
    for index in range(len(extended) - 3):
        p0, p1, p2, p3 = extended[index : index + 4]
        for step in range(samples):
            t = step / samples
            t2 = t * t
            t3 = t2 * t
            curve.append(
                (
                    0.5
                    * (
                        2 * p1[0]
                        + (-p0[0] + p2[0]) * t
                        + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                        + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3
                    ),
                    0.5
                    * (
                        2 * p1[1]
                        + (-p0[1] + p2[1]) * t
                        + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                        + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3
                    ),
                )
            )
    return curve


def mirror(points: list[Point], axis: float = 128.0) -> list[Point]:
    """Refleja una media silueta para construir la otra mitad."""
    return [(2 * axis - x, y) for x, y in reversed(points)]


def ellipse_points(
    center: Point, radius_x: float, radius_y: float, start: float, end: float, steps: int = 24
) -> list[Point]:
    """Arco elíptico muestreado, en grados, para componer siluetas."""
    from math import cos, radians, sin

    span = end - start
    return [
        (
            center[0] + radius_x * cos(radians(start + span * index / steps)),
            center[1] + radius_y * sin(radians(start + span * index / steps)),
        )
        for index in range(steps + 1)
    ]
