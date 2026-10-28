#!/usr/bin/env bash
# Sync this fork with upstream AirTAK.
#
#   upstream = https://git.crashcat.net/3phedra/AirTAK  (read-only source of truth)
#   origin   = https://github.com/DByte308/AirTAK       (your fork)
#
# Refuses to force-push. If you have local commits that diverge from upstream,
# it stops cleanly and leaves everything untouched so you can resolve by hand.
#
# Usage:  ./tools/sync_upstream.sh [--dry-run]

set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."

DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mxx %s\033[0m\n' "$*" >&2; exit 1; }

BRANCH="$(git symbolic-ref --quiet --short HEAD)" || die "detached HEAD; check out a branch first"
say "branch: $BRANCH"

git fetch --prune upstream
git fetch --prune origin

UP="$(git rev-parse "upstream/$BRANCH")"
LOCAL="$(git rev-parse "$BRANCH")"
REMOTE="$(git rev-parse "origin/$BRANCH" 2>/dev/null || echo none)"

say "upstream: $UP"
say "local:    $LOCAL"
say "origin:   $REMOTE"

if [[ "$UP" == "$LOCAL" && "$UP" == "$REMOTE" ]]; then
  say "already up to date — nothing to do"
  exit 0
fi

# Local work that is not in upstream? Then a fast-forward is impossible.
if [[ -n "$(git rev-list --max-count=1 "$LOCAL" "^upstream/$BRANCH")" ]]; then
  echo
  say "you have local commits not present in upstream:"
  git --no-pager log --oneline "upstream/$BRANCH..$LOCAL"
  die "diverged. Rebase or merge by hand, then re-run. Nothing was changed."
fi

if [[ "$DRY" == "1" ]]; then
  say "[dry-run] would fast-forward $LOCAL -> $UP and push to origin"
  git --no-pager log --oneline "$LOCAL..$UP"
  exit 0
fi

say "fast-forwarding to $UP"
git merge --ff-only "upstream/$BRANCH"

say "pushing to origin/$BRANCH (fast-forward only)"
git push --porcelain origin "$BRANCH"

git --no-pager log --oneline -5
say "done — fork now matches upstream at $UP"
