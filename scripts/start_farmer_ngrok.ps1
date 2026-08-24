[CmdletBinding()]
param(
    [ValidateSet("apple", "banana", "mango", "orange", "papaya", "pineapple", "tomato", "avocado", "durian")]
    [string]$Fruit = "banana",

    [ValidateSet("normal", "warm", "humid", "dry", "ventilation", "sensor_drift", "door_opening", "gas_burst", "mixed_stress")]
    [string]$Scenario = "mixed_stress",

    [ValidateRange(1, 65535)]
    [int]$Port = 8080,

    [string]$NgrokDomain,

    [switch]$UseClipboardToken
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = Get-Command python -ErrorAction Stop
$tokenWasAddedByScript = $false

Set-Location $repoRoot

try {
    & $python.Source -c "import ngrok" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Memasang SDK Ngrok Python..." -ForegroundColor Yellow
        & $python.Source -m pip install ngrok
        if ($LASTEXITCODE -ne 0) {
            throw "SDK Ngrok gagal dipasang. Jalankan '$($python.Source) -m pip install ngrok' lalu ulangi."
        }
    }

    if ([string]::IsNullOrWhiteSpace($env:NGROK_AUTHTOKEN)) {
        if ($UseClipboardToken) {
            $clipboardToken = Get-Clipboard -Raw -ErrorAction Stop
            if ([string]::IsNullOrWhiteSpace($clipboardToken)) {
                throw "Clipboard kosong. Salin authtoken dari dashboard Ngrok, lalu jalankan ulang dengan -UseClipboardToken."
            }
            $env:NGROK_AUTHTOKEN = $clipboardToken.Trim()
        }
        else {
            $secureToken = Read-Host "Tempel Ngrok authtoken dengan klik kanan atau Shift+Insert (input disembunyikan)" -AsSecureString
            $env:NGROK_AUTHTOKEN = [System.Net.NetworkCredential]::new("", $secureToken).Password
        }
        $tokenWasAddedByScript = $true
    }

    if ($env:NGROK_AUTHTOKEN.Length -lt 20 -or $env:NGROK_AUTHTOKEN -match "[\p{C}]") {
        throw "Authtoken tidak valid atau tidak terpasta dengan benar. Di VS Code gunakan klik kanan/Shift+Insert, atau salin token lalu jalankan dengan -UseClipboardToken."
    }

    $demoArguments = @(
        "scripts/farmer_demo.py",
        "--ngrok",
        "--host", "127.0.0.1",
        "--port", $Port,
        "--fruit", $Fruit,
        "--scenario", $Scenario
    )
    if (-not [string]::IsNullOrWhiteSpace($NgrokDomain)) {
        $demoArguments += @("--ngrok-domain", $NgrokDomain)
    }

    Write-Host "Memulai demo dan tunnel Ngrok. Biarkan terminal ini tetap terbuka." -ForegroundColor Cyan
    Write-Host "Salin URL HTTPS yang tercetak ke RipenAI, lalu kosongkan SSID WiFi unit." -ForegroundColor Cyan
    & $python.Source @demoArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Demo Ngrok berhenti dengan kode $LASTEXITCODE."
    }
}
finally {
    if ($tokenWasAddedByScript) {
        Remove-Item Env:NGROK_AUTHTOKEN -ErrorAction SilentlyContinue
    }
}
