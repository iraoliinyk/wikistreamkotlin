#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:7000}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-30}"
SLEEP_SECONDS="${SLEEP_SECONDS:-10}"
EMAIL_DOMAIN="${EMAIL_DOMAIN:-example.com}"

register_url="${BASE_URL%/}/v1/auth/register"

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  email="astra-warmup-${attempt}-$(date +%s)@${EMAIL_DOMAIN}"

  http_code=$(curl -sS -o /dev/null -w "%{http_code}" \
    -X POST "$register_url" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"StrongPass#123\"}" || true)

  if [[ "$http_code" == "201" || "$http_code" == "409" ]]; then
    echo "Astra warm-up succeeded on attempt ${attempt} (HTTP ${http_code})."
    exit 0
  fi

  echo "Astra not ready yet (attempt ${attempt}/${MAX_ATTEMPTS}, HTTP ${http_code:-n/a}). Retrying in ${SLEEP_SECONDS}s..."
  sleep "$SLEEP_SECONDS"
done

echo "Astra warm-up failed after ${MAX_ATTEMPTS} attempts. Last endpoint: ${register_url}" >&2
exit 1

