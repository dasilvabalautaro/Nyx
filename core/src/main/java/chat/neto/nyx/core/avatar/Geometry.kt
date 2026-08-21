package chat.neto.nyx.core.avatar

import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometría del dibujo de avatares, portada de
 * `src/avatar_face/infrastructure/rendering/geometry.py`.
 *
 * Las coordenadas están sobre el mismo lienzo de referencia de 256 px que la
 * versión de Python, para que ambas produzcan la misma imagen.
 */
data class Point(val x: Float, val y: Float)

/**
 * Suaviza una polilínea con splines Catmull-Rom.
 *
 * Permite describir cabezas, mechones y barbas con unos pocos puntos de control
 * y obtener contornos orgánicos, que es lo que distingue un avatar cuidado de
 * un montaje de rectángulos redondeados.
 */
fun catmullRom(points: List<Point>, samples: Int = 14, closed: Boolean = true): List<Point> {
    if (points.size < 3) return points
    val extended = if (closed) {
        buildList {
            add(points.last())
            addAll(points)
            add(points[0])
            add(points[1])
        }
    } else {
        buildList {
            add(points[0])
            addAll(points)
            add(points.last())
        }
    }
    val curve = ArrayList<Point>((extended.size - 3) * samples)
    for (index in 0 until extended.size - 3) {
        val p0 = extended[index]
        val p1 = extended[index + 1]
        val p2 = extended[index + 2]
        val p3 = extended[index + 3]
        for (step in 0 until samples) {
            val t = step.toFloat() / samples
            val t2 = t * t
            val t3 = t2 * t
            curve.add(
                Point(
                    0.5f * (
                        2 * p1.x +
                            (-p0.x + p2.x) * t +
                            (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                            (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3
                        ),
                    0.5f * (
                        2 * p1.y +
                            (-p0.y + p2.y) * t +
                            (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                            (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3
                        ),
                ),
            )
        }
    }
    return curve
}

/** Refleja una media silueta para construir la otra mitad. */
fun mirror(points: List<Point>, axis: Float = 128f): List<Point> =
    points.reversed().map { Point(2 * axis - it.x, it.y) }

/** Arco elíptico muestreado, en grados, para componer siluetas. */
fun ellipsePoints(
    center: Point,
    radiusX: Float,
    radiusY: Float,
    start: Float,
    end: Float,
    steps: Int = 24,
): List<Point> {
    val span = end - start
    return (0..steps).map { index ->
        val angle = Math.toRadians((start + span * index / steps).toDouble())
        Point(
            center.x + radiusX * cos(angle).toFloat(),
            center.y + radiusY * sin(angle).toFloat(),
        )
    }
}
