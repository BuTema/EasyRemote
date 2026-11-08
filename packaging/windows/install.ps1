# Installs EasyRemote for the current user (no administrator rights needed).
$ErrorActionPreference = "Stop"
$dest = Join-Path $env:LOCALAPPDATA "EasyRemote\bin"
New-Item -ItemType Directory -Force $dest | Out-Null
Get-Process easyremoted -ErrorAction SilentlyContinue | Stop-Process -Force
Copy-Item (Join-Path $PSScriptRoot "easyremote.exe"), (Join-Path $PSScriptRoot "easyremoted.exe") $dest -Force
Write-Host "Installed to $dest"

& (Join-Path $dest "easyremote.exe") autostart on
Start-Process (Join-Path $dest "easyremoted.exe")

# Let the phone reach the agent on private (home/office) networks. Needs admin, so it is optional:
# if this fails, Windows shows its normal "allow access" prompt the first time a phone connects.
try {
    New-NetFirewallRule -DisplayName "EasyRemote" -Direction Inbound -Program (Join-Path $dest "easyremoted.exe") `
        -Action Allow -Profile Private | Out-Null
    Write-Host "Firewall rule added for private networks."
} catch {
    Write-Host "Skipped firewall rule (run this script as administrator to add it)."
}

Write-Host ""
Write-Host "Now pair your phone:"
& (Join-Path $dest "easyremote.exe") pair
