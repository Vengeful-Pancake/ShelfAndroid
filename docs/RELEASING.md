# Publishing source and signed updates

Confirmed publishing account/repository: `Vengeful-Pancake/ShelfAndroid`
(public). The CLI authenticated as the repository owner. Both `origin` and the
separate `publish` remote refer to this repository. Publishing never force-pushes
over remote changes. The earlier thinh2000tiger-lang target was an unpublished
local plan based on the connector account, not the authenticated CLI account.

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
   if needed, pushes main and the tag, uploads APK + SHA-256 + source ZIP +
   `update.json` to a draft, then publishes it as latest after uploads finish.
5. Verify `https://github.com/OWNER/REPO/releases/latest/download/update.json`
   returns the versionName, increasing versionCode, exact versioned APK URL,
   APK size and matching SHA-256. Keep schemaVersion 1 compatible with existing
   clients. API fallback still requires GitHub's asset `digest` for older releases.

GitHub Actions runs tests and creates a **debug** build on pushes and PRs. It
does not receive signing keys and does not publish unsigned production updates.
Releases are produced locally by the signed publishing script. Re-running the
script never replaces an existing tag/release with different content.

Changing the repository requires building with
`-PupdateRepository=OWNER/REPOSITORY` and publishing to that same public repository.
The app never embeds a GitHub access token. A private repository is not an
anonymous updater endpoint.

## Update behavior

Checks run at cold startup and on returning to the foreground, then every 15
minutes while active. Failed checks retry after one minute or server backoff.
Each new process checks immediately instead of inheriting the previous build's
six-hour timestamp. Manual requests are queued until the root coordinator can
handle them and show progress/results. Since 0.3.6 the user chooses to open the
exact version's GitHub release page in the system browser, download the APK and
confirm installation in Android. Shelf does not download APKs or request install
privileges. The publisher verifies package identity, versionName, versionCode,
non-debuggable flag and the pinned release certificate. Android enforces installed
package/signing compatibility. Keep the same signing key for in-place upgrades.

The primary manifest uses the documented GitHub latest-release asset URL, so
normal checks do not consume the REST API's anonymous per-IP quota. Only a 404
manifest falls back to the API. Rate-limit headers are honored for API retries;
no account token is placed in the APK. All metadata redirects must stay HTTPS
on GitHub/CDN hosts. Malformed metadata fails closed. The browser handoff constructs
the release-page URL from the configured repository and validated stable version;
it never launches an arbitrary URL provided by remote metadata. APK SHA-256 and
source ZIP remain public release assets, including update.json for older clients.

Version/update details show the installed channel, last attempt/success, latest
known version, source and error. Debug builds explicitly identify themselves and
do not self-install a production package. Updater timestamps and API cooldowns
are excluded from Android backup/transfer; reading preferences remain separate.

0.3.6 removes REQUEST_INSTALL_PACKAGES, the cached-APK download/installation path
and the update FileProvider. Automatic update discovery and manual checks remain.
No accessibility, device-admin, SMS, contacts or broad file permissions are involved.
Play Protect classification cannot be guaranteed by a successful build, signature
check or emulator install. Do not change package/key or disable device protection
to work around a classification; continue the official review when necessary.
