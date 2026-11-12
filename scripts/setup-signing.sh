#!/usr/bin/env bash
# Se ejecuta UNA sola vez: crea la clave de firma de Visor y la guarda como secretos del repositorio.
#   - Clave y contraseña quedan en ~/.config/visor/ (fuera del repositorio). Haz copia: sin ella
#     no se pueden publicar actualizaciones que se instalen encima de las anteriores.
#   - GitHub recibe una copia cifrada como secretos, que solo usa el flujo de publicación.
set -euo pipefail
cd "$(dirname "$0")/.."

DIR="$HOME/.config/visor"
STORE="$DIR/visor-release.jks"
[[ ! -e "$STORE" ]] || { echo "Ya existe $STORE: no se sobrescribe." >&2; exit 1; }
KEYTOOL="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin/keytool"

mkdir -p "$DIR"; chmod 700 "$DIR"
PASS="$(openssl rand -hex 24)"
"$KEYTOOL" -genkeypair -keystore "$STORE" -storetype PKCS12 -alias visor -keyalg RSA -keysize 4096 \
  -validity 20000 -dname "CN=Visor" -storepass "$PASS" -keypass "$PASS"
printf 'KEYSTORE_PATH=%s\nKEYSTORE_PASSWORD=%s\nKEY_ALIAS=visor\nKEY_PASSWORD=%s\n' "$STORE" "$PASS" "$PASS" > "$DIR/signing.properties"
chmod 600 "$STORE" "$DIR/signing.properties"

base64 -i "$STORE" | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64
printf '%s' "$PASS" | gh secret set KEYSTORE_PASSWORD
printf '%s' "$PASS" | gh secret set KEY_PASSWORD
printf 'visor' | gh secret set KEY_ALIAS

echo "Hecho. Clave en $STORE (haz una copia de seguridad de $DIR)."
