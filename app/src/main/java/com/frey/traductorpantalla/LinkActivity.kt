package com.frey.traductorpantalla

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Pantalla para traducir una página web a partir de su enlace. */
class LinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link)

        val sourceSpinner = findViewById<Spinner>(R.id.sourceSpinner)
        val targetSpinner = findViewById<Spinner>(R.id.targetSpinner)
        setupSpinner(sourceSpinner, Languages.sources, Languages.source(this))
        setupSpinner(targetSpinner, Languages.targets, Languages.target(this))
        val save = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Languages.save(
                    this@LinkActivity,
                    Languages.sources[sourceSpinner.selectedItemPosition],
                    Languages.targets[targetSpinner.selectedItemPosition],
                )
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sourceSpinner.onItemSelectedListener = save
        targetSpinner.onItemSelectedListener = save

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val open = {
            val url = urlInput.text.toString()
            if (url.isBlank()) {
                Toast.makeText(this, "Escribe o pega un enlace", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, WebTranslateActivity::class.java).putExtra(WebTranslateActivity.EXTRA_URL, url))
            }
        }
        findViewById<Button>(R.id.openWebButton).setOnClickListener { open() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) open()
            actionId == EditorInfo.IME_ACTION_GO
        }
    }

    private fun setupSpinner(spinner: Spinner, items: List<Language>, selected: Language) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
        spinner.setSelection(items.indexOf(selected))
    }
}
