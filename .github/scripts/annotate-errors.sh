#!/bin/sh
# Re-emits a failed step's error lines as workflow annotations, which the checks API
# returns alongside the run: a reader who cannot fetch the full log still sees why it
# failed (GitHub keeps 10 error annotations per step). Usage: annotate-errors.sh <log>
log="$1"
sed 's/\x1b\[[0-9;]*m//g' "$log" \
  | grep -E 'ERROR|error:|Exception|FAILED|wanted by' \
  | grep -v 'JAVA_TOOL_OPTIONS' \
  | tail -n 10 \
  | while IFS= read -r line; do printf '::error::%s\n' "$(printf '%s' "$line" | cut -c1-900)"; done
