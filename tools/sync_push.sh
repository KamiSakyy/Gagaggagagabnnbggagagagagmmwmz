#!/usr/bin/env bash
# Publishes the local tree to the session branch, keeping the artifacts the CI job commits
# (handoff/ and .ci/) that live only on the remote branch.
#
# The CI job pushes its own commits to the same branch while a build is running, so the local tree
# is published as a single fresh commit on top of whatever is already there.
set -euo pipefail

BRANCH="${1:-arena/01a0d529-gagaggagagabnnbggagagagagmmwmz}"
MESSAGE="${2:-}"

cd "$(dirname "$0")/.."

echo "--- забираю ветку с сервера"
git fetch -q origin "$BRANCH"
BEFORE=$(git rev-parse FETCH_HEAD)
echo "на сервере: $(git log --oneline -1 "$BEFORE")"

if [ -n "$(git status --porcelain)" ]; then
  echo "--- фиксирую незакоммиченные изменения"
  git add -A
  git commit -q -m "${MESSAGE:-работа над Echidna Studio}"
fi
MYTREE=$(git rev-parse HEAD^{tree})
MYMESSAGE=$(git log --format=%B -1 HEAD)

if git diff --quiet "$BEFORE" HEAD 2>/dev/null; then
  echo "--- изменений относительно сервера нет, ничего не публикую"
  exit 0
fi

echo "--- переношу своё дерево поверх серверной ветки"
git checkout -q -B "$BRANCH" "$BEFORE"
git read-tree --reset -u "$MYTREE"
# The CI job commits these; they are not part of the local tree.
for path in handoff .ci; do
  git checkout -q "$BEFORE" -- "$path" 2>/dev/null || true
done
git commit -q -m "$MYMESSAGE"
git log --oneline -3
echo "--- публикую"
git push origin "HEAD:$BRANCH"
echo "ГОТОВО: $(git rev-parse --short HEAD)"
