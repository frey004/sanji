package com.frey.traductorpantalla

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Gender { MALE, FEMALE, UNSPECIFIED }

/**
 * Un personaje: cómo aparece su nombre en el texto original (puede tener
 * varias formas, p. ej. «Rain» y «レイン»), cómo escribirlo en la traducción
 * y su género.
 */
data class GameCharacter(val aliases: List<String>, val display: String, val gender: Gender)

/**
 * Lista de personajes que los traductores respetan.
 *
 * Google y ML Kit traducen nombres que también son palabras («Rain» → «lluvia»)
 * y, sin saber el género, ponen masculino. Antes de traducir, cada nombre de
 * la lista se cambia por un nombre «cebo» que ningún traductor toca y que
 * marca el género (un nombre de mujer o de hombre); así la frase concuerda
 * («eres estúpida, Mariela») y después se vuelve a poner el nombre real.
 */
object Glossary {
    private const val PREFS = "ajustes"
    private const val KEY = "personajes"

    // Nombres poco habituales para que no choquen con el texto real.
    private val femaleBaits = listOf("Mariela", "Rosaura", "Griselda", "Leonor", "Maribel", "Florinda", "Graciela", "Adelaida")
    private val maleBaits = listOf("Rodolfo", "Leopoldo", "Anselmo", "Gervasio", "Teodoro", "Fulgencio", "Herminio", "Baldomero")
    private val neutralBaits = listOf("Zorvak", "Quilmex", "Drevon", "Tazmir", "Velkor", "Joxtan", "Brimal", "Kestrun")

    /** Lista en memoria; los servicios la leen sin tocar el disco en cada traducción. */
    @Volatile
    var characters: List<GameCharacter> = emptyList()
        private set

    /** Cambia cada vez que se edita la lista (para no reutilizar traducciones antiguas). */
    @Volatile
    var version = 0
        private set

    fun load(context: Context): List<GameCharacter> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        characters = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val aliases = o.getJSONArray("aliases").let { a -> (0 until a.length()).map { a.getString(it) } }
                GameCharacter(aliases, o.optString("display", aliases.firstOrNull().orEmpty()), Gender.valueOf(o.optString("gender", "UNSPECIFIED")))
            }
        }.getOrDefault(emptyList())
        version++
        return characters
    }

    fun save(context: Context, list: List<GameCharacter>) {
        val array = JSONArray()
        for (c in list) {
            array.put(JSONObject().put("aliases", JSONArray(c.aliases)).put("display", c.display).put("gender", c.gender.name))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        characters = list
        version++
    }

    /** Texto con los nombres cambiados por cebos y cómo devolverlos a su sitio. */
    class Protected(val text: String, private val baits: Map<String, String>) {
        /** Vuelve a poner los nombres reales en la traducción. */
        fun restore(translated: String): String = baits.entries.fold(translated) { acc, (bait, name) ->
            acc.replace(Regex("(?i)(?<!\\p{L})" + Regex.escape(bait) + "(?!\\p{L})"), Regex.escapeReplacement(name))
        }
    }

    fun protect(text: String): Protected {
        val list = characters
        if (list.isEmpty()) return Protected(text, emptyMap())
        var result = text
        val baits = LinkedHashMap<String, String>()
        val used = mutableMapOf(Gender.FEMALE to 0, Gender.MALE to 0, Gender.UNSPECIFIED to 0)
        // Primero los nombres más largos («Rain Smith» antes que «Rain»).
        val aliases = list.flatMap { c -> c.aliases.map { it to c } }.sortedByDescending { it.first.length }
        for ((alias, character) in aliases) {
            val pattern = aliasPattern(alias) ?: continue
            if (!pattern.containsMatchIn(result)) continue
            val bait = baits.entries.firstOrNull { it.value == character.display }?.key ?: run {
                val pool = when (character.gender) {
                    Gender.FEMALE -> femaleBaits
                    Gender.MALE -> maleBaits
                    Gender.UNSPECIFIED -> neutralBaits
                }
                val index = used.getValue(character.gender)
                if (index >= pool.size) return@run null
                used[character.gender] = index + 1
                pool[index].also { baits[it] = character.display }
            } ?: continue
            result = pattern.replace(result, Regex.escapeReplacement(bait))
        }
        return Protected(result, baits)
    }

    /**
     * En alfabeto latino el nombre tiene que ser una palabra entera y da igual
     * la mayúscula («rain» o «Rain»); en japonés, chino o coreano basta con
     * que aparezca.
     */
    private fun aliasPattern(alias: String): Regex? {
        val name = alias.trim()
        if (name.isEmpty()) return null
        val latin = name.all { it.code < 0x2E80 }
        return if (latin) {
            Regex("(?i)(?<!\\p{L})" + Regex.escape(name) + "(?!\\p{L})")
        } else {
            Regex(Regex.escape(name))
        }
    }

    /** Traduce [text] respetando los personajes de la lista. */
    suspend fun translate(text: String, translate: suspend (String) -> String?): String? {
        val protected = protect(text)
        return translate(protected.text)?.let { protected.restore(it) }
    }
}
