#!/bin/sh
# Files a failed nightly image scan (ADR-0113 amendment): comments on the open issue with this exact
# title, or opens it. Needs GH_TOKEN, GH_REPO and RUN_URL; the body names no package (an npm scope
# would mention a GitHub account).
set -eu

title='Nightly image scan is failing'
number=$(gh issue list --state open --search "\"$title\" in:title" --json number,title |
  jq -r --arg title "$title" 'map(select(.title == $title)) | first | .number // empty')

if [ -n "$number" ]; then
  gh issue comment "$number" --body "Still failing: $RUN_URL"
else
  gh issue create --title "$title" --body "The \`image-scan\` job of $RUN_URL failed: a High or Critical finding with a fixed version, or a build or scan error. Its job summary lists the findings and the versions that fix them; the grype reports are the run's \`image-scan\` artifact. The job is report-first (ADR-0113), so the run itself stays green. Close this issue once a run passes."
fi
