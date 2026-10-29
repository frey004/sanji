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
