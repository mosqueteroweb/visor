#!/usr/bin/env bash
# Sube la versión de la app en version.properties.
#   ./scripts/bump-version.sh patch   -> actualización menor: 1.0.1 -> 1.0.2
#   ./scripts/bump-version.sh minor   -> actualización mayor: 1.0.2 -> 1.1.0
#   ./scripts/bump-version.sh major   -> paso a 2.x (solo cuando lo decida Pedro): 1.4.3 -> 2.0.0
# VERSION_CODE sube siempre en 1. Imprime la nueva versión.
set -euo pipefail

FILE="$(cd "$(dirname "$0")/.." && pwd)/version.properties"
KIND="${1:-}"

get() { grep "^$1=" "$FILE" | cut -d= -f2; }
set_prop() { sed -i '' "s/^$1=.*/$1=$2/" "$FILE"; }

MAJOR=$(get VERSION_MAJOR); MINOR=$(get VERSION_MINOR); PATCH=$(get VERSION_PATCH); CODE=$(get VERSION_CODE)

case "$KIND" in
  patch) PATCH=$((PATCH + 1)) ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  *) echo "Uso: $0 patch|minor|major" >&2; exit 1 ;;
esac
CODE=$((CODE + 1))

set_prop VERSION_MAJOR "$MAJOR"
set_prop VERSION_MINOR "$MINOR"
set_prop VERSION_PATCH "$PATCH"
set_prop VERSION_CODE "$CODE"

echo "$MAJOR.$MINOR.$PATCH ($CODE)"
