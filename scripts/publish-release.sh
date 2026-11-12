#!/usr/bin/env bash
# Publica la versión de version.properties: sube main y crea la etiqueta vX.Y.Z.
# GitHub compila, firma y adjunta el APK a la release (ver .github/workflows/release.yml).
#   ./scripts/bump-version.sh patch && git commit -am "chore(release): ..." && ./scripts/publish-release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

get() { grep "^$1=" version.properties | cut -d= -f2; }
TAG="v$(get VERSION_MAJOR).$(get VERSION_MINOR).$(get VERSION_PATCH)"

[[ -z "$(git status --porcelain)" ]] || { echo "Hay cambios sin confirmar." >&2; exit 1; }
[[ "$(git branch --show-current)" == "main" ]] || { echo "Hay que publicar desde main." >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "La etiqueta $TAG ya existe: sube antes la versión con scripts/bump-version.sh." >&2; exit 1
fi

git push origin main
git tag "$TAG"
git push origin "$TAG"
echo "Publicando $TAG: https://github.com/mosqueteroweb/visor/actions"
