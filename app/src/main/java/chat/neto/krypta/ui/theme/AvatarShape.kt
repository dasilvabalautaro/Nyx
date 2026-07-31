package chat.neto.krypta.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `Shape` de un polígono regular con esquinas redondeadas, para dar variedad a los
 * avatares de contacto sin salir del look de la marca (formas geométricas limpias, nada
 * de puntas agudas que aprieten la inicial). El redondeo se logra "cortando" cada vértice
 * y uniendo los cortes con una curva cuadrática que pasa por el vértice original.
 *
 * @param sides número de lados (>= 3; un valor alto ~60 aproxima un círculo).
 * @param rounding fracción de cada lado que se convierte en curva en cada esquina (0..0.5).
 * @param rotationDeg giro inicial, en grados (0 = un vértice apuntando a la derecha).
 * @param scale circunradio como fracción de medio recuadro. Se usa para **igualar el área**
 *   entre formas distintas (ver [equalAreaScale]): un cuadrado inscrito en el mismo círculo
 *   ocupa mucho menos que el círculo, así que sin este ajuste unas formas se verían más
 *   pequeñas que otras.
 */
class RegularPolygonShape(
    private val sides: Int,
    private val rounding: Float = 0.20f,
    private val rotationDeg: Float = 0f,
    private val scale: Float = 1f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val n = sides.coerceAtLeast(3)
        val r = min(size.width, size.height) / 2f * scale
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rot = Math.toRadians(rotationDeg.toDouble())

        val verts = (0 until n).map { i ->
            val a = rot + i * 2.0 * Math.PI / n
            Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
        // Longitud de un lado del polígono regular; el corte de esquina es una fracción de él.
        val edge = 2f * r * sin(Math.PI.toFloat() / n)
        val cut = edge * rounding.coerceIn(0f, 0.5f)

        val path = Path()
        for (i in 0 until n) {
            val curr = verts[i]
            val prev = verts[(i - 1 + n) % n]
            val next = verts[(i + 1) % n]
            val toPrev = unit(curr, prev)
            val toNext = unit(curr, next)
            val a = Offset(curr.x + toPrev.x * cut, curr.y + toPrev.y * cut)
            val b = Offset(curr.x + toNext.x * cut, curr.y + toNext.y * cut)
            if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
            path.quadraticTo(curr.x, curr.y, b.x, b.y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun unit(from: Offset, to: Offset): Offset {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = hypot(dx, dy)
        return if (len == 0f) Offset.Zero else Offset(dx / len, dy / len)
    }
}

/**
 * Área de un n-gono regular inscrito en radio R, como coeficiente: `área = areaCoeff(n) · R²`.
 * Para el círculo tiende a π (n grande).
 */
private fun areaCoeff(n: Int): Float =
    (n / 2.0 * sin(2.0 * Math.PI / n)).toFloat()

/**
 * Área objetivo = la del pentágono a circunradio = medio recuadro. Es la forma del set que
 * más "sobresale" del recuadro por unidad de área, así que igualar todas a ESA área garantiza
 * que (a) todas tengan el mismo tamaño visual y (b) ninguna se salga del recuadro.
 */
private val TARGET_AREA_COEFF = areaCoeff(5)

/** Escala que hay que aplicar al circunradio de un n-gono para que iguale [TARGET_AREA_COEFF]. */
private fun equalAreaScale(n: Int): Float = sqrt(TARGET_AREA_COEFF / areaCoeff(n))

/**
 * Repertorio curado de formas de avatar, **todas con la misma área visual** (ver
 * [equalAreaScale]). El círculo es un 60-gono (visualmente un círculo, pero pasa por el mismo
 * escalado que las demás, así que no se ve más grande). El resto son polígonos redondeados con
 * buena área interior para la inicial. La forma se elige de forma estable por PeerID (ver
 * [avatarShapeFor]), así el mismo contacto conserva su forma en la lista, la cabecera del chat
 * y la pantalla de llamada — como el color, es una huella visual de identidad.
 */
val AvatarShapes: List<Shape> = listOf(
    RegularPolygonShape(sides = 60, rounding = 0f, rotationDeg = 0f, scale = equalAreaScale(60)),     // círculo
    RegularPolygonShape(sides = 4, rounding = 0.32f, rotationDeg = 45f, scale = equalAreaScale(4)),   // "squircle"
    RegularPolygonShape(sides = 6, rounding = 0.24f, rotationDeg = 0f, scale = equalAreaScale(6)),    // hexágono (lados planos arriba/abajo)
    RegularPolygonShape(sides = 5, rounding = 0.24f, rotationDeg = -90f, scale = equalAreaScale(5)),  // pentágono con punta arriba
    RegularPolygonShape(sides = 8, rounding = 0.18f, rotationDeg = 22.5f, scale = equalAreaScale(8)), // octágono
)

/**
 * Forma estable para un contacto. Usa un hash decorrelacionado del que elige el color
 * ([AvatarColors]) para que forma y color no vayan siempre emparejados igual.
 */
fun avatarShapeFor(peerId: String): Shape =
    AvatarShapes[abs((peerId + "#shape").hashCode()) % AvatarShapes.size]
