# Visor

Visor de archivos para Android. Solo muestra: no edita ni modifica nada.

Aparece en «Abrir con» y en «Compartir» de cualquier app, y no pide ningún permiso: lee únicamente el archivo que se le entrega.

## Qué abre

| Tipo | Detalles |
|---|---|
| Texto y código | Números de línea, resaltado de sintaxis, búsqueda, ajuste de línea opcional. UTF-8, UTF-16 y Windows-1252 |
| Markdown | Con formato o en código fuente |
| Partidas PGN | Como texto resaltado, con un botón para analizarlas en chess.com (si hay varias, se elige cuál) |
| Imágenes | JPG, PNG, WebP, GIF, HEIC, AVIF, BMP y SVG, con zoom |
| PDF | Páginas continuas, zoom con dos dedos o doble toque, búsqueda de texto y vista solo texto |
| Cualquier otro | Volcado hexadecimal |

El tipo se decide por el contenido del archivo, no por su extensión. Desde el menú se puede forzar la vista de texto o la hexadecimal.

Todos los archivos se pueden compartir con otra app desde la barra superior. El texto se selecciona con una pulsación larga, y «Copiar todo el texto» está en el menú.

Límites:

- El visor de texto carga los primeros 4 MB y el hexadecimal los primeros 8 MB.
- La búsqueda y la extracción de texto en PDF necesitan Android 15 o posterior. Los PDF escaneados no tienen texto que buscar.
- Los PDF con contraseña no se abren.
- El portapapeles admite hasta unos 400 000 caracteres.

## Instalar

Descarga el APK de la [última release](https://github.com/mosqueteroweb/visor/releases/latest).

## Compilar

Hace falta JDK 17 y el SDK de Android (`local.properties` con `sdk.dir`).

```bash
./gradlew testDebugUnitTest assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`. Sin clave de distribución se firma con la de depuración, que solo sirve para pruebas.

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
