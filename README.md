# Traductor de Pantalla (Android)

App Android que traduce en tiempo real el texto de **cualquier app, juego, manga o vídeo**,
dibujando la traducción encima del texto original (estilo Google Lens).

## Descargar

👉 **[Descargar el APK](../../releases/latest/download/TraductorPantalla.apk)** (última versión)

Instálalo en el móvil; si Android lo pide, permite «Instalar apps desconocidas».
Requiere Android 8.0 o superior.

## Uso

1. Elige el idioma del texto (inglés, japonés, chino, coreano…) y el idioma al que traducir.
2. Pulsa **Iniciar burbuja** y acepta los permisos:
   - *Mostrar sobre otras apps* (para la burbuja y las traducciones).
   - *Grabar / compartir pantalla* (para leer el texto). Elige **pantalla completa**.
3. Abre la app que quieras traducir y usa la burbuja **文A**:
   - **Tocar** → traduce lo que hay en pantalla. Tocar otra vez → quita la traducción.
   - **Mantener pulsada** → activa/desactiva el **modo tiempo real** (burbuja verde): la traducción se actualiza sola cuando cambia la pantalla.
   - **Arrastrar** → mover la burbuja.
4. Para cerrarla: botón **Detener** en la notificación o en la app.

El reconocimiento de texto y la traducción funcionan en el propio móvil con
Google ML Kit. Solo hace falta internet la primera vez para descargar cada idioma (~30 MB).

## Compilar

GitHub Actions compila y publica el APK en cada push
(`.github/workflows/build-apk.yml`). En local: `./gradlew assembleRelease`.
