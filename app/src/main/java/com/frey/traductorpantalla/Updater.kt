package com.frey.traductorpantalla

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

/** Busca nuevas versiones en las publicaciones de GitHub, las descarga e instala. */
object Updater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/frey004/sanji/releases/latest"

    /** Devuelve la última versión publicada si es más nueva que la instalada. */
    suspend fun checkForUpdate(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(open(LATEST_RELEASE, api = true).inputStream.bufferedReader().use { it.readText() })
        // Las etiquetas son v1.0.<número de compilación>, que es también el versionCode.
        val tag = json.getString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return@withContext null
        if (code <= BuildConfig.VERSION_CODE) return@withContext null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
        Release(code, tag.removePrefix("v"), json.optString("body").trim(), apk.getString("browser_download_url"))
    }

    /** Descarga el APK; [onProgress] recibe el porcentaje (o -1 si no se conoce el tamaño). */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "Sanji-${release.versionName}.apk")
            val connection = open(release.apkUrl)
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -2
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
            file
        }

    /** Android pide permiso una vez para que esta app pueda instalar actualizaciones. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String, api: Boolean = false): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (api) connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("El servidor respondió $code")
        }
        return connection
    }
}
