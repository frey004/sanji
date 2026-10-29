package com.frey.traductorpantalla

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Traducción en línea con el servicio web gratuito de Google Translate (el
 * mismo que usan sus extensiones). Traduce bastante mejor que el modelo sin
 * conexión de ML Kit, sobre todo el japonés. No es una API oficial: si falla
 * o no hay internet, devuelve null y se usa la traducción sin conexión.
 */
object OnlineTranslator {

    /** Google usa «zh-CN» para el chino simplificado. */
    private fun googleCode(code: String) = if (code == "zh") "zh-CN" else code

    /** Como mucho estos caracteres por petición. */
    private const val MAX_CHARS = 4000

    /**
     * Traduce varios textos del mismo idioma ([from] puede ser «auto»)
     * agrupándolos en pocas peticiones, una línea por texto. Null si falla.
     */
    suspend fun translateAll(texts: List<String>, from: String, to: String): List<String>? = withContext(Dispatchers.IO) {
        val clean = texts.map { it.replace('\n', ' ').trim() }
        val result = ArrayList<String>(clean.size)
        var start = 0
        while (start < clean.size) {
            var end = start
            var length = 0
            while (end < clean.size && (end == start || length + clean[end].length < MAX_CHARS)) {
                length += clean[end].length + 1
                end++
            }
            val chunk = clean.subList(start, end)
            val lines = post(chunk.joinToString("\n"), from, to)?.split('\n') ?: return@withContext null
            // Si no vuelve una línea por texto, no se puede saber qué es cada cosa.
            if (lines.size != chunk.size) return@withContext null
            result += lines.map { it.trim() }
            start = end
        }
        result
    }

    private fun post(text: String, from: String, to: String): String? = runCatching {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
            "&sl=${googleCode(from)}&tl=${googleCode(to)}"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 8000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try {
            connection.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
            if (connection.responseCode != 200) return@runCatching null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONArray(body).getJSONArray(0)
            buildString { for (i in 0 until parts.length()) append(parts.getJSONArray(i).optString(0)) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    suspend fun translate(text: String, from: String, to: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=${googleCode(from)}&tl=${googleCode(to)}&q=" + URLEncoder.encode(text, "UTF-8")
            val connection = URL(url).openConnection() as HttpURLConnection
            // En directo, más vale la traducción sin conexión ya que una buena tarde.
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                if (connection.responseCode != 200) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                // Respuesta: [[["traducción", "original", …], …], …]
                val parts = JSONArray(body).getJSONArray(0)
                buildString {
                    for (i in 0 until parts.length()) append(parts.getJSONArray(i).optString(0))
                }.trim().ifEmpty { null }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
