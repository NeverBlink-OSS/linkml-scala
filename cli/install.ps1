<#
Installs the ohi CLI, or the linkml-scala CLI, from GitHub Releases.
Needs Windows PowerShell 5.1 or PowerShell 7.

Run it, so that the install directory lands on the PATH of the current session:

  irm https://ohi.dev/install.ps1 | iex

Or download it, read it, and run it:

  irm https://ohi.dev/install.ps1 -OutFile install.ps1
  .\install.ps1

Select linkml-scala through the environment:

  $env:LINKML_SCALA_TOOL = 'linkml-scala'; irm https://ohi.dev/install.ps1 | iex

Environment variables:
  LINKML_SCALA_TOOL              CLI to install: ohi (default) or linkml-scala.
  LINKML_SCALA_VERSION           Release tag to install, e.g. v0.17.2 (default: latest).
  LINKML_SCALA_INSTALL_DIR       Where to put the binary (default: $HOME\.local\bin).
  LINKML_SCALA_REQUIRE_CHECKSUM  Set to 1 to abort if the release publishes no SHA256SUMS.
  LINKML_SCALA_REQUIRE_ATTESTATION  Set to 1 to abort unless the build provenance verifies.
                                 Needs the GitHub CLI, recent enough to read the current
                                 Sigstore trust root.
  LINKML_SCALA_MODIFY_PROFILE    Preset the "add to PATH?" answer (1/yes to accept,
                                 anything else to decline). Skips the prompt.

The script never calls exit, so a failure does not close the session it was pasted into.

If the install directory is not on your PATH, the script asks before adding it to your
PowerShell profile. It never edits anything without an explicit yes, and never prompts when
stdin is redirected (CI), nor does it touch the persistent user PATH.

For bash on Windows - Git Bash, MSYS2, Cygwin, WSL - use https://ohi.dev/install.sh.
#>

