param(
    [string]$Repository = 'Vengeful-Pancake/ShelfAndroid',
    [string]$GhPath,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository name' }
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
if (-not $GhPath) {
    $installed = Get-Command gh -ErrorAction SilentlyContinue
    if ($installed) { $GhPath = $installed.Source }
    else { $GhPath = (Get-ChildItem (Join-Path $root 'build/tools/gh') -Filter gh.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }
}
if (-not $GhPath -or -not (Test-Path -LiteralPath $GhPath)) { throw 'Run scripts/Install-Gh.ps1, then gh auth login first' }
& $GhPath auth status
if ($LASTEXITCODE -ne 0) { throw 'Sign into GitHub CLI yourself before publishing; never paste a token into chat' }
$login = & $GhPath api user --jq .login
if ($LASTEXITCODE -ne 0) { throw 'Could not verify GitHub account' }
if ($Repository.Split('/')[0] -ne $login.Trim()) { throw 'This publisher only creates repositories under the authenticated account' }
$accountId = & $GhPath api user --jq .id
if ($LASTEXITCODE -ne 0 -or $accountId -notmatch '^\d+$') { throw 'Could not verify GitHub account ID' }
if ($SkipBuild -and $Repository -ne 'Vengeful-Pancake/ShelfAndroid') { throw 'Changing update repository requires a fresh build' }
$dirty = git status --porcelain
if ($dirty) { throw 'Commit reviewed source changes before publishing' }
$tracked = git ls-files
foreach ($file in $tracked) {
    if ($file -match '(?i)(^|/)(keystore\.properties|local\.properties)$|\.(jks|keystore|p12|pfx)$|(^|/)(\.gradle|build)/') { throw "Refusing to publish sensitive/generated path: $file" }
}
$gradle = Get-Content 'app/build.gradle.kts' -Raw
$version = [regex]::Match($gradle, 'versionName\s*=\s*"([0-9.]+)"').Groups[1].Value
if ($version -notmatch '^\d+(\.\d+){1,3}$') { throw 'Set a stable versionName in app/build.gradle.kts' }
$tag = "v$version"
if (-not $SkipBuild) {
    & ./gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease "-PupdateRepository=$Repository" --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed' }
}
$sdkLine = (Get-Content 'local.properties' | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', ''
$sdk = $sdkLine.Replace('\:', ':').Replace('\\', '\')
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
$apk = Join-Path $root "app/build/outputs/apk/release/Shelf-$version.apk"
Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect APK metadata' }
$expectedCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$packageLine = $badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
if (-not $packageLine.Contains("name='com.shelf.reader'") -or
    -not $packageLine.Contains("versionName='$version'") -or
    -not $packageLine.Contains("versionCode='$expectedCode'") -or
    ($badging | Where-Object { $_ -eq 'application-debuggable' })) { throw 'APK identity/version does not match reviewed source' }
$certificateOutput = & $signer verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed' }
$expectedCertificate = (Get-Content 'config/release-certificate.sha256' -Raw).Trim()
if (-not ($certificateOutput | Select-String -SimpleMatch $expectedCertificate)) { throw 'Wrong signing key; would break installed-user updates' }
$sha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumFile = "$apk.sha256"
[IO.File]::WriteAllText($checksumFile, "$sha  Shelf-$version.apk`n", [Text.UTF8Encoding]::new($false))
$sourceZip = Join-Path $root "build/Shelf-source-$version.zip"
git archive --format=zip "--output=$sourceZip" HEAD
if ($LASTEXITCODE -ne 0) { throw 'Source archive failed' }
$head = (git rev-parse HEAD).Trim()
$remote = "https://github.com/$Repository.git"
$existing = git remote get-url publish 2>$null
if ($LASTEXITCODE -ne 0) { git remote add publish $remote }
elseif ($existing.Trim() -ne $remote) { throw 'The publish remote points somewhere else; review it first' }
& $GhPath repo view $Repository --json nameWithOwner 2>$null
if ($LASTEXITCODE -ne 0) {
    & $GhPath repo create $Repository --public --description 'Android reader with offline Smooth Xianxia, local story/global names and verified release updates'
    if ($LASTEXITCODE -ne 0) { throw 'Repository creation failed' }
}
$helper = '!"' + $GhPath.Replace('\','/') + '" auth git-credential'
git -c credential.helper= -c "credential.helper=$helper" push publish HEAD:main
if ($LASTEXITCODE -ne 0) { throw 'Source push failed; never force push over remote changes' }
$existingTag = git rev-list -n 1 $tag 2>$null
if ($LASTEXITCODE -eq 0 -and $existingTag -and $existingTag.Trim() -ne $head) { throw 'Tag already points to another commit' }
if (-not $existingTag) { git -c "user.name=$login" -c "user.email=$accountId+$login@users.noreply.github.com" tag -a $tag -m "Shelf $version" }
git -c credential.helper= -c "credential.helper=$helper" push publish "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) { throw 'Tag push failed' }
& $GhPath release view $tag --repo $Repository --json tagName 2>$null
if ($LASTEXITCODE -eq 0) { throw 'Release already exists; inspect it rather than replacing public assets' }
$notes = Join-Path $root "docs/releases/$tag.md"
if (-not (Test-Path -LiteralPath $notes)) { throw 'Write release notes in docs/releases before publishing' }
& $GhPath release create $tag $apk $checksumFile $sourceZip --repo $Repository --title "Shelf $version" --notes-file $notes --target $head --latest
if ($LASTEXITCODE -ne 0) { throw 'Release creation failed' }
Write-Host "Published source: https://github.com/$Repository"
Write-Host "Published release: https://github.com/$Repository/releases/tag/$tag"
Write-Host "APK SHA-256: $sha"
