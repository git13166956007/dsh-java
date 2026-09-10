#!/usr/bin/env bash
set -euo pipefail

sk_pattern="${sk_prefix:-sk-}[A-Za-z0-9]{20,}"
aws_pattern="${aws_prefix:-AKIA}[0-9A-Z]{16}"
gh_pattern="${gh_prefix:-gh[pousr]_}[A-Za-z0-9_]{20,}"
github_pat_pattern="${github_pat_prefix:-github_pat_}[A-Za-z0-9_]{20,}"

if git diff --cached --binary --diff-filter=ACM --unified=0 | rg -n -I '^\+[^+]' | rg -n -I \
  -e "$sk_pattern" \
  -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' \
  -e "$aws_pattern" \
  -e "$gh_pattern" \
  -e "$github_pat_pattern" >/dev/null; then
  echo "Commit blocked: staged changes contain a possible secret." >&2
  exit 1
fi