function Install-LinkmlScalaCli {
    $ErrorActionPreference = 'Stop'

    $repo = 'NeverBlink-OSS/linkml-scala'
    $name = if ($env:LINKML_SCALA_TOOL) { $env:LINKML_SCALA_TOOL } else { 'ohi' }
    if ($name -notin @('ohi', 'linkml-scala')) {
        throw "LINKML_SCALA_TOOL must be ohi or linkml-scala, not '$name'."
    }

    $installDir = if ($env:LINKML_SCALA_INSTALL_DIR) {
        $env:LINKML_SCALA_INSTALL_DIR
    } else {
        Join-Path $HOME '.local\bin'
    }

    # Windows PowerShell 5.1 on older Windows does not enable TLS 1.2 by default.
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    # The tests point this at a local server. Not part of the documented interface.
    $base = if ($env:LINKML_SCALA_RELEASE_BASE) {
        $env:LINKML_SCALA_RELEASE_BASE
    } else {
        "https://github.com/$repo/releases"
    }

    # Resolve the release tag unless one was pinned. This follows the redirect that
    # /releases/latest issues, rather than asking api.github.com, which rate-limits
    # unauthenticated callers to 60 requests per hour per IP.
    $tag = $env:LINKML_SCALA_VERSION
    if (-not $tag) {
        try {
            $request = [Net.HttpWebRequest]::Create("$base/latest")
            $request.Method = 'HEAD'
            $request.AllowAutoRedirect = $false
            $response = $request.GetResponse()
            $location = $response.Headers['Location']
            $response.Close()
        } catch {
            throw ("Could not reach GitHub to determine the latest release. " +
                   "Set LINKML_SCALA_VERSION to install a specific release instead.")
        }
        if ($location -notmatch '/tag/(.+)$') {
            throw ("Could not determine the latest release tag of $repo. " +
                   "Set LINKML_SCALA_VERSION to install a specific release instead.")
        }
        $tag = $Matches[1]
    }

    # Only windows-x86_64 is published. Windows on arm64 runs it under emulation.
    $asset = "$name-windows-x86_64.exe"
    $downloadBase = "$base/download/$tag"

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Write-Host "Installing $name $tag ($asset)"
        $binary = Join-Path $tmp $asset
        try {
            Invoke-WebRequest -Uri "$downloadBase/$asset" -OutFile $binary -UseBasicParsing
        } catch {
            throw "Failed to download $downloadBase/$asset"
        }

        # Verify against the release's checksum manifest. A 404 here is an expected,
        # handled case for older releases.
        $sums = Join-Path $tmp 'SHA256SUMS'
        $haveSums = $true
        try {
            Invoke-WebRequest -Uri "$downloadBase/SHA256SUMS" -OutFile $sums -UseBasicParsing
        } catch {
            $haveSums = $false
        }

        if ($haveSums) {
            $expected = $null
            foreach ($line in Get-Content $sums) {
                $fields = $line -split '\s+', 2
                if ($fields.Count -lt 2) { continue }
                if ($fields[1].Trim().TrimStart('*') -eq $asset) {
                    $expected = $fields[0]
                    break
                }
            }
            if (-not $expected) {
                throw "SHA256SUMS for $tag does not list $asset."
            }
            # Get-FileHash returns uppercase hex, sha256sum lowercase. -ne ignores case.
            $actual = (Get-FileHash -Path $binary -Algorithm SHA256).Hash
            if ($expected -ne $actual) {
                throw ("Checksum mismatch for $asset - refusing to install.`n" +
                       "  expected: $expected`n  actual:   $actual")
            }
            Write-Host 'Checksum verified against SHA256SUMS.'
        } elseif ($env:LINKML_SCALA_REQUIRE_CHECKSUM -eq '1') {
            throw ("Release $tag publishes no SHA256SUMS and LINKML_SCALA_REQUIRE_CHECKSUM=1 " +
                   "was set. Refusing to install.")
        } else {
            # Releases up to and including v0.12.0 predate the SHA256SUMS asset.
            Write-Warning ("Release $tag publishes no SHA256SUMS, so the download could not be " +
                           "verified. Install a newer release to get a verifiable download.")
        }

        # Best effort: Sigstore-backed proof that these bytes came out of the release
        # workflow. gh exits 1 for every problem alike, so branch on what it actually said.
        if (Get-Command gh -ErrorAction SilentlyContinue) {
            $attestation = & {
                $ErrorActionPreference = 'Continue'
                & gh attestation verify $binary --repo $repo 2>&1 | Out-String
            }
            if ($LASTEXITCODE -eq 0) {
                Write-Host 'Build provenance verified with gh attestation.'
            } else {
                if ($attestation -match 'HTTP 404' -or $attestation -match 'no attestations found') {
                    # Releases up to and including v0.12.0 predate the attestation.
                    Write-Warning "Release $tag publishes no build provenance (older releases have none)."
                } else {
                    Write-Warning ("The build provenance of $tag could not be checked. gh reported:`n" +
                                   $attestation.Trim() + "`n" +
                                   "This is most often an out-of-date gh that cannot read the current " +
                                   "Sigstore trust root; upgrading gh usually fixes it. To check the " +
                                   "provenance itself, re-run:`n" +
                                   "  gh attestation verify <file> --repo $repo")
                }
                if ($env:LINKML_SCALA_REQUIRE_ATTESTATION -eq '1') {
                    throw ("LINKML_SCALA_REQUIRE_ATTESTATION=1 was set, so an unverified build " +
                           "provenance is fatal. Refusing to install.")
                }
            }
        } elseif ($env:LINKML_SCALA_REQUIRE_ATTESTATION -eq '1') {
            throw ("LINKML_SCALA_REQUIRE_ATTESTATION=1 was set but 'gh' was not found, so the " +
                   "build provenance cannot be checked. Refusing to install.")
        }

        if (-not (Test-Path -LiteralPath $installDir)) {
            New-Item -ItemType Directory -Path $installDir -Force | Out-Null
        }
        $target = Join-Path $installDir "$name.exe"
        Move-Item -LiteralPath $binary -Destination $target -Force
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host "Installed $name $tag to $target"

    $separator = [IO.Path]::PathSeparator
    if (($env:PATH -split $separator) -contains $installDir) {
        Write-Host "Run '$name --help' to get started."
        return
    }

    # Usable straight away in this session.
    $env:PATH = "$env:PATH$separator$installDir"

    $pathLine = "`$env:PATH += '$separator$installDir'"
    $answer = ''
    if ($env:LINKML_SCALA_MODIFY_PROFILE) {
        $answer = $env:LINKML_SCALA_MODIFY_PROFILE
    } elseif (-not [Console]::IsInputRedirected) {
        Write-Host ''
        $answer = Read-Host "$installDir is not on your PATH.`nAdd it to $PROFILE? [y/N]"
    }

    if ($answer -in @('1', 'y', 'yes')) {
        if ((Test-Path -LiteralPath $PROFILE) -and
            (Select-String -LiteralPath $PROFILE -Pattern $installDir -SimpleMatch -Quiet)) {
            Write-Host "$PROFILE already refers to $installDir - leaving it unchanged."
            return
        }
        $profileDir = Split-Path -Parent $PROFILE
        if (-not (Test-Path -LiteralPath $profileDir)) {
            New-Item -ItemType Directory -Path $profileDir -Force | Out-Null
        }
        # Add-Content creates the file if it is missing and never truncates one that is not.
        Add-Content -LiteralPath $PROFILE -Value "`n# Added by the linkml-scala installer`n$pathLine"
        Write-Host "Added $installDir to your PATH in $PROFILE."
        Write-Host "Open a new shell, or run '. `$PROFILE', to pick it up."
        return
    }

    Write-Host ''
    Write-Host "Leaving your PowerShell profile alone. To put $installDir on your PATH"
    Write-Host 'permanently, add this line to it:'
    Write-Host ''
    Write-Host "  $pathLine"
    Write-Host ''
    Write-Host 'Or call the binary by its full path:'
    Write-Host "  $target --help"
}

Install-LinkmlScalaCli
