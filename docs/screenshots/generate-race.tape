# VHS tape: side-by-side `generate shacl` race, native linkml-scala vs Python linkml.
# Regenerate with: docs/screenshots/generate-comparison.sh
# (builds the native image, sets up a Python 3.14 linkml venv, puts both on PATH)

Output docs/img/generate-race.gif

Require linkml-scala
Require linkml
Require tmux

Set Shell bash
Set FontFamily "JetBrains Mono"
Set FontSize 16
Set Width 1500
Set Height 300
Set Padding 12
Set Margin 0
Set BorderRadius 0
Set Theme "Catppuccin Mocha"

# Build the two-pane session with both commands pre-typed (hidden setup).
Hide
Type "cd docs/examples" Enter
Type "source ../screenshots/race-setup.sh core.yaml" Enter
Type "clear" Enter
# Schedule the synchronized start, then attach so we record the panes.
Type "bash ../screenshots/race-go.sh 1.6 & tmux attach -t race" Enter
Sleep 300ms
Show

# ~1.6s of both commands sitting ready, then they fire together:
# native finishes almost instantly, Python keeps going for ~2s.
Sleep 8s
