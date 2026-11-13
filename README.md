# Visor

Visor de archivos para Android. Solo muestra: no edita ni modifica nada.

Aparece en «Abrir con» y en «Compartir» de cualquier app, y no pide ningún permiso: lee únicamente el archivo que se le entrega.

## Qué abre

| Tipo | Detalles |
|---|---|
| Texto y código | Números de línea, resaltado de sintaxis, búsqueda, ir a una línea, ajuste de línea opcional. UTF-8, UTF-16 y Windows-1252 |
| JSON | Se sangra solo si viene en una única línea; se puede alternar con el original |
| CSV y TSV | Como tabla con cabecera fija, búsqueda por filas e ir a una fila. Detecta el separador |
| Markdown | Con formato o en código fuente |
| Partidas PGN | Como texto resaltado, con botones para analizarlas en chess.com o en Lichess (si hay varias, se elige cuál) |
| Libros ePub | Lector por páginas (una o dos por pantalla) o en vertical continuo, con índice, tamaño y tipo de letra, temas claro, sepia y oscuro, pantalla completa y recuerdo del punto de lectura. Sin DRM |
| Imágenes | JPG, PNG, WebP, GIF, HEIC, AVIF, BMP y SVG, con zoom |
| PDF | Páginas continuas, zoom con dos dedos o doble toque, búsqueda de texto y vista solo texto |
| Cualquier otro | Volcado hexadecimal |

El tipo se decide por el contenido del archivo, no por su extensión. Desde el menú se puede forzar la vista de texto o la hexadecimal.

## Estantería

La pestaña «Libros» guarda los ePub abiertos y los de las carpetas que se añadan (se analizan también las subcarpetas). Muestra portada y progreso, y permite quitar libros o carpetas sin borrar los archivos. «Volver a analizar carpetas» recoge los libros nuevos y quita los que ya no están.

Los ePub que llegan desde otra app sin permiso duradero se copian dentro de Visor para que sigan en la estantería; al quitarlos, se borra esa copia.

## Lector de libros

Por defecto se lee por páginas: un toque en el lateral derecho avanza, en el izquierdo retrocede y en el centro muestra las barras; también se puede deslizar. En pantallas anchas se ven dos páginas, y se puede forzar una o dos. En «Aa» se cambia a vertical continuo, el tamaño y el tipo de letra (incluida Atkinson Hyperlegible, pensada para baja visión) y el tema.

También se pasa página con los botones de volumen, el teclado o los botones de la barra inferior, y arrastrando la barra de progreso se salta a cualquier punto del libro.

El código que traiga un libro no se ejecuta, la app no tiene permiso de internet, y un libro no puede abrir enlaces por su cuenta: los externos piden confirmación al tocarlos.

## Otras funciones

Todos los archivos se pueden compartir con otra app desde la barra superior. El texto se selecciona con una pulsación larga, y «Copiar todo el texto» está en el menú, igual que «Imprimir» (impresora o guardar como PDF).

Límites:

- El visor de texto carga los primeros 4 MB y el hexadecimal los primeros 8 MB.
- La búsqueda y la extracción de texto en PDF necesitan Android 15 o posterior. Los PDF escaneados no tienen texto que buscar.
- Los PDF con contraseña no se abren.
- El portapapeles admite hasta unos 400 000 caracteres.
- La selección con el dedo no pasa de lo que cabe en pantalla; para tramos largos está «Copiar líneas…».

## Instalar

Descarga el APK de la [última release](https://github.com/mosqueteroweb/visor/releases/latest).

## Compilar

Hace falta JDK 17 y el SDK de Android (`local.properties` con `sdk.dir`).

```bash
./gradlew testDebugUnitTest assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`. Sin clave de distribución se firma con la de depuración, que solo sirve para pruebas.

La compilación verifica la suma de cada dependencia (`gradle/verification-metadata.xml`). Al cambiar una versión hay que regenerarla con una caché vacía (`GRADLE_USER_HOME` temporal y `--write-verification-metadata sha256`) y añadir a mano la variante `-linux` de `aapt2`, que es la que usa GitHub.

## Publicar

GitHub compila, firma y publica el APK al recibir una etiqueta `vX.Y.Z`:

```bash
./scripts/bump-version.sh patch
git commit -am "chore(release): nueva versión"
./scripts/publish-release.sh
```

La clave de firma se crea una sola vez con `scripts/setup-signing.sh`, que la guarda en `~/.config/visor/` y como secretos del repositorio.

## Estructura

- `core/`: detección de tipo, decodificación de texto, resaltado y recientes. Sin interfaz, con tests.
- `viewers/`: un visor por tipo (texto, imagen, PDF, hexadecimal).
- `ViewerActivity`: recibe los archivos y elige el visor. `MainActivity`: inicio y recientes.

## Licencia

Visor es software libre bajo la licencia GNU GPLv3 (ver `LICENSE`). Las bibliotecas que incluye y sus licencias se pueden consultar en la propia app, en «Autoría».
