#!/usr/bin/env bash
# Publica el APK de release y el manifiesto que la app consulta para
# actualizarse sola por la red local.
#
#   tools/publicar.sh
#
# Deja los ficheros en build/publicar/. Para servirlos:
#   python -m http.server 8000 -d build/publicar
#   python tools/anunciador.py
# La IP se detecta sola.
set -euo pipefail

APP="s2000"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SERVE="$REPO/build/publicar"
PUERTO=8000
mkdir -p "$SERVE"

APK="$REPO/$APP/build/outputs/apk/release/$APP-release.apk"
[ -f "$APK" ] || { echo "No hay APK: $APK (gradle :$APP:assembleRelease)"; exit 1; }

IP=$(ipconfig | grep -A6 -i "Ethernet:" | grep -i "IPv4" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' || true)
[ -n "$IP" ] || IP=$(ipconfig | grep -i "IPv4" | grep -oE '192\.168\.[0-9]+\.[0-9]+' | head -1)

CODE=$(grep -oE 'versionCode = [0-9]+' "$REPO/$APP/build.gradle.kts" | grep -oE '[0-9]+')
NAME=$(grep -oE 'versionName = "[^"]+"' "$REPO/$APP/build.gradle.kts" | cut -d'"' -f2)
SIZE=$(stat -c %s "$APK")

# Nombre unico por version: un navegador o un proxy cacheando el APK viejo
# ya hizo perder un despliegue.
FILE="$APP-v${CODE}.apk"
cp "$APK" "$SERVE/$FILE"

cat > "$SERVE/version.json" <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "file": "$FILE",
  "url": "http://$IP:$PUERTO/$FILE",
  "size": $SIZE
}
JSON

echo "Publicado v$NAME (code $CODE, $SIZE bytes) en $IP:$PUERTO"
echo "  http://$IP:$PUERTO/version.json"
