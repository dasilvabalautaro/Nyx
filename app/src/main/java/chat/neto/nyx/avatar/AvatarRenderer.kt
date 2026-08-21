package chat.neto.nyx.avatar

import chat.neto.nyx.core.avatar.AvatarAttributes
import chat.neto.nyx.core.avatar.Palette
import chat.neto.nyx.core.avatar.Point
import chat.neto.nyx.core.avatar.catmullRom
import chat.neto.nyx.core.avatar.ellipsePoints
import chat.neto.nyx.core.avatar.mirror
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Dibuja el avatar desde los atributos, sin modelo neuronal (ADR 0012).
 *
 * Es el port de `src/avatar_face/infrastructure/rendering/avatar_renderer.py`
 * sobre la misma tabla de coordenadas de 256 px, para que la app y la
 * herramienta de Python produzcan la misma imagen. Cualquier cambio en una de
 * las dos debe replicarse en la otra y comprobarse con la comparación de
 * `scripts/compare_android_render.py`.
 */
class AvatarRenderer(private val imageSize: Int = 256) {

    data class FaceShape(
        val temple: Float,
        val cheek: Float,
        val jaw: Float,
        val chin: Float,
        val chinY: Float,
        val top: Float,
    )

    private lateinit var canvas: Canvas
    private var factor = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun render(attributes: AvatarAttributes): Bitmap {
        factor = imageSize * SUPERSAMPLE / 256f
        val side = Math.round(256 * factor)
        val large = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        canvas = Canvas(large)
        canvas.drawColor(Palette.parse(Palette.backgrounds.getValue(attributes.background)))

        val shape = FACE_SHAPES.getValue(attributes.faceShape)
        val skin = Palette.parse(Palette.skinTones.getValue(attributes.skinTone))
        val skinShadow = Palette.shade(skin, -0.11f)
        val hair = Palette.parse(Palette.hairColors.getValue(attributes.hairColor))
        val cloth = Palette.parse(Palette.clothingColors.getValue(attributes.clothingColor))

        drawHairBack(shape, attributes, hair)
        drawBody(shape, attributes, cloth, skin, skinShadow)
        for (sign in signs) {
            val cx = CENTER + sign * (faceHalfWidth(shape, 151f) - 4)
            fill(ellipsePoints(Point(cx, 151f), 9f, 14f, 0f, 360f, 24), skin)
        }
        fill(faceOutline(shape), skin)
        drawEyes(attributes)
        drawNose(attributes, skin, skinShadow)
        drawBeard(shape, attributes, hair, skin)
        drawMouth(attributes)
        drawMustache(attributes, hair, skin)
        drawFreckles(attributes, skinShadow)
        if (attributes.hairStyle == "bald") drawScalpHighlight(shape, skin)
        drawHairFront(shape, attributes, hair)
        drawGlasses(shape, attributes)
        drawEarrings(shape, attributes)

        if (side == imageSize) return large
        val scaled = Bitmap.createScaledBitmap(large, imageSize, imageSize, true)
        large.recycle()
        return scaled
    }

    // --- primitivas -------------------------------------------------------

