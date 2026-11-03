$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root 'build/tools/gh'
$headers = @{ 'User-Agent' = 'Shelf-release-tools'; Accept = 'application/vnd.github+json' }
$release = Invoke-RestMethod 'https://api.github.com/repos/cli/cli/releases/latest' -Headers $headers
$asset = $release.assets | Where-Object name -Match '^gh_[0-9.]+_windows_amd64\.zip$' | Select-Object -First 1
$checksums = $release.assets | Where-Object name -Match '^gh_[0-9.]+_checksums\.txt$' | Select-Object -First 1
if (-not $asset -or -not $checksums) { throw 'GitHub CLI release is missing its Windows binary or checksums' }
foreach ($item in @($asset, $checksums)) {
    if (-not $item.browser_download_url.StartsWith('https://github.com/cli/cli/releases/download/')) { throw 'Unexpected CLI download URL' }
}
New-Item -ItemType Directory -Force $destination | Out-Null
$zip = Join-Path $destination $asset.name
Invoke-WebRequest $asset.browser_download_url -OutFile $zip
$checksumPath = Join-Path $destination $checksums.name
Invoke-WebRequest $checksums.browser_download_url -OutFile $checksumPath
$checksumText = Get-Content -LiteralPath $checksumPath -Raw
$expectedLine = ($checksumText -split "`n") | Where-Object { $_.TrimEnd().EndsWith($asset.name) } | Select-Object -First 1
if (-not $expectedLine) { throw 'GitHub CLI checksum is missing' }
$expected = ($expectedLine.Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()) { throw 'GitHub CLI SHA-256 mismatch' }
Expand-Archive -LiteralPath $zip -DestinationPath $destination -Force
$gh = Get-ChildItem -LiteralPath $destination -Filter gh.exe -Recurse | Select-Object -First 1
if (-not $gh) { throw 'GitHub CLI executable was not found' }
Write-Host ('Verified GitHub CLI: ' + $gh.FullName)
Write-Host ('Sign in yourself with: & "' + $gh.FullName + '" auth login --web --git-protocol https --scopes repo,workflow')
