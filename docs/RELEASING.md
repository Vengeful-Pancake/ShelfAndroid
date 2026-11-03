# Publishing source and signed updates

Target repository: `thinh2000tiger-lang/ShelfAndroid` (public). The original
`origin` is the upstream `Vengeful-Pancake/ShelfAndroid`; publishing uses a
separate `publish` remote and never force-pushes over remote changes.

## One-time sign-in

Run `scripts/Install-Gh.ps1` to download the official portable GitHub CLI into
the ignored build/tools directory and verify its published SHA-256. Run the
printed `gh auth login --web --git-protocol https --scopes repo,workflow`
command yourself and complete GitHub's login/authorization. Never paste a
token or signing password into chat, source files or an issue.

## Publish a release

1. Keep `release-key.jks` and `keystore.properties` only on the local signing
   machine. Their paths are ignored. `config/release-certificate.sha256` is a
   public fingerprint used to detect accidental key changes.
2. Increase both versionCode and versionName in app/build.gradle.kts. Existing
   installs only accept a higher versionCode signed with the same certificate.
3. Write release notes in `docs/releases/vVERSION.md`, review changes, and commit
   all intended source. Git author identity should identify the actual publisher.
4. Run `./scripts/Publish-Release.ps1`. It verifies GitHub authentication,
   requires clean committed source, refuses tracked signing/config/build files,
   builds and tests, verifies the release certificate, creates the public repo
   if needed, pushes main and the tag, and publishes APK + SHA-256 + source ZIP.
5. Verify the public release API returns a SHA-256 `digest` for the exact asset
   `Shelf-VERSION.apk`. The app refuses release assets missing that digest.

GitHub Actions runs tests and creates a **debug** build on pushes and PRs. It
does not receive signing keys and does not publish unsigned production updates.
Releases are produced locally by the signed publishing script. Re-running the
script never replaces an existing tag/release with different content.

Changing the repository requires building with
`-PupdateRepository=OWNER/REPOSITORY` and publishing to that same public repository.
The app never embeds a GitHub access token. A private repository is not an
anonymous updater endpoint.

## Update behavior

Checks run at startup and on resume (six-hour throttle), then every six hours
while the app is active. The menu check bypasses the throttle. The user chooses
whether to download; only the verified cached APK can be offered to Android's
installer. Package identity, versionName, increasing versionCode and the exact
current signing certificate are checked. Certificate rotation requires a
deliberate future migration; it is not accepted implicitly.

REQUEST_INSTALL_PACKAGES is now intentionally declared for this user-requested
feature. Permission is requested only at the install step. No accessibility,
device-admin, SMS, contacts or broad file permissions are involved. Play Protect
classification cannot be guaranteed by a successful build or signature check.
