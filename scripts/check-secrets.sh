#!/usr/bin/env bash
set -euo pipefail

secret_patterns=(
  'sk-'"'[A-Za-z0-9]{20,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'AKIA'"'[0-9A-Z]{16}'
  'gh[pousr]_'"'[A-Za-z0-9_]{20,}'
  'github_pat_'"'[A-Za-z0-9_]{20,}'
)

if git diff --cached --binary --diff-filter=ACM --unified=0 | rg -n -I '^\\+[^+]' \
  -e "${secret_patterns[0]}" \
  -e "${secret_patterns[1]}" \
  -e "${secret_patterns[2]}" \
  -e "${secret_patterns[3]}" \
  -e "${secret_patterns[4]}" >/dev/null; then
  echo "Commit blocked: staged changes contain a possible secret." >&2
  exit 1
fi
