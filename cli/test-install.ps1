<#
Exercises install.ps1 against a synthetic release served from localhost, without touching
GitHub or any profile. Windows only - install.ps1 installs a Windows executable.

  pwsh cli/test-install.ps1

Needs python on the PATH to serve the fixtures. LINKML_SCALA_VERSION is pinned throughout,
because python -m http.server cannot issue the /releases/latest redirect; the install
workflow covers that against the real release.
#>

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) {
    Write-Host 'SKIP: install.ps1 installs a Windows executable'
    exit 0
}

$installer = Join-Path $PSScriptRoot 'install.ps1'
$shell = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh' } else { 'powershell' }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('linkml-install-test.' + [IO.Path]::GetRandomFileName())
$release = Join-Path $testRoot 'release'
$download = Join-Path $release 'releases\download\v0.0.1'
New-Item -ItemType Directory -Path $download -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $testRoot 'bin') -Force | Out-Null

foreach ($name in @('ohi', 'linkml-scala')) {
    Set-Content -LiteralPath (Join-Path $download "$name-windows-x86_64.exe") `
        -Value "fixture $name" -NoNewline
}
$sums = Get-ChildItem -LiteralPath $download -Filter '*.exe' | ForEach-Object {
    "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)"
}
Set-Content -LiteralPath (Join-Path $download 'SHA256SUMS') -Value $sums

# gh is installed on the runners. Stub it, so no case reaches the attestation API.
Set-Content -LiteralPath (Join-Path $testRoot 'bin\gh.cmd') -Value @(
    '@echo off'
    'echo no attestations found 1>&2'
    'exit /b 1'
)

$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = $probe.LocalEndpoint.Port
$probe.Stop()

$server = Start-Process -FilePath 'python' -PassThru -WindowStyle Hidden `
    -ArgumentList @('-m', 'http.server', "$port", '--bind', '127.0.0.1', '--directory', $testRoot)

$originalPath = $env:PATH
$failures = 0
$caseName = ''
$caseDir = ''

function Start-Case {
    param([string]$Name)
    $script:caseName = $Name
    $script:caseDir = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Path $script:caseDir -Force | Out-Null
    Copy-Item -LiteralPath $release -Destination (Join-Path $script:caseDir 'release') -Recurse
}

function Set-CaseEnvironment {
    param([hashtable]$Variables = @{})
    $env:PATH = (Join-Path $testRoot 'bin') + [IO.Path]::PathSeparator + $originalPath
    $env:LINKML_SCALA_TOOL = $null
    $env:LINKML_SCALA_VERSION = 'v0.0.1'
    $env:LINKML_SCALA_RELEASE_BASE = "http://127.0.0.1:$port/$caseName/release/releases"
    $env:LINKML_SCALA_INSTALL_DIR = Join-Path $caseDir 'install'
    $env:LINKML_SCALA_MODIFY_PROFILE = '0'
    $env:LINKML_SCALA_REQUIRE_CHECKSUM = '1'
    $env:LINKML_SCALA_REQUIRE_ATTESTATION = '0'
    foreach ($key in $Variables.Keys) { Set-Item "env:$key" $Variables[$key] }
}

function Invoke-Case {
    param([int]$ExpectedStatus, [hashtable]$Variables = @{})
    Set-CaseEnvironment $Variables
    $output = Join-Path $caseDir 'output'
    $errors = Join-Path $caseDir 'output.err'
    $process = Start-Process -FilePath $shell -PassThru -Wait -NoNewWindow `
        -ArgumentList @('-NoProfile', '-File', $installer) `
        -RedirectStandardOutput $output -RedirectStandardError $errors
    Get-Content -LiteralPath $errors | Add-Content -LiteralPath $output
    if ($process.ExitCode -ne $ExpectedStatus) {
        Assert-Failed "expected status $ExpectedStatus, got $($process.ExitCode)"
    }
}

function Assert-Failed {
    param([string]$Message)
    Write-Host "FAIL: ${caseName}: $Message"
    Get-Content -LiteralPath (Join-Path $caseDir 'output') -ErrorAction SilentlyContinue | Write-Host
    $script:failures++
    throw [OperationCanceledException]::new()
}

function Assert-Installed {
    param([string]$Name)
    $installed = @(Get-ChildItem -LiteralPath (Join-Path $caseDir 'install'))
    if ($installed.Count -ne 1 -or $installed[0].Name -ne "$Name.exe") {
        Assert-Failed "unexpected installed file: $($installed.Name -join ', ')"
    }
    if ((Get-Content -LiteralPath $installed[0].FullName -Raw) -ne "fixture $Name") {
        Assert-Failed 'payload does not match'
    }
}

function Assert-OutputContains {
    param([string]$Message)
    $output = Get-Content -LiteralPath (Join-Path $caseDir 'output') -Raw
    if ($output -notlike "*$Message*") { Assert-Failed "missing output: $Message" }
}

function Assert-Refused {
    param([string]$Message)
    Assert-OutputContains $Message
    if (Test-Path -LiteralPath (Join-Path $caseDir 'install')) {
        Assert-Failed 'failed install wrote its destination'
    }
}

function Invoke-TestCase {
    param([string]$Name, [scriptblock]$Body)
    try {
        Start-Case $Name
        & $Body
        Write-Host "PASS: $Name"
    } catch [OperationCanceledException] {
    }
}

try {
    for ($i = 0; $i -lt 50; $i++) {
        try {
            [Net.Sockets.TcpClient]::new('127.0.0.1', $port).Close()
            break
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }

    Invoke-TestCase 'default_install' {
        Invoke-Case 0
        Assert-Installed 'ohi'
        Assert-OutputContains 'Checksum verified against SHA256SUMS.'
    }

    Invoke-TestCase 'linkml_scala_install' {
        Invoke-Case 0 @{ LINKML_SCALA_TOOL = 'linkml-scala' }
        Assert-Installed 'linkml-scala'
    }

    Invoke-TestCase 'invalid_selector' {
        Invoke-Case 1 @{ LINKML_SCALA_TOOL = 'ohi-cli' }
        Assert-Refused "LINKML_SCALA_TOOL must be ohi or linkml-scala, not 'ohi-cli'."
    }

    Invoke-TestCase 'checksum_missing_selected_asset' {
        $file = Join-Path $caseDir 'release\releases\download\v0.0.1\SHA256SUMS'
        Set-Content -LiteralPath $file -Value (Get-Content -LiteralPath $file | Where-Object { $_ -notmatch 'ohi-' })
        Invoke-Case 1
        Assert-Refused 'does not list ohi-windows-x86_64.exe.'
    }

    Invoke-TestCase 'checksum_mismatch' {
        Set-Content -NoNewline -Value 'tampered' `
            -LiteralPath (Join-Path $caseDir 'release\releases\download\v0.0.1\ohi-windows-x86_64.exe')
        Invoke-Case 1
        Assert-Refused 'Checksum mismatch'
    }

    # iex runs in the caller's session, so the install directory must be usable at once.
    Invoke-TestCase 'install_updates_session_path' {
        Set-CaseEnvironment
        $output = Join-Path $caseDir 'output'
        & $installer *>&1 | Set-Content -LiteralPath $output
        if (($env:PATH -split [IO.Path]::PathSeparator) -notcontains $env:LINKML_SCALA_INSTALL_DIR) {
            Assert-Failed 'install did not update PATH'
        }
    }
} finally {
    $env:PATH = $originalPath
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures -gt 0) { exit 1 }
