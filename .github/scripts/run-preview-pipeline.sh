#!/usr/bin/env bash
# run-preview-pipeline.sh — Run the compose-preview pipeline with custom
# per-build-system branch names, bypassing the apply action's hardcoded
# branch defaults.
#
# Downloads the upstream pipeline scripts at a pinned SHA and runs them with
# caller-supplied branch names. Handles both baseline mode (push to main) and
# comment mode (PR preview diff + sticky comment).
#
# Upstream tracking: yschimke/compose-ai-tools#<tbd> — once the apply action
# gains `baseline-branch`/`pr-branch` inputs this script can be replaced by
# a direct `uses:` call.
#
# Required env:
#   PIPELINE              — "compose" or "resources"
#   BASELINE_BRANCH       — e.g. "compose-preview/scripting/main"
#   PR_HEAD_BRANCH        — e.g. "compose-preview/scripting/pr"
#   COMMENT_MARKER        — HTML marker for sticky PR comment identity,
#                           e.g. "<!-- preview-diff-scripting -->"
#   REPO                  — github.repository (owner/name)
#   GITHUB_TOKEN_INLINE   — github.token (for git push)
#   GH_TOKEN              — github.token (for gh CLI in comment steps)
#
# Optional env:
#   PR_NUMBER             — PR number (comment mode only)
#   SKIP_RENDER           — "true"|"false" (default false)
#   COMMENT_ON_EMPTY_DIFF — "true"|"false" (default false)
#   RENDER_TIMEOUT        — seconds (default 600)
#   MODE_OVERRIDE         — force "baseline"|"comment"|"skip"

set -euo pipefail

: "${PIPELINE:?PIPELINE required (compose|resources)}"
: "${BASELINE_BRANCH:?BASELINE_BRANCH required}"
: "${PR_HEAD_BRANCH:?PR_HEAD_BRANCH required}"
: "${COMMENT_MARKER:?COMMENT_MARKER required}"
: "${REPO:?REPO required}"
: "${GITHUB_TOKEN_INLINE:?GITHUB_TOKEN_INLINE required}"

PR_NUMBER="${PR_NUMBER:-}"
SKIP_RENDER="${SKIP_RENDER:-false}"
COMMENT_ON_EMPTY_DIFF="${COMMENT_ON_EMPTY_DIFF:-false}"
RENDER_TIMEOUT="${RENDER_TIMEOUT:-600}"
GH_TOKEN="${GH_TOKEN:-$GITHUB_TOKEN_INLINE}"

# Resolve mode ----------------------------------------------------------------
if [ -n "${MODE_OVERRIDE:-}" ]; then
  MODE="$MODE_OVERRIDE"
else
  case "${GITHUB_EVENT_NAME:-push}" in
    pull_request|pull_request_target) MODE="comment" ;;
    push)
      # Baseline only on the development branch (default main)
      if [ "${GITHUB_REF_NAME:-main}" = "${DEVELOPMENT_BRANCH:-main}" ]; then
        MODE="baseline"
      else
        echo "run-preview-pipeline: push to non-main ref — skipping."
        exit 0
      fi ;;
    workflow_dispatch)
      if [ "${GITHUB_REF_NAME:-main}" = "${DEVELOPMENT_BRANCH:-main}" ]; then
        MODE="baseline"
      else
        MODE="comment"
      fi ;;
    *)
      echo "run-preview-pipeline: unrecognised event '${GITHUB_EVENT_NAME:-?}' — skipping."
      exit 0 ;;
  esac
fi

echo "run-preview-pipeline: pipeline=$PIPELINE mode=$MODE baseline=$BASELINE_BRANCH pr_head=$PR_HEAD_BRANCH"

# Download pipeline scripts from pinned SHA -----------------------------------
PINNED_REF="631e26944264ea1c52fb6a16f40ac8eea964c16c"
SCRIPT_BASE="https://raw.githubusercontent.com/yschimke/compose-ai-tools/$PINNED_REF/.github/actions/apply"
export ACTION_PATH
ACTION_PATH="$(mktemp -d)"

mkdir -p "$ACTION_PATH/lib" "$ACTION_PATH/pipelines"
for f in \
    "lib/compare-previews.py" \
    "lib/push-branch.sh" \
    "lib/post-comment.sh" \
    "pipelines/compose.sh" \
    "pipelines/resources.sh"; do
  curl -fsSL "$SCRIPT_BASE/$f" -o "$ACTION_PATH/$f"
done
chmod +x \
  "$ACTION_PATH/lib/push-branch.sh" \
  "$ACTION_PATH/pipelines/compose.sh" \
  "$ACTION_PATH/pipelines/resources.sh"

# Install perceptual-diff deps (mirrors apply action) -------------------------
python3 -m pip install --quiet 'pixelmatch==0.4.0' 'Pillow>=10' \
  || echo "pixelmatch install failed; strict-bytes diff will be used" >&2

# Export env required by pipeline scripts -------------------------------------
export MODE
export REPO
export GITHUB_TOKEN_INLINE
export SKIP_RENDER
export COMMENT_ON_EMPTY_DIFF
export RENDER_TIMEOUT
export PR_NUMBER
# compose pipeline vars
export BASELINE_BRANCH
export PR_HEAD_BRANCH
# resources pipeline vars (aliased to the same custom branches)
export RESOURCE_BRANCH="$BASELINE_BRANCH"
export RESOURCE_HEAD_BRANCH="$PR_HEAD_BRANCH"

