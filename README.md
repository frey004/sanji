# Traductor de Pantalla (Android)

App Android que traduce **páginas web a partir de su enlace** y, además, traduce en tiempo real el texto de **cualquier app, juego, manga o vídeo**,
dibujando la traducción encima del texto original (estilo Google Lens).

## Descargar

👉 **[Descargar el APK](../../releases/latest/download/TraductorPantalla.apk)** (última versión)

Instálalo en el móvil; si Android lo pide, permite «Instalar apps desconocidas».
Requiere Android 8.0 o superior.

## Uso

La pantalla de inicio tiene un cuadro por cada modo; al tocarlo se abre su
propia pantalla con sus ajustes:

- **Uma Musume** (cuadro rosa, con el icono del juego si está instalado).
- **Pantalla en vivo** (cuadro verde).
- **Enlaces web** (cuadro azul).

Debajo está el apartado de **Actualizaciones**.

### Traducir una página web

1. Toca el cuadro **Enlaces web**.
2. Pega el enlace, elige el idioma de la página y al que traducir, y pulsa **Abrir y traducir**
   (si escribes algo que no es un enlace, se busca en Google).
3. La página se abre dentro de la app ya traducida. Puedes seguir navegando por
   sus enlaces: cada página nueva, y el contenido que se carga al hacer scroll,
   se traduce sola.
4. **Ver original / Ver traducción** cambia entre el texto original y el traducido.

También puedes **compartir** un enlace desde Chrome u otra app y elegir
«Traducir página».

### Modo Uma Musume

Sección dedicada al juego **Uma Musume Pretty Derby** (versión japonesa):

1. Elige en «Traducir a» el idioma al que traducir.
2. Ajusta la **opacidad del fondo de los subtítulos**: son claros y translúcidos,
   a juego con los cuadros del juego, en lugar de recuadros negros.
3. Desde el cuadro **Uma Musume**, pulsa **Iniciar modo Uma Musume**. Si el juego está instalado, se abre solo.
4. El tiempo real ya viene activado (burbuja **ウマ**):
   - Traduce solo **diálogos, opciones de selección y menús**; ignora números,
     estadísticas, monedas y contadores.
   - Espera a que cada diálogo termine de escribirse antes de traducirlo.
   - Tocar la burbuja fuerza una traducción; mantenerla pulsada pausa/reanuda el tiempo real.

Los cambios de opacidad se aplican al momento, sin reiniciar la burbuja.

### Traducción en vivo de la pantalla

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

## Actualizaciones

Al abrir la app se comprueba si hay una versión nueva publicada en GitHub y,
si la hay, se ofrece descargarla e instalarla. También puedes usar el botón
**Buscar actualizaciones** al final de la pantalla principal. La primera vez,
Android pide permitir «Instalar apps desconocidas» para esta app.

Para que una versión se pueda instalar encima de la anterior, todas tienen que
ir firmadas con la misma clave. GitHub Actions la lee de dos secretos del
repositorio (*Settings → Secrets and variables → Actions*):

- `SIGNING_KEYSTORE_BASE64`: el archivo `.keystore` (alias `traductor`) en base64.
- `SIGNING_PASSWORD`: su contraseña.

Sin esos secretos el APK se compila igual, pero con una clave temporal distinta
en cada compilación, y Android no dejará instalarlo como actualización.

## Compilar

GitHub Actions compila y publica el APK en cada push
(`.github/workflows/build-apk.yml`). En local: `./gradlew assembleRelease`.
