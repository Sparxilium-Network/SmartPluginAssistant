#!/usr/bin/env bash
set -e

echo "=================================================="

echo "  Building Smart Plugin Assistant for Linux...     "

echo "=================================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "[1/3] Packaging Maven dependencies..."
chmod +x ./mvnw || true
./mvnw clean package -DskipTests

echo "[2/3] Preparing lib folder..."
./mvnw dependency:copy-dependencies -DoutputDirectory=target/lib
cp target/SmartPluginAssistant-1.0-SNAPSHOT.jar target/lib/SmartPluginAssistant.jar

echo "[3/3] Creating Linux Application with jpackage..."
rm -rf dist/SmartPluginAssistant

jpackage \
  --type app-image \
  --name "SmartPluginAssistant" \
  --input "target/lib" \
  --main-jar "SmartPluginAssistant.jar" \
  --main-class "com.sparxilium.smartpluginassistant.Launcher" \
  --dest "dist"

echo ""
echo "=================================================="

echo "  BUILD SUCCESSFUL!"

echo "  Executable located at: dist/SmartPluginAssistant/bin/SmartPluginAssistant"
echo "=================================================="
