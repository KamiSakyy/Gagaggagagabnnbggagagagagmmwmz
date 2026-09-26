#!/usr/bin/env bash
# Publishes the local tree to the session branch, keeping the artifacts the CI job commits
# (handoff/ and .ci/) that live only on the remote branch.
#
# The CI job pushes its own commits to the same branch while a build is running, so the local tree
# is published as a single fresh commit on top of whatever is already there. The artifacts are put
# into that commit straight from the server tree: the APK is tens of megabytes and there is no
# reason to keep a second copy of it on the build machine.
set -euo pipefail

BRANCH="${1:-arena/01a0d529-gagaggagagabnnbggagagagagmmwmz}"
MESSAGE="${2:-}"

cd "$(dirname "$0")/.."

# Артефакты сборки принадлежат серверу: локальные правки в них не коммитятся и не удаляют их.
ARTIFACTS=(":(exclude)handoff" ":(exclude).ci")

echo "--- забираю ветку с сервера"
git fetch -q origin "$BRANCH"
BEFORE=$(git rev-parse FETCH_HEAD)
echo "на сервере: $(git log --oneline -1 "$BEFORE")"

if [ -n "$(git status --porcelain -- . "${ARTIFACTS[@]}")" ]; then
  echo "--- фиксирую незакоммиченные изменения"
  git add -A -- . "${ARTIFACTS[@]}"
  git commit -q -m "${MESSAGE:-работа над Echidna Studio}"
fi
MYTREE=$(git rev-parse HEAD^{tree})
MYMESSAGE=$(git log --format=%B -1 HEAD)

if git diff --quiet "$BEFORE" HEAD -- . "${ARTIFACTS[@]}" 2>/dev/null; then
  echo "--- изменений относительно сервера нет, ничего не публикую"
  exit 0
fi

echo "--- собираю коммит: моё дерево плюс артефакты серверной ветки"
IDX=$(mktemp)
GIT_INDEX_FILE="$IDX" git read-tree "$MYTREE"
git ls-tree -r "$BEFORE" -- .ci handoff | GIT_INDEX_FILE="$IDX" git update-index --index-info
NEWTREE=$(GIT_INDEX_FILE="$IDX" git write-tree)
rm -f "$IDX"
NEWCOMMIT=$(git commit-tree "$NEWTREE" -p "$BEFORE" -m "$MYMESSAGE")
git update-ref "refs/heads/$BRANCH" "$NEWCOMMIT"
git read-tree "$NEWTREE"
# Артефакты лежат на сервере, а не на диске: чтобы git не считал их удалёнными, помечаю их
# пропущенными для рабочего дерева.
if [ -n "$(git ls-files handoff .ci)" ]; then
  git update-index --skip-worktree $(git ls-files handoff .ci)
fi
git log --oneline -3 "$BRANCH"
echo "--- публикую"
git push origin "$NEWCOMMIT:refs/heads/$BRANCH"
echo "ГОТОВО: $(git rev-parse --short "$NEWCOMMIT")"
