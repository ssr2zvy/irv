#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
npm ci --prefix frontend
npm run build --prefix frontend
rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -R frontend/dist/. backend/src/main/resources/static/
: "${IRV_ENCRYPTION_KEY:=$(openssl rand -base64 32)}"
export IRV_ENCRYPTION_KEY
mvn -f backend/pom.xml clean verify
mkdir -p dist
cp backend/target/irv-0.1.0.jar dist/irv.jar
python3 -m unittest discover -s agent -p 'test_*.py'
