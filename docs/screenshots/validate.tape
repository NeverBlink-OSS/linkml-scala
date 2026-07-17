# VHS tape for the `validate` command demo GIF.
# Regenerate with: docs/screenshots/generate.sh
# (that script builds the CLI, puts a `linkml-scala` launcher on PATH, and runs vhs)

Output docs/img/validate.gif

Require linkml-scala

Set Shell bash
Set FontFamily "JetBrains Mono"
Set FontSize 16
Set Width 1580
Set Height 350
Set Padding 24
Set Margin 0
Set BorderRadius 0
Set Theme "Catppuccin Mocha"
Set TypingSpeed 55ms
Set PlaybackSpeed 1.0

# Use a clean prompt and run from the example folder so the typed path stays short.
Hide
Type "export PS1='$ '" Enter
Type "cd docs/examples" Enter
Type "clear" Enter
Show

Sleep 600ms
Type "linkml-scala validate with_errors.yaml"
Sleep 700ms
Enter

Sleep 5.5s
