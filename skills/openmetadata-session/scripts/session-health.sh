#!/usr/bin/env bash

set -uo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
EXPECTED_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)"
WARNINGS=0
BLOCKERS=0

warn() { WARNINGS=$((WARNINGS + 1)); }
block() { BLOCKERS=$((BLOCKERS + 1)); }

if ! REPO_ROOT="$(git -C "$EXPECTED_ROOT" rev-parse --show-toplevel 2>/dev/null)"; then
  printf '%s\n' "status=blocked" "blockers=1" "warnings=0" "reason=not-a-git-repository"
  exit 2
fi

[ "$REPO_ROOT" = "$EXPECTED_ROOT" ] || block
case "$REPO_ROOT" in
  /mnt/*) FILESYSTEM="windows-mount"; warn ;;
  *) FILESYSTEM="linux" ;;
esac

BRANCH="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
HEAD="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null || true)"
UPSTREAM="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)"
[ -n "$BRANCH" ] || BRANCH="detached"
[ -n "$HEAD" ] || HEAD="unknown"
[ -n "$UPSTREAM" ] || UPSTREAM="none"

WORKTREE="$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all 2>/dev/null || true)"
TRACKED="$(printf '%s\n' "$WORKTREE" | awk 'NF && substr($0,1,2)!="??"{n++} END{print n+0}')"
UNTRACKED="$(printf '%s\n' "$WORKTREE" | awk 'NF && substr($0,1,2)=="??"{n++} END{print n+0}')"
if [ "$TRACKED" -gt 0 ] || [ "$UNTRACKED" -gt 0 ]; then DIRTY="dirty"; else DIRTY="clean"; fi

if git -C "$REPO_ROOT" diff --check -- . >/dev/null 2>&1 &&
   git -C "$REPO_ROOT" diff --cached --check -- . >/dev/null 2>&1; then
  DIFF_CHECK="ok"
else
  DIFF_CHECK="warning"; warn
fi

AUTOCRLF="$(git -C "$REPO_ROOT" config --get core.autocrlf 2>/dev/null || true)"
[ -n "$AUTOCRLF" ] || AUTOCRLF="unset"
[ "$FILESYSTEM:$AUTOCRLF" != "linux:true" ] || warn

CRLF="$(git -C "$REPO_ROOT" ls-files --eol 2>/dev/null |
  awk '$1=="i/lf" && $2=="w/crlf"{n++} END{print n+0}')"
[ "$CRLF" -eq 0 ] || warn

STALE_REFS=0
if command -v rg >/dev/null 2>&1; then
  for TARGET in "$REPO_ROOT/.codex" "$REPO_ROOT/.serena" "$REPO_ROOT/.claude" "$REPO_ROOT/.devcontainer"; do
    [ -e "$TARGET" ] || continue
    MATCHES="$(rg -l -i -F --hidden --no-messages "/mnt/d/neuralforge/openmetadata-oracle" "$TARGET" 2>/dev/null || true)"
    COUNT="$(printf '%s\n' "$MATCHES" | awk 'NF{n++} END{print n+0}')"
    STALE_REFS=$((STALE_REFS + COUNT))
  done
fi
[ "$STALE_REFS" -eq 0 ] || warn

if [ -L "$REPO_ROOT/AGENTS.md" ] && cmp -s "$REPO_ROOT/AGENTS.md" "$REPO_ROOT/CLAUDE.md"; then
  AGENTS_SYNC="ok"
else
  AGENTS_SYNC="warning"; warn
fi

HANDOFF_COUNT=0
LATEST="none"
for HANDOFF in "$REPO_ROOT"/docs/codex-work/*.md; do
  [ -f "$HANDOFF" ] || continue
  [ "$(basename "$HANDOFF")" = "README.md" ] && continue
  HANDOFF_COUNT=$((HANDOFF_COUNT + 1))
  if [ "$LATEST" = "none" ] || [ "$HANDOFF" -nt "$LATEST" ]; then LATEST="$HANDOFF"; fi
done
if [ "$LATEST" != "none" ]; then
  LATEST="$(printf '%s' "$LATEST" | sed "s#^$REPO_ROOT/##")"
else
  warn
fi

STATUS="ok"; EXIT_CODE=0
if [ "$BLOCKERS" -gt 0 ]; then STATUS="blocked"; EXIT_CODE=2
elif [ "$WARNINGS" -gt 0 ]; then STATUS="warning"; EXIT_CODE=1
fi

printf '%s\n' \
  "status=$STATUS" "blockers=$BLOCKERS" "warnings=$WARNINGS" \
  "repo_root=$REPO_ROOT" "filesystem=$FILESYSTEM" "branch=$BRANCH" "head=$HEAD" \
  "upstream=$UPSTREAM" "worktree=$DIRTY" "tracked_changes=$TRACKED" \
  "untracked_changes=$UNTRACKED" "diff_check=$DIFF_CHECK" "core_autocrlf=$AUTOCRLF" \
  "lf_index_crlf_worktree=$CRLF" "stale_mount_refs=$STALE_REFS" \
  "agents_sync=$AGENTS_SYNC" "handoff_files=$HANDOFF_COUNT" "latest_handoff=$LATEST"

exit "$EXIT_CODE"
