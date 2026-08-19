#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
EXAMPLE_PATH="$ROOT_DIR/.env.example"
ENV_PATH="$ROOT_DIR/.env"

if [ ! -f "$EXAMPLE_PATH" ]; then
  echo ".env.example not found at $EXAMPLE_PATH" >&2
  exit 1
fi

if [ -f "$ENV_PATH" ]; then
  echo ".env already exists; leaving it unchanged."
  exit 0
fi

cp "$EXAMPLE_PATH" "$ENV_PATH"
echo "Created .env from .env.example."

