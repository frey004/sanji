package com.frey.traductorpantalla

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Pantalla para apuntar los personajes: sus nombres y su género. */
class GlossaryActivity : ComponentActivity() {

    private lateinit var list: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glossary)
        setupHeader(
            R.string.glossary_title, R.string.glossary_subtitle, R.drawable.header_main,
            Color.rgb(108, 92, 231), emoji = "👤",
        )
        list = findViewById(R.id.characterList)
        emptyText = findViewById(R.id.emptyText)

        val aliases = findViewById<EditText>(R.id.aliasesInput)
        val display = findViewById<EditText>(R.id.displayInput)
        val gender = findViewById<RadioGroup>(R.id.genderGroup)
        findViewById<Button>(R.id.addButton).setOnClickListener {
            val names = aliases.text.split(',', '、', '，').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) {
                Toast.makeText(this, "Escribe al menos un nombre", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val character = GameCharacter(
                aliases = names,
                display = display.text.toString().trim().ifEmpty { names.first() },
                gender = when (gender.checkedRadioButtonId) {
                    R.id.genderMale -> Gender.MALE
                    R.id.genderFemale -> Gender.FEMALE
                    else -> Gender.UNSPECIFIED
                },
            )
            Glossary.save(this, Glossary.characters + character)
            aliases.text.clear()
            display.text.clear()
            gender.check(R.id.genderUnspecified)
            refresh()
        }
        refresh()
    }

    private fun genderName(gender: Gender) = getString(
        when (gender) {
            Gender.MALE -> R.string.glossary_male
            Gender.FEMALE -> R.string.glossary_female
            Gender.UNSPECIFIED -> R.string.glossary_unspecified
        }
    )

    private fun refresh() {
        list.removeAllViews()
        val characters = Glossary.characters
        emptyText.visibility = if (characters.isEmpty()) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        for (character in characters) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val text = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
                setTextColor(getColor(R.color.text))
                val others = character.aliases.filter { it != character.display }
                text = buildString {
                    append(character.display)
                    if (others.isNotEmpty()) append("  (").append(others.joinToString(", ")).append(")")
                    append("\n").append(genderName(character.gender))
                }
            }
            val delete = TextView(this).apply {
                text = "✕"
                textSize = 18f
                setTextColor(getColor(R.color.text_secondary))
                setPadding((14 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                setOnClickListener {
                    AlertDialog.Builder(this@GlossaryActivity)
                        .setMessage("¿Quitar a ${character.display} de la lista?")
                        .setPositiveButton("Quitar") { _, _ ->
                            Glossary.save(this@GlossaryActivity, Glossary.characters - character)
                            refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            row.addView(text)
            row.addView(delete)
            list.addView(row)
        }
    }
}