# Run pipeline ----------------------------------------------------------------
if [ "$PIPELINE" = "compose" ]; then
  bash "$ACTION_PATH/pipelines/compose.sh"
else
  bash "$ACTION_PATH/pipelines/resources.sh"
fi

# Push staged content ---------------------------------------------------------
push_stage() {
  local stage="$1"
  local sha_out="$2"
  if [ ! -d "$stage" ] || [ ! -f "$stage/_push_branch" ]; then
    echo "run-preview-pipeline: no staged content in $stage — skipping push."
    return 0
  fi
  export MSG
  MSG=$(cat "$stage/_push_msg")
  export TARGET_BRANCH
  TARGET_BRANCH=$(cat "$stage/_push_branch")
  export SKIP_IF_UNCHANGED
  SKIP_IF_UNCHANGED=$(cat "$stage/_skip_if_unchanged" 2>/dev/null || echo "0")
  export SHA_OUTPUT_FILE="$sha_out"
  rm -f "$stage/_push_msg" "$stage/_push_branch" "$stage/_skip_if_unchanged"
  (cd "$stage" && bash "$ACTION_PATH/lib/push-branch.sh")
}

if [ "$PIPELINE" = "compose" ]; then
  if [ "$MODE" = "baseline" ]; then
    push_stage "$GITHUB_WORKSPACE/_baselines" "$GITHUB_WORKSPACE/_baseline_pushed_sha"
  else
    push_stage "$GITHUB_WORKSPACE/_pr_renders" "$GITHUB_WORKSPACE/_head_sha"
  fi
else
  if [ "$MODE" = "baseline" ]; then
    push_stage "$GITHUB_WORKSPACE/_resource_baselines" "$GITHUB_WORKSPACE/_resource_baseline_pushed_sha"
  else
    push_stage "$GITHUB_WORKSPACE/_pr_resource_renders" "$GITHUB_WORKSPACE/_resource_head_sha"
  fi
fi

# Generate and post PR comment (comment mode only) ----------------------------
[ "$MODE" != "comment" ] && exit 0
[ -z "$PR_NUMBER" ] && exit 0

if [ "$PIPELINE" = "compose" ]; then
  COMMENT_FILE="$GITHUB_WORKSPACE/_comment_body.md"
  if [ -f _previews.json ] && [ -d _baselines ]; then
    BASE_REF=$(cat _base_sha 2>/dev/null || echo "$BASELINE_BRANCH")
    HEAD_REF=$(cat _head_sha 2>/dev/null || echo "$PR_HEAD_BRANCH")
    python3 "$ACTION_PATH/lib/compare-previews.py" compare _previews.json \
      --baselines _baselines/baselines.json \
      --baseline-renders _baselines/renders \
      --repo "$REPO" \
      --base-ref "$BASE_REF" \
      --head-ref "$HEAD_REF" \
      > "$COMMENT_FILE" || true
  fi
else
  COMMENT_FILE="$GITHUB_WORKSPACE/_comment_body_resources.md"
  if [ -f _resources.json ]; then
    RESOURCE_BASE_REF=$(cat _resource_base_sha 2>/dev/null || echo "")
    [ -z "$RESOURCE_BASE_REF" ] && RESOURCE_BASE_REF=$(cat _base_sha 2>/dev/null || echo "$BASELINE_BRANCH")
    RESOURCE_HEAD_REF=$(cat _resource_head_sha 2>/dev/null || echo "$PR_HEAD_BRANCH")
    python3 "$ACTION_PATH/lib/compare-previews.py" compare-resources \
      _resources.json \
      --baselines _baselines/resource-baselines.json \
      --baseline-renders _resource_baselines/renders \
      --repo "$REPO" \
      --base-ref "$RESOURCE_BASE_REF" \
      --head-ref "$RESOURCE_HEAD_REF" \
      > "$COMMENT_FILE" || true
  fi
fi

if [ ! -s "${COMMENT_FILE:-}" ]; then
  echo "run-preview-pipeline: empty comment body — nothing to post."
  exit 0
fi

# Replace the generic marker with the caller-supplied per-system marker so
# sticky comment upserts work correctly when multiple build systems run on
# the same PR and all post to the same PR thread.
DEFAULT_MARKER="<!-- preview-diff -->"
[ "$PIPELINE" = "resources" ] && DEFAULT_MARKER="<!-- preview-diff-resources -->"
sed -i "s|${DEFAULT_MARKER}|${COMMENT_MARKER}|g" "$COMMENT_FILE"

# Skip posting an empty-diff comment if not requested and no prior comment.
if grep -qF "No visual changes detected." "$COMMENT_FILE" && \
   [ "$COMMENT_ON_EMPTY_DIFF" != "true" ]; then
  EXISTING_ID=$(GH_TOKEN="$GH_TOKEN" gh api \
    "repos/${REPO}/issues/${PR_NUMBER}/comments" \
    --paginate \
    --jq ".[] | select(.body | startswith(\"${COMMENT_MARKER}\")) | .id" \
    | head -1 2>/dev/null || true)
  if [ -z "$EXISTING_ID" ]; then
    echo "run-preview-pipeline: no diff and no existing comment — skipping post."
    exit 0
  fi
fi

export GH_TOKEN
export MARKER="$COMMENT_MARKER"
export BODY_FILE="$COMMENT_FILE"
bash "$ACTION_PATH/lib/post-comment.sh"
