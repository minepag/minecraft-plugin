#!/usr/bin/env bash
# Atualiza protocol/minecraft-agent.openapi.json com o contrato publicado pela MinePag e roda os testes de contrato.
# Uso: scripts/sync-protocol.sh [URL ou caminho do arquivo]
#   Padrão: https://docs.minepag.com.br/minecraft-agent.openapi.json
#   Com o monorepo ao lado: scripts/sync-protocol.sh ../minepag/docs-site/minecraft-agent.openapi.json
set -euo pipefail
cd "$(dirname "$0")/.."

source="${1:-https://docs.minepag.com.br/minecraft-agent.openapi.json}"
target="protocol/minecraft-agent.openapi.json"

if [[ "$source" == http* ]]; then
  curl -fsSL "$source" -o "$target"
else
  cp "$source" "$target"
fi

git --no-pager diff --stat -- "$target"
./gradlew test --tests '*ProtocolContractTest' --no-daemon