    private fun fill(points: List<Point>, color: Int) {
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points[0].x * factor, points[0].y * factor)
        for (index in 1 until points.size) {
            path.lineTo(points[index].x * factor, points[index].y * factor)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        RectF(x0 * factor, y0 * factor, x1 * factor, y1 * factor)

    private fun fillOval(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawOval(rect(x0, y0, x1, y1), paint)
    }

    private fun strokeOval(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = max(1f, width * factor)
        canvas.drawOval(rect(x0, y0, x1, y1), paint)
        paint.style = Paint.Style.FILL
    }

    private fun strokeRoundRect(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        color: Int,
        width: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = max(1f, width * factor)
        canvas.drawRoundRect(rect(x0, y0, x1, y1), radius * factor, radius * factor, paint)
        paint.style = Paint.Style.FILL
    }

    private fun fillRoundRect(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        color: Int,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(rect(x0, y0, x1, y1), radius * factor, radius * factor, paint)
    }

    private fun stroke(points: List<Point>, color: Int, width: Float) {
        val path = Path()
        path.moveTo(points[0].x * factor, points[0].y * factor)
        for (index in 1 until points.size) {
            path.lineTo(points[index].x * factor, points[index].y * factor)
        }
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = max(1f, width * factor)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    // --- silueta ----------------------------------------------------------

    private fun faceHalfWidth(shape: FaceShape, y: Float): Float {
        val anchors = listOf(
            shape.top to 0f,
            (shape.top + 44) to shape.temple,
            132f to shape.cheek,
            176f to shape.jaw,
            (shape.chinY - 12) to shape.chin,
            shape.chinY to 0f,
        )
        if (y <= anchors[1].first) return shape.temple
        for (index in 0 until anchors.size - 1) {
            val (y0, w0) = anchors[index]
            val (y1, w1) = anchors[index + 1]
            if (y in y0..y1) return w0 + (w1 - w0) * (y - y0) / (y1 - y0)
        }
        return shape.chin
    }

    private fun faceOutline(shape: FaceShape): List<Point> {
        val right = listOf(
            Point(CENTER, shape.top),
            Point(CENTER + shape.temple * 0.72f, shape.top + 12),
            Point(CENTER + shape.temple, shape.top + 44),
            Point(CENTER + shape.cheek, 132f),
            Point(CENTER + shape.jaw, 176f),
            Point(CENTER + shape.chin, shape.chinY - 12),
            Point(CENTER, shape.chinY),
        )
        val left = mirror(right).drop(1).dropLast(1)
        return catmullRom(right + left, samples = 10)
    }

    private fun hairCap(
        shape: FaceShape,
        volume: Float,
        sideY: Float,
        hairline: Float,
        peak: Float = 0f,
    ): List<Point> {
        val width = max(shape.temple, faceHalfWidth(shape, sideY) * 0.94f) + 5
        val crown = ellipsePoints(
            Point(CENTER, shape.top + 30), width, 30 + volume + peak, 180f, 360f, 18,
        )
        val points = buildList {
            add(Point(CENTER - width + 2, sideY))
            add(Point(CENTER - width - 1, sideY - 40))
            addAll(crown)
            add(Point(CENTER + width + 1, sideY - 40))
            add(Point(CENTER + width - 2, sideY))
            add(Point(CENTER + width - 12, sideY - 4))
            add(Point(CENTER + width - 15, hairline + 14))
            add(Point(CENTER + width * 0.62f, hairline))
            add(Point(CENTER, hairline - 7))
            add(Point(CENTER - width * 0.62f, hairline))
            add(Point(CENTER - width + 15, hairline + 14))
            add(Point(CENTER - width + 12, sideY - 4))
        }
        return catmullRom(points, samples = 8)
    }

    private fun scallopedCrown(
        shape: FaceShape,
        width: Float,
        volume: Float,
        lobes: Int,
        depth: Float,
    ): List<Point> {
        val base = ellipsePoints(
            Point(CENTER, shape.top + 30), width, 30 + volume, 180f, 360f, lobes * 2,
        )
        val cy = shape.top + 30
        return base.mapIndexed { index, point ->
            val scale = 1f + if (index % 2 == 1) depth else -depth * 0.5f
            Point(CENTER + (point.x - CENTER) * scale, cy + (point.y - cy) * scale)
        }
    }

    // --- capas ------------------------------------------------------------

    private fun drawHairBack(shape: FaceShape, attributes: AvatarAttributes, hair: Int) {
        val dark = Palette.shade(hair, -0.18f)
        when (attributes.hairStyle) {
            "long" -> {
                val right = listOf(
                    Point(CENTER + 4, shape.top - 6),
                    Point(CENTER + shape.temple + 18, shape.top + 40),
                    Point(CENTER + shape.cheek + 22, 150f),
                    Point(CENTER + shape.cheek + 16, 232f),
                    Point(CENTER + 40, 244f),
                    Point(CENTER, 240f),
                )
                fill(catmullRom(right + mirror(right).drop(1).dropLast(1), 10), dark)
            }
            "bob" -> {
                val right = listOf(
                    Point(CENTER + 4, shape.top - 4),
                    Point(CENTER + shape.temple + 14, shape.top + 40),
                    Point(CENTER + shape.cheek + 16, 148f),
                    Point(CENTER + shape.jaw + 16, 196f),
                    Point(CENTER + 34, 208f),
                    Point(CENTER, 210f),
                )
                fill(catmullRom(right + mirror(right).drop(1).dropLast(1), 10), dark)
            }
            "afro" -> {
                val cy = shape.top + 36
                val base = ellipsePoints(
                    Point(CENTER, cy), shape.temple + 26, shape.temple + 29, 0f, 360f, 26,
                )
                val scalloped = base.mapIndexed { index, point ->
                    val scale = if (index % 2 == 1) 1.06f else 0.98f
                    Point(CENTER + (point.x - CENTER) * scale, cy + (point.y - cy) * scale)
                }
                fill(catmullRom(scalloped, 4), dark)
            }
            "ponytail" -> fill(
                catmullRom(
                    listOf(
                        Point(CENTER + shape.temple, 96f),
                        Point(CENTER + shape.temple + 34, 118f),
                        Point(CENTER + shape.temple + 40, 178f),
                        Point(CENTER + shape.temple + 18, 206f),
                        Point(CENTER + shape.temple + 4, 168f),
                    ),
                    10,
                ),
                dark,
            )
            "bun" -> fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 27, shape.top - 18),
                        Point(CENTER, shape.top - 40),
                        Point(CENTER + 27, shape.top - 18),
                        Point(CENTER, shape.top + 4),
                    ),
                    12,
                ),
                dark,
            )
        }
    }

    private fun drawScalpHighlight(shape: FaceShape, skin: Int) {
        fill(
            catmullRom(
                listOf(
                    Point(CENTER - 30, shape.top + 26),
                    Point(CENTER - 6, shape.top + 9),
                    Point(CENTER + 22, shape.top + 20),
                    Point(CENTER - 2, shape.top + 32),
                ),
                10,
            ),
            Palette.shade(skin, 0.12f),
        )
    }

    private fun drawHairFront(shape: FaceShape, attributes: AvatarAttributes, hair: Int) {
        val light = Palette.shade(hair, 0.16f)
        when (val style = attributes.hairStyle) {
            "bald" -> return
            "buzz" -> fill(hairCap(shape, 2f, 126f, 100f), hair)
            "undercut" -> fill(hairCap(shape, 15f, 104f, 96f), hair)
            "curly" -> {
                val width = max(shape.temple, faceHalfWidth(shape, 142f) * 0.94f) + 5
                val crown = scallopedCrown(shape, width, 13f, 6, 0.10f)
                val points = buildList {
                    add(Point(CENTER - width + 2, 142f))
                    add(Point(CENTER - width - 1, 108f))
                    addAll(crown)
                    add(Point(CENTER + width + 1, 108f))
                    add(Point(CENTER + width - 2, 142f))
                    add(Point(CENTER + width - 12, 138f))
                    add(Point(CENTER + width - 15, 114f))
                    add(Point(CENTER + width * 0.62f, 100f))
                    add(Point(CENTER, 93f))
                    add(Point(CENTER - width * 0.62f, 100f))
                    add(Point(CENTER - width + 15, 114f))
                    add(Point(CENTER - width + 12, 138f))
                }
                fill(catmullRom(points, 8), hair)
            }
            "afro" -> fill(hairCap(shape, 10f, 140f, 104f), hair)
            "wavy" -> {
                fill(hairCap(shape, 18f, 150f, 100f), hair)
                fill(
                    catmullRom(
                        listOf(
                            Point(CENTER - shape.temple + 4, 98f),
                            Point(CENTER - 24, 110f),
                            Point(CENTER + 8, 94f),
                            Point(CENTER + shape.temple - 6, 108f),
                            Point(CENTER + shape.temple - 2, 82f),
                            Point(CENTER - shape.temple + 6, 78f),
                        ),
                        10,
                    ),
                    Palette.shade(hair, 0.09f),
                )
            }
            "side-parted" -> {
                fill(hairCap(shape, 14f, 142f, 99f), hair)
                fill(
                    catmullRom(
                        listOf(
                            Point(CENTER - 30, 92f),
                            Point(CENTER + shape.temple - 4, 82f),
                            Point(CENTER + shape.temple + 2, 112f),
                            Point(CENTER + 14, 96f),
                        ),
                        10,
                    ),
                    light,
                )
            }
            "long", "bob", "ponytail", "bun" -> {
                val side = if (style == "bun") 130f else 150f
                fill(hairCap(shape, 13f, side, 98f), hair)
            }
            else -> {
                fill(hairCap(shape, 12f, 138f, 100f), hair)
                fill(
                    catmullRom(
                        listOf(
                            Point(CENTER - 34, 92f),
                            Point(CENTER + 6, 82f),
                            Point(CENTER + shape.temple - 8, 96f),
                            Point(CENTER - 6, 96f),
                        ),
                        10,
                    ),
                    light,
                )
            }
        }
    }

    private fun facialHairColor(attributes: AvatarAttributes, hair: Int, skin: Int): Int =
        if (attributes.facialHair == "stubble") {
            Palette.mix(skin, hair, 0.45f)
        } else {
            Palette.shade(hair, -0.04f)
        }

    private fun drawBeard(
        shape: FaceShape,
        attributes: AvatarAttributes,
        hair: Int,
        skin: Int,
    ) {
        val style = attributes.facialHair
        if (style == "none") return
        val color = facialHairColor(attributes, hair, skin)
        if (style == "stubble" || style == "short beard" || style == "full beard") {
            val top = when (style) {
                "stubble" -> 150f
                "short beard" -> 146f
                else -> 138f
            }
            val lift = if (style == "full beard") 16f else 2f
            val points = listOf(
                Point(CENTER - shape.cheek + 4, top),
                Point(CENTER - shape.jaw - 1, 178f),
                Point(CENTER - shape.chin - 8, shape.chinY - 10),
                Point(CENTER, shape.chinY - 1),
                Point(CENTER + shape.chin + 8, shape.chinY - 10),
                Point(CENTER + shape.jaw + 1, 178f),
                Point(CENTER + shape.cheek - 4, top),
                Point(CENTER + shape.cheek - 22, top + 20),
                Point(CENTER + 31, MOUTH_Y - lift),
                Point(CENTER, MOUTH_Y + 6),
                Point(CENTER - 31, MOUTH_Y - lift),
                Point(CENTER - shape.cheek + 22, top + 20),
            )
            fill(catmullRom(points, 8), color)
        } else if (style == "goatee") {
            fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 21, MOUTH_Y + 4),
                        Point(CENTER, MOUTH_Y - 2),
                        Point(CENTER + 21, MOUTH_Y + 4),
                        Point(CENTER + 15, shape.chinY - 8),
                        Point(CENTER, shape.chinY - 2),
                        Point(CENTER - 15, shape.chinY - 8),
                    ),
                    10,
                ),
                color,
            )
        }
    }

    private fun drawMustache(attributes: AvatarAttributes, hair: Int, skin: Int) {
        if (attributes.facialHair == "none") return
        fill(
            catmullRom(
                listOf(
                    Point(CENTER - 28, MOUTH_Y - 17),
                    Point(CENTER, MOUTH_Y - 22),
                    Point(CENTER + 28, MOUTH_Y - 17),
                    Point(CENTER + 20, MOUTH_Y - 4),
                    Point(CENTER, MOUTH_Y - 10),
                    Point(CENTER - 20, MOUTH_Y - 4),
                ),
                10,
            ),
            facialHairColor(attributes, hair, skin),
        )
    }

    private fun drawBody(
        shape: FaceShape,
        attributes: AvatarAttributes,
        cloth: Int,
        skin: Int,
        skinShadow: Int,
    ) {
        fill(
            catmullRom(
                listOf(
                    Point(CENTER - 24, shape.chinY - 30),
                    Point(CENTER - 26, shape.chinY + 18),
                    Point(CENTER - 30, shape.chinY + 34),
                    Point(CENTER + 30, shape.chinY + 34),
                    Point(CENTER + 26, shape.chinY + 18),
                    Point(CENTER + 24, shape.chinY - 30),
                ),
                8,
            ),
            skin,
        )
        fill(
            catmullRom(
                listOf(
                    Point(CENTER - 25, shape.chinY - 6),
                    Point(CENTER, shape.chinY + 13),
                    Point(CENTER + 25, shape.chinY - 6),
                    Point(CENTER, shape.chinY + 1),
                ),
                10,
            ),
            skinShadow,
        )
        val garment = attributes.clothing
        val topY = when (garment) {
            "crew neck" -> 220f
            "v-neck" -> 216f
            "collared shirt" -> 218f
            "hoodie" -> 212f
            else -> 204f
        }
        val shoulders = listOf(
            Point(CENTER - 118, 258f),
            Point(CENTER - 94, 234f),
            Point(CENTER - 44, 220f),
            Point(CENTER, 216f),
            Point(CENTER + 44, 220f),
            Point(CENTER + 94, 234f),
            Point(CENTER + 118, 258f),
        )
        val body = catmullRom(shoulders, 10, closed = false)
            .map { Point(it.x, max(it.y, topY)) }
        fill(body + listOf(Point(CENTER + 132, 264f), Point(CENTER - 132, 264f)), cloth)
        when (garment) {
            "v-neck" -> fill(
                listOf(
                    Point(CENTER - 22, topY - 1),
                    Point(CENTER, topY + 30),
                    Point(CENTER + 22, topY - 1),
                ),
                skin,
            )
            "collared shirt" -> {
                fill(
                    listOf(
                        Point(CENTER - 11, topY - 4),
                        Point(CENTER, topY + 15),
                        Point(CENTER + 11, topY - 4),
                    ),
                    Palette.shade(cloth, -0.26f),
                )
                val highlight = Palette.shade(cloth, 0.14f)
                for (sign in signs) {
                    fill(
                        listOf(
                            Point(CENTER + sign * 25, topY - 5),
                            Point(CENTER + sign * 6, topY + 1),
                            Point(CENTER + sign * 12, topY + 17),
                        ),
                        highlight,
                    )
                }
            }
            "hoodie" -> fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 62, topY + 6),
                        Point(CENTER, topY + 32),
                        Point(CENTER + 62, topY + 6),
                        Point(CENTER, topY - 8),
                    ),
                    10,
                ),
                Palette.shade(cloth, -0.16f),
            )
            "turtleneck" -> fill(
                listOf(
                    Point(CENTER - 29, topY - 10),
                    Point(CENTER + 29, topY - 10),
                    Point(CENTER + 31, topY + 16),
                    Point(CENTER - 31, topY + 16),
                ),
                Palette.shade(cloth, 0.13f),
            )
        }
    }

    private fun drawEyes(attributes: AvatarAttributes) {
        val (halfW, halfH, irisR, hood) = eyeGeometry(attributes.eyeShape)
        val iris = Palette.parse(Palette.eyeColors.getValue(attributes.eyeColor))
        val dark = Palette.parse(Palette.LINE)
        val white = Palette.parse(Palette.SCLERA)
        val brow = Palette.mix(
            Palette.parse(Palette.hairColors.getValue(attributes.hairColor)), dark, 0.25f,
        )
        for (sign in signs) {
            val cx = CENTER + sign * EYE_DX
            fillOval(cx - halfW, EYE_Y - halfH, cx + halfW, EYE_Y + halfH, white)
            fillOval(cx - irisR, EYE_Y - irisR, cx + irisR, EYE_Y + irisR, iris)
            fillOval(cx - 3.9f, EYE_Y - 3.9f, cx + 3.9f, EYE_Y + 3.9f, dark)
            fillOval(cx + 1.8f, EYE_Y - 6.2f, cx + 5.6f, EYE_Y - 2.4f, white)
            fill(
                catmullRom(
                    listOf(
                        Point(cx - halfW - 0.5f, EYE_Y - halfH * 0.45f),
                        Point(cx, EYE_Y - halfH - 1.0f + hood),
                        Point(cx + halfW + 0.5f, EYE_Y - halfH * 0.45f),
                        Point(cx, EYE_Y - halfH + 1.9f + hood),
                    ),
                    8,
                ),
                dark,
            )
            fill(browPoints(attributes.browStyle, sign), brow)
        }
    }

    private fun drawNose(attributes: AvatarAttributes, skinBase: Int, skinShadow: Int) {
        val style = attributes.noseStyle
        val (width, tip) = when (style) {
            "straight" -> 8f to NOSE_BOTTOM
            "small" -> 6.5f to NOSE_BOTTOM - 5
            "button" -> 9f to NOSE_BOTTOM - 3
            "wide" -> 12f to NOSE_BOTTOM
            else -> 6.5f to NOSE_BOTTOM + 1
        }
        val soft = Palette.mix(skinShadow, skinBase, 0.3f)
        val depth = if (style == "button" || style == "wide") 6f else 5f
        fill(
            catmullRom(
                listOf(
                    Point(CENTER - width, tip - 5),
                    Point(CENTER, tip + depth * 0.55f),
                    Point(CENTER + width, tip - 5),
                    Point(CENTER, tip - depth * 0.35f),
                ),
                12,
            ),
            soft,
        )
        if (style == "pointed") {
            fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 2.2f, NOSE_TOP + 4),
                        Point(CENTER + 2.2f, NOSE_TOP + 4),
                        Point(CENTER + 2.6f, tip - 6),
                        Point(CENTER - 2.6f, tip - 6),
                    ),
                    6,
                ),
                soft,
            )
        }
    }

    private fun drawMouth(attributes: AvatarAttributes) {
        val lip = Palette.parse(Palette.LIP)
        when (val expression = attributes.expression) {
            "happy", "smiling" -> {
                val open = expression == "happy"
                fill(
                    catmullRom(
                        listOf(
                            Point(CENTER - 25, MOUTH_Y - 4),
                            Point(CENTER, MOUTH_Y + if (open) 16f else 10f),
                            Point(CENTER + 25, MOUTH_Y - 4),
                            Point(CENTER, MOUTH_Y - 7),
                        ),
                        10,
                    ),
                    lip,
                )
                if (open) {
                    fill(
                        catmullRom(
                            listOf(
                                Point(CENTER - 17, MOUTH_Y - 3),
                                Point(CENTER, MOUTH_Y + 3),
                                Point(CENTER + 17, MOUTH_Y - 3),
                                Point(CENTER, MOUTH_Y - 5),
                            ),
                            10,
                        ),
                        Palette.parse(Palette.TEETH),
                    )
                }
            }
            "friendly" -> fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 22, MOUTH_Y - 3),
                        Point(CENTER, MOUTH_Y + 8),
                        Point(CENTER + 22, MOUTH_Y - 3),
                        Point(CENTER, MOUTH_Y + 1),
                    ),
                    10,
                ),
                lip,
            )
            "confident" -> fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 22, MOUTH_Y + 2),
                        Point(CENTER + 4, MOUTH_Y + 6),
                        Point(CENTER + 22, MOUTH_Y - 4),
                        Point(CENTER + 2, MOUTH_Y + 1),
                    ),
                    10,
                ),
                lip,
            )
            "serious" -> fill(
                listOf(
                    Point(CENTER - 20, MOUTH_Y - 1),
                    Point(CENTER + 20, MOUTH_Y - 1),
                    Point(CENTER + 20, MOUTH_Y + 3),
                    Point(CENTER - 20, MOUTH_Y + 3),
                ),
                lip,
            )
            else -> fill(
                catmullRom(
                    listOf(
                        Point(CENTER - 20, MOUTH_Y),
                        Point(CENTER - 7, MOUTH_Y - 4),
                        Point(CENTER, MOUTH_Y - 1),
                        Point(CENTER + 7, MOUTH_Y - 4),
                        Point(CENTER + 20, MOUTH_Y),
                        Point(CENTER + 8, MOUTH_Y + 6),
                        Point(CENTER, MOUTH_Y + 7),
                        Point(CENTER - 8, MOUTH_Y + 6),
                    ),
                    10,
                ),
                lip,
            )
        }
    }

    private fun drawFreckles(attributes: AvatarAttributes, skinShadow: Int) {
        val density = attributes.effectiveFreckles
        if (density == "none") return
        val color = Palette.shade(skinShadow, -0.22f)
        val rows = if (density == "heavy") listOf(0, 8) else listOf(0)
        for (row in rows) {
            for (index in 0 until 4) {
                for (sign in signs) {
                    val x = CENTER + sign * (22 + index * 9)
                    val y = NOSE_TOP + 6 + row + (index % 2) * 5
                    fillOval(x - 2.2f, y - 2.2f, x + 2.2f, y + 2.2f, color)
                }
            }
        }
    }

    private fun drawGlasses(shape: FaceShape, attributes: AvatarAttributes) {
        val style = attributes.effectiveGlasses
        if (style == "none") return
        val frame = Palette.parse(Palette.LINE)
        val edge = faceHalfWidth(shape, EYE_Y)
        val half = min(22f, max(15f, (edge - EYE_DX) * 0.86f))
        val centerY = EYE_Y + 2
        for (sign in signs) {
            val cx = CENTER + sign * EYE_DX
            when (style) {
                "round" -> {
                    val radius = half * 0.87f
                    strokeOval(
                        cx - radius, centerY - radius, cx + radius, centerY + radius, frame, 3.2f,
                    )
                }
                "rectangular" -> strokeRoundRect(
                    cx - half - 1, centerY - half * 0.58f, cx + half + 1, centerY + half * 0.58f,
                    4f, frame, 3.2f,
                )
                "square" -> strokeRoundRect(
                    cx - half, centerY - half * 0.82f, cx + half, centerY + half * 0.82f,
                    6f, frame, 3.2f,
                )
                else -> fillRoundRect(
                    cx - half - 1, centerY - half * 0.78f, cx + half + 1, centerY + half * 0.7f,
                    8f, frame,
                )
            }
        }
        val bridge = EYE_DX - half
        stroke(
            listOf(Point(CENTER - bridge, centerY - 3), Point(CENTER + bridge, centerY - 3)),
            frame, 3.2f,
        )
        val outer = EYE_DX + half
        stroke(
            listOf(Point(CENTER - edge - 1, centerY - 5), Point(CENTER - outer, centerY - 2)),
            frame, 3.2f,
        )
        stroke(
            listOf(Point(CENTER + outer, centerY - 2), Point(CENTER + edge + 1, centerY - 5)),
            frame, 3.2f,
        )
    }

    private fun drawEarrings(shape: FaceShape, attributes: AvatarAttributes) {
        val style = attributes.effectiveEarrings
        if (style == "none") return
        val gold = Palette.parse(Palette.GOLD)
        for (sign in signs) {
            val cx = CENTER + sign * (faceHalfWidth(shape, 168f) + 6)
            if (style == "studs") {
                fillOval(cx - 5, 164f, cx + 5, 174f, gold)
            } else {
                strokeOval(cx - 10, 164f, cx + 10, 190f, gold, 3.5f)
            }
        }
    }

    private fun eyeGeometry(shape: String): List<Float> = when (shape) {
        "almond" -> listOf(17.5f, 10.0f, 8.0f, 0f)
        "round" -> listOf(15.0f, 12.2f, 8.6f, 0f)
        "narrow" -> listOf(18.0f, 7.2f, 7.6f, 0f)
        "wide" -> listOf(19.0f, 12.5f, 9.0f, 0f)
        else -> listOf(17.5f, 10.5f, 8.0f, 3.5f)
    }

    private fun browPoints(style: String, sign: Float): List<Point> {
        val inner = CENTER + sign * 12
        val outer = CENTER + sign * 44
        val (thickness, tilt, peak) = when (style) {
            "natural" -> Triple(7.0f, 2.0f, -2.0f)
            "arched" -> Triple(6.0f, 1.0f, -7.0f)
            "thick" -> Triple(10.5f, 2.0f, -2.0f)
            "thin" -> Triple(4.0f, 2.0f, -2.0f)
            else -> Triple(7.0f, 7.0f, -1.0f)
        }
        val points = listOf(
            Point(inner, BROW_Y + tilt),
            Point(CENTER + sign * 28, BROW_Y + peak),
            Point(outer, BROW_Y + 1),
            Point(outer, BROW_Y + 1 + thickness * 0.55f),
            Point(CENTER + sign * 28, BROW_Y + peak + thickness),
            Point(inner, BROW_Y + tilt + thickness * 0.8f),
        )
        return catmullRom(points, samples = 8)
    }

    private operator fun <T> List<T>.component4(): T = this[3]

    companion object {
        const val SUPERSAMPLE = 4
        const val EYE_Y = 139f
        const val EYE_DX = 26f
        const val BROW_Y = 116f
        const val NOSE_TOP = 150f
        const val NOSE_BOTTOM = 170f
        const val MOUTH_Y = 186f
        const val CENTER = 128f

        private val signs = listOf(-1f, 1f)

        val FACE_SHAPES = mapOf(
            "oval" to FaceShape(56f, 62f, 50f, 24f, 216f, 50f),
            "round" to FaceShape(62f, 69f, 64f, 37f, 205f, 55f),
            "square" to FaceShape(63f, 66f, 66f, 48f, 208f, 52f),
            "heart" to FaceShape(64f, 66f, 42f, 14f, 215f, 52f),
            "long" to FaceShape(52f, 56f, 47f, 23f, 231f, 42f),
            "diamond" to FaceShape(47f, 70f, 43f, 20f, 217f, 55f),
        )
    }
}
