# Visor

Visor de archivos para Android. Solo muestra: no edita ni modifica nada.

Aparece en «Abrir con» y en «Compartir» de cualquier app, y no pide ningún permiso: lee únicamente el archivo que se le entrega.

## Qué abre

| Tipo | Detalles |
|---|---|
| Texto y código | Números de línea, resaltado de sintaxis, búsqueda, ajuste de línea opcional. UTF-8, UTF-16 y Windows-1252 |
| Markdown | Con formato o en código fuente |
| Imágenes | JPG, PNG, WebP, GIF, HEIC, AVIF, BMP y SVG, con zoom |
| PDF | Páginas continuas, zoom con dos dedos o doble toque |
| Cualquier otro | Volcado hexadecimal |

El tipo se decide por el contenido del archivo, no por su extensión. Desde el menú se puede forzar la vista de texto o la hexadecimal.

Límites: el visor de texto carga los primeros 4 MB y el hexadecimal los primeros 8 MB. Los PDF con contraseña no se abren.

## Compilar

Hace falta JDK 17 y el SDK de Android (`local.properties` con `sdk.dir`).

```bash
./gradlew testDebugUnitTest assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`. Se firma con la clave de depuración de la máquina que compila, así que las actualizaciones deben salir siempre del mismo equipo.

La versión está en `version.properties` y se sube con `scripts/bump-version.sh patch|minor|major`.

## Estructura

- `core/`: detección de tipo, decodificación de texto, resaltado y recientes. Sin interfaz, con tests.
- `viewers/`: un visor por tipo (texto, imagen, PDF, hexadecimal).
- `ViewerActivity`: recibe los archivos y elige el visor. `MainActivity`: inicio y recientes.
