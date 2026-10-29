#!/usr/bin/env bash
#
# Starts the pre-typed commands in both race panes at (as near as possible to)
# the same instant, after an initial delay so the viewer sees them ready first.
# Used by generate-race.tape (backgrounded before `tmux attach`).

set -euo pipefail

sleep "${1:-1.6}"
tmux send-keys -t race.0 Enter \; send-keys -t race.1 Enter
