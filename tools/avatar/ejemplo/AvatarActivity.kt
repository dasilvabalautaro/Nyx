package com.avatarface.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.avatarface.app.render.AttributeParser
import com.avatarface.app.render.AvatarPrompt
import com.avatarface.app.render.AvatarRenderer

/**
 * Pantalla de producto: se escribe cómo debe ser el avatar y se dibuja al
 * instante, sin red y sin modelo (ADR 0012).
 *
 * El filtro de sólo adultos (RF-09) actúa antes de dibujar: si la descripción
 * sugiere una persona menor de edad no se genera nada y se explica por qué.
 *
 * Para pruebas automatizadas admite `--es text "<descripción>"`, que rellena el
 * campo al abrir.
 */
class AvatarActivity : Activity() {

    private val renderer = AvatarRenderer(AVATAR_SIZE)
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F1EC"))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "Tu avatar"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#2A2229"))
        }
        val subtitle = TextView(this).apply {
            text = "Descríbete y se dibuja al momento. Nada sale del teléfono."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#6C6670"))
            setPadding(0, dp(4), 0, dp(16))
        }

        preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        input = EditText(this).apply {
            id = INPUT_ID
            hint = "smiling adult with curly black hair and round glasses"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#2A2229"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
        }

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#6C6670"))
            setPadding(0, dp(12), 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(preview)
        root.addView(input)
        root.addView(status)
        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update(s?.toString().orEmpty())
        })

        val initial = intent.getStringExtra("text")
        if (initial != null) input.setText(initial) else update("")
    }

    /** Texto → atributos → dibujo. Se ejecuta en cada pulsación de tecla. */
    private fun update(text: String) {
        if (text.isBlank()) {
            preview.setImageBitmap(renderer.render(AttributeParser.parse("")))
            status.text = "Ejemplo por defecto: escribe para cambiarlo."
            return
        }
        when (val result = AvatarPrompt.validate(text)) {
            is AvatarPrompt.Result.Invalid -> {
                preview.setImageDrawable(null)
                status.text = result.reason
            }
            is AvatarPrompt.Result.Valid -> {
                val attributes = AttributeParser.parse(result.text)
                val started = SystemClock.elapsedRealtimeNanos()
                preview.setImageBitmap(renderer.render(attributes))
                val milliseconds = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                status.text = buildString {
                    append("pelo ").append(attributes.hairStyle).append(' ')
                        .append(attributes.hairColor)
                    append(" · piel ").append(attributes.skinTone)
                    append(" · ojos ").append(attributes.eyeColor)
                    if (attributes.facialHair != "none") {
                        append(" · ").append(attributes.facialHair)
                    }
                    if (attributes.effectiveGlasses != "none") {
                        append(" · gafas ").append(attributes.effectiveGlasses)
                    }
                    append('\n')
                    append(String.format("dibujado en %.0f ms", milliseconds))
                }
            }
        }
    }

    companion object {
        private const val AVATAR_SIZE = 512
        const val INPUT_ID = 0x00A1
    }
}
