#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
EXAMPLE_PATH="$ROOT_DIR/.env.example"
ENV_PATH="${PAYMENT_ENV_FILE:-$ROOT_DIR/.env}"

if [ ! -f "$EXAMPLE_PATH" ]; then
  echo ".env.example not found at $EXAMPLE_PATH" >&2
  exit 1
fi

if [ ! -f "$ENV_PATH" ]; then
  cp "$EXAMPLE_PATH" "$ENV_PATH"
  echo "Created .env from .env.example."
fi

set -a
. "$ENV_PATH"
set +a

if [ "$#" -gt 0 ] && [ "$1" = "--" ]; then
  shift
fi

if [ "$#" -eq 0 ]; then
  env | sort | awk '/^PAYMENT_/ { print }'
  exit 0
fi

exec "$@"
