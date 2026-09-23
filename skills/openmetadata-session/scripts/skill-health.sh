#!/usr/bin/env bash

set -uo pipefail

if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [ "$1" != "--summary" ]; }; then
  printf '%s\n' 'Usage: bash skill-health.sh [--summary]' >&2
  exit 2
fi
SUMMARY_ONLY="${1:-}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
CHECKED=0
ISSUES=0

issue() {
  ISSUES=$((ISSUES + 1))
  printf 'skill_issue=%s:%s\n' "$ENTRY_PATH" "$1"
}

if ! INDEX_ENTRIES="$(git -C "$REPO_ROOT" ls-files --stage -- .agents/skills .claude/skills)"; then
  printf '%s\n' 'skill_files=unverified' 'skill_agent_visibility=unverified'
  exit 2
fi

# The Git index retains expected links even when a checkout materializes them as plain files.
while IFS= read -r INDEX_ENTRY; do
  [ -n "$INDEX_ENTRY" ] || continue
  ENTRY_PATH="${INDEX_ENTRY#*$'\t'}"
  INDEX_METADATA="${INDEX_ENTRY%%$'\t'*}"
  [ "${INDEX_METADATA%% *}" = "120000" ] || continue
  # This directory contains agent templates, not a skill entrypoint.
  [ "$ENTRY_PATH" != '.claude/skills/agents' ] || continue
  CHECKED=$((CHECKED + 1))
  ENTRY="$REPO_ROOT/$ENTRY_PATH"
  if [ ! -L "$ENTRY" ]; then
    issue missing-or-materialized-link
    continue
  fi
  LINK_TARGET="$(readlink "$ENTRY")"
  case "$LINK_TARGET" in
    /*) issue absolute-link; continue ;;
  esac
  BLOB_ID="${INDEX_METADATA#* }"
  BLOB_ID="${BLOB_ID%% *}"
  if ! EXPECTED_TARGET="$(git -C "$REPO_ROOT" cat-file -p "$BLOB_ID")"; then
    issue unreadable-index-entry
    continue
  fi
  if [ "$LINK_TARGET" != "$EXPECTED_TARGET" ]; then
    issue changed-link-target
    continue
  fi
  if ! CANONICAL_DIR="$(CDPATH= cd -- "$ENTRY" 2>/dev/null && pwd -P)"; then
    issue broken-link
    continue
  fi
  case "$CANONICAL_DIR" in
    "$REPO_ROOT"/skills/*) ;;
    *) issue target-outside-project-skills; continue ;;
  esac
  if [ ! -f "$CANONICAL_DIR/SKILL.md" ] || [ ! -r "$CANONICAL_DIR/SKILL.md" ]; then
    issue missing-or-unreadable-SKILL.md
    continue
  fi
  if [ "$SUMMARY_ONLY" != '--summary' ]; then
    printf 'skill_present=%s -> %s/SKILL.md\n' "$ENTRY_PATH" "${CANONICAL_DIR#"$REPO_ROOT/"}"
  fi
done <<< "$INDEX_ENTRIES"

if [ "$CHECKED" -eq 0 ]; then
  ENTRY_PATH='project'
  issue no-tracked-skill-links
fi
STATUS=ok
[ "$ISSUES" -eq 0 ] || STATUS=warning
printf '%s\n' "skill_files=$STATUS" "skill_entries_checked=$CHECKED" "skill_entry_issues=$ISSUES" \
  'skill_agent_visibility=unverified'
[ "$ISSUES" -eq 0 ]
