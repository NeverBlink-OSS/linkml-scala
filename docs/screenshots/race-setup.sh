#!/usr/bin/env bash
#
# Sets up a two-pane tmux "race" session for the generate-shacl comparison GIF:
#   left pane  = native linkml-scala
#   right pane = python linkml (Python 3.14)
#
# Each pane gets the timed command *pre-typed but not run*. race-go.sh then
# presses Enter in both panes back-to-back, so they start together.
#
# Run from the directory that holds the schema; both `linkml-scala` and `linkml`
# must be on PATH. Used by generate-comparison.sh.
#
# IMPORTANT: this must be *sourced* (not run as `bash race-setup.sh`). A detached
# tmux server started by a short-lived subprocess gets reaped when that process
# exits; sourcing keeps it owned by the long-lived (VHS) shell.

SESSION=race
SCHEMA="${1:-core.yaml}"

LEFT="time sh -c 'linkml-scala generate shacl $SCHEMA >/dev/null 2>&1'"
RIGHT="time sh -c 'linkml generate shacl $SCHEMA >/dev/null 2>&1'"

# The pane shells are interactive and re-source ~/.bashrc, which can reset PATH
# and PS1 — so re-apply our PATH (with linkml-scala + linkml), a clean prompt,
# and C locale (dot decimals in `time` output) inside each pane.
# PS1 is a plain "$ " (not a fancy glyph): the panes run under LC_ALL=C for
# dot-decimal `time` output, and multibyte prompt glyphs don't render there.
PANE_INIT="export PATH=\"$PATH\" PS1='$ ' LC_ALL=C; clear"

tmux kill-server 2>/dev/null || true

# Create the session first, THEN set options (options set before a live server
# exists are silently dropped in some environments).
tmux new-session -d -s "$SESSION"
tmux set -g status off
tmux set -g pane-border-status top
tmux set -g pane-border-format " #{pane_title} "
tmux split-window -h -t "$SESSION"

tmux select-pane -t "$SESSION".0 -T " linkml-scala (native) "
tmux select-pane -t "$SESSION".1 -T " linkml (Python 3.14) "

tmux send-keys -t "$SESSION".0 "$PANE_INIT" Enter
tmux send-keys -t "$SESSION".1 "$PANE_INIT" Enter

# Pre-type the command into each pane WITHOUT running it (no Enter).
tmux send-keys -t "$SESSION".0 "$LEFT"
tmux send-keys -t "$SESSION".1 "$RIGHT"
