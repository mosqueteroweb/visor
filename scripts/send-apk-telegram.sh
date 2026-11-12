#!/usr/bin/env bash
# Envía el APK más reciente a Telegram mediante el bot @Antigravity01mac_bot.
#
# Configuración (una sola vez, fuera del repositorio):
#   ~/.config/aulix/telegram.env  (chmod 600) con:
#     TELEGRAM_BOT_TOKEN=<token de @BotFather>
#     TELEGRAM_CHAT_ID=<tu chat id>
#   También se pueden pasar como variables de entorno.
#
# Uso:
#   ./scripts/send-apk-telegram.sh                      -> envía app/build/outputs/apk/release/visor-*-release.apk
#   ./scripts/send-apk-telegram.sh ruta.apk             -> envía ese APK
#   ./scripts/send-apk-telegram.sh -m "resumen" [apk]   -> añade un resumen de cambios al mensaje
#   ./scripts/send-apk-telegram.sh --chat-id            -> muestra los chat id que han escrito al bot
#
# El resumen debe ser corto, con iconos y poco texto, una línea por cambio, p. ej.:
#   -m $'🔔 La alarma vuelve tras reiniciar\n📝 Las notas del mismo día se suman'
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${AULIX_TELEGRAM_CONFIG:-$HOME/.config/aulix/telegram.env}"
if [[ -f "$CONFIG" ]]; then
  set -a; source "$CONFIG"; set +a
fi

if [[ -z "${TELEGRAM_BOT_TOKEN:-}" ]]; then
  echo "Falta TELEGRAM_BOT_TOKEN (en $CONFIG o como variable de entorno)." >&2
  exit 1
fi
API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

if [[ "${1:-}" == "--chat-id" ]]; then
  # Escribe cualquier mensaje al bot antes de ejecutar esto
  curl -sS "$API/getUpdates" | python3 -c '
import json, sys
data = json.load(sys.stdin)
chats = {u["message"]["chat"]["id"]: u["message"]["chat"].get("username") or u["message"]["chat"].get("first_name") for u in data.get("result", []) if "message" in u}
print("\n".join(f"{cid}  ({name})" for cid, name in chats.items()) or "Sin mensajes: escribe primero al bot.")'
  exit 0
fi

if [[ -z "${TELEGRAM_CHAT_ID:-}" ]]; then
  echo "Falta TELEGRAM_CHAT_ID. Escribe al bot y ejecuta: $0 --chat-id" >&2
  exit 1
fi

SUMMARY=""
if [[ "${1:-}" == "-m" ]]; then
  SUMMARY="${2:-}"
  shift 2
fi

APK="${1:-}"
if [[ -z "$APK" ]]; then
  APK=$(ls -t "$ROOT"/app/build/outputs/apk/release/visor-*-release.apk 2>/dev/null | head -1 || true)
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "No hay APK. Compila antes con ./gradlew assembleRelease" >&2
  exit 1
fi

BRANCH=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)
COMMIT=$(git -C "$ROOT" log -1 --format='%h %s')
VERSION=$(basename "$APK" | sed -E 's/^visor-(.*)-release\.apk$/\1/')
CAPTION="📦 Visor $VERSION"
if [[ -n "$SUMMARY" ]]; then
  CAPTION="$CAPTION

$SUMMARY"
fi
CAPTION="$CAPTION

🌿 $BRANCH · $(echo "$COMMIT" | cut -d' ' -f1)"
# Telegram admite como máximo 1024 caracteres en el texto de un archivo
CAPTION="${CAPTION:0:1024}"

RESPONSE=$(curl -sS -F "chat_id=$TELEGRAM_CHAT_ID" -F "caption=$CAPTION" -F "document=@$APK" "$API/sendDocument")
if echo "$RESPONSE" | grep -q '"ok":true'; then
  echo "Enviado a Telegram: $(basename "$APK")"
else
  echo "Error al enviar: $(echo "$RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("description","respuesta desconocida"))')" >&2
  exit 1
fi
