# Local security review — 29 October 2026

## Confirmed publication target 0.3.2 — 3 November 2026

The user completed GitHub CLI authentication as Vengeful-Pancake. The CLI
confirmed ADMIN permission on the existing public Vengeful-Pancake/ShelfAndroid
repository. The updater and publishing defaults now use this confirmed original
repository instead of the provisional connector-account target mentioned in the
unpublished 0.3.1 notes below. No signing-key change is involved.

VersionCode 7 / versionName 0.3.2 passed release assembly, JVM checks and release
lint after the endpoint change. The earlier five emulator tests exercised the
same updater/package-validation implementation. The publisher additionally
checks APK package/version metadata against the committed Gradle configuration
and the release certificate against the public pinned fingerprint.

APK SHA-256: `59e18bf6237171053c83d7917ce79871736b32f029093635f809ef2f8aa56690`.
Install 0.3.2 once over old/local builds to bootstrap the correct public updater.

## User-requested GitHub updater 0.3.1 — 3 November 2026

The project user requested publication of source + bundled dictionaries + APK
and self-updates from future GitHub releases. VersionCode 6 / versionName 0.3.1
adds a new updater pinned to `thinh2000tiger-lang/ShelfAndroid`, rather than
restoring the upstream 0.1 updater unchanged. That public repository is the
intended publishing destination; availability depends on successful publication.

Checks run on startup/resume with a six-hour throttle and periodically while
foregrounded, plus the explicit library-menu action. Downloads require user
confirmation. Draft/prerelease releases are ignored. The exact versioned APK
asset must belong to the configured repository, have GitHub's SHA-256 digest,
stay below 100 MiB and use HTTPS GitHub/CDN redirects. File size and digest are
checked before parsing package metadata. The package ID and advertised version
must match, versionCode must increase, and the signing certificates must equal
those of the installed app. No app GitHub token is embedded. Debug installations
do not install production releases.

REQUEST_INSTALL_PACKAGES is intentionally reintroduced for this explicit user
request. It is used only after verified download and a further install action.
Android's unknown-source permission and installer confirmation remain mandatory.
The FileProvider exposes only cache/updates, not private reading data. This does
not bypass or guarantee clearance by Play Protect. Historical 0.2/0.3.0 notes
below describe builds without this feature.

22 JVM tests, 5 emulator tests and 4 browser-runtime tests pass; release lint
reports 0 errors / 41 warnings. Emulator package tests reject a fake APK, another
package, and the currently installed non-increasing version. The release APK's
certificate matches the public pinned fingerprint. Signing keys, passwords,
machine configuration, build caches and generated APKs are excluded from Git.
The publishing script also refuses those tracked paths before pushing.

Release APK SHA-256:
`7d328b63c3353a12e5c648b5023b9aca5664f5743542e4066e14830610ca43ed`.

## Interactive reader 0.3.0 — 3 November 2026

Chinese text is preserved in escaped local reader annotations, with separate
original, Hán Việt and Smooth dictionary values. Literal name rules are written
through AtomicFile to private `translation-names.json`. Story rules override
global rules, and manual backups include this JSON. STV chapter IDs and mirrors
are normalized so chapter changes do not change story scope.

The new LocalReader JavaScript interface is attached only to generated local
reader documents, never the Internet WebView. Network requests/navigation in
the generated web-article reader are blocked. Its methods expose bounded text
translation, fixed-location name/appearance storage and Android speech; they
do not accept filesystem paths or install packages. The EPUB reader continues
to strip publisher scripts and intercept external resource loads. Taps and
drags on reader tools no longer trigger EPUB page-turn gestures.

Appearance values are validated before storage. HTML text and source attributes
are escaped. The Chinese mode leaves Latin text intact even when an upstream
dictionary includes Latin keys. Speech uses installed Android TTS voices; the
highlight range follows the correct sentence occurrence, including repeats.

Verification: 19 JVM tests, 2 Android emulator tests and 4 browser-runtime JS
tests pass; release lint has 0 errors (39 warnings). Device tests cover original
text recovery, word inspection, one-tick story-name saving, global/story
precedence, JSON on disk, colour persistence, sentence navigation/highlighting
and scrolling, plus reader-mode/tab transitions. Popup and speech panel images
were inspected after capture. Actual speech quality and voice availability are
device/engine dependent. VersionCode 5 / versionName 0.3.0 retains the existing
release signing key and only INTERNET plus the AndroidX private receiver
permission. APK SHA-256:
`7acc3a88b8a163cb261a8507a0d00d9d0628468b69f7754776464808c6a40614`.

## Reader-mode correction in 0.2.2

The user's recording showed the article reader flashing briefly and returning
to the webpage repeatedly. Reader mode shared the browser's transient article
extraction state. Disposing the WebView changed its reference to null, which
triggered the extraction effect to clear the article and made the UI leave
Reader mode. Recreating the browser then extracted the article again and
repeated the transition.

Reader mode now owns a separate article snapshot. Browser disposal and late
page callbacks cannot clear or replace the displayed article. Entry runs on
the main dispatcher, with translation work on IO. The emulator regression test
now enters Reader mode, checks it remains visible after disposal effects and
pending callbacks, explicitly returns to the browser, and switches tabs.

The signed release uses versionCode 4 / versionName 0.2.2 and the same release
key. Device tests, JVM tests and release lint pass (0 lint errors). HTTPS,
message validation and permission restrictions are unchanged.
APK SHA-256: `f3cefbda3fbcbab39afee1f93b3da7ae3e78508fbea243dbac819bdfe613a3cc`.

## Internet-tab correction in 0.2.1

The WebView message listener's `https://*` origin rule was invalid and caused
`IllegalArgumentException` during Internet-tab creation. A device test on the
Android 17 emulator confirms the old rule throws. The browser now uses WebView's
documented wildcard syntax and explicitly rejects messages unless they come
from the main HTTPS frame of the current page, with the same host and port.
Existing text size/concurrency limits and HTTPS-only navigation remain in force.
Binary messages are rejected before accessing string payloads. An unsupported
bridge setup reports translation unavailability instead of crashing the tab.
Translation replies explicitly return to the main dispatcher before accessing
WebView. The emulator regression test covers opening Internet, translating a
controlled Chinese page through the actual browser bridge and offline QT
dictionary, switching to Dictionary, and returning to Internet and Books.
Both device tests and all 16 JVM tests pass; release lint has 0 errors.

Version 0.2.1 uses versionCode 3 and the existing release signing key. Its APK
SHA-256 is `0609017c74e18e4bcc16b90fd92a713ad7d7c9de5dc5f26616a83223fa898447`.
The historical 0.2 review below records the previous artifact and checks.

## Finding and limits

The supplied screenshot is Google's **Harmful app blocked** warning. Google's
guidance distinguishes this from an unknown-app scan recommendation. The
screenshot and the subsequent generic **App didn't install** message do not
identify a malware category or Android package-installer error code.

Official guidance:
https://developers.google.com/android/play-protect/warning-dev-guidance

The previously supplied APK was correctly signed with an Android debug
certificate and used package `com.shelf.reader.debug`. Its manifest requested
INTERNET and REQUEST_INSTALL_PACKAGES. No SMS, contacts, microphone, camera,
accessibility, notification-listener or broad-storage permission was declared.
A valid signature is an integrity check, not a malware or reputation verdict.

The inherited updater could fetch APKs from the original upstream repository
and invoke the package installer. A reader does not need this capability. The
manifest also permitted cleartext traffic. These are confirmed risks addressed
below, **not confirmed causes of Google's classification**.

## Changes in 0.2

- Removed the updater implementation, its startup hook, installation permission
  and installer FileProvider/path configuration. Version comparison remains as
  an inert utility. This build does not fetch executable updates.
- Disabled cleartext traffic at the Android application level. The browser
  upgrades HTTP navigation to HTTPS and OPDS requires HTTPS.
- Browser navigation rejects arbitrary intent, file, content, data and script
  destinations. Only main-frame, user-tapped mailto/tel links may launch an app.
  External links in EPUBs also require a main-frame user gesture.
- The browser translation bridge accepts HTTPS main-frame messages only, with
  one translation operation in flight, at most 32 nodes / 32,000 total input
  characters and bounded message and request-ID lengths. It exposes text
  translation, not file, settings, installation or command APIs.
- Added ZIP directory/range checks, a 100,000-entry limit, 32 MiB directory and
  per-entry limits, and rejection of inflation beyond declared size and
  truncated deflate streams. This limits malicious EPUB memory consumption.
- Built a non-debuggable release with the existing configured release key and
  the existing release package `com.shelf.reader`; did not generate a replacement
  identity to bypass the warning. The hardened debug APK is also rebuilt.

## Verification

- Debug and release builds, Android release lint, and JVM tests pass.
- 15 JVM tests cover normal books, version parsing, dictionaries, URL policy,
  and malicious ZIP sizes. Four browser-runtime JavaScript tests pass.
- Release lint: 0 errors, 34 warnings.
- Android apksigner verifies the release APK's v2 signature (RSA 4096).
- Packaged release manifest: INTERNET plus AndroidX's app-private signature
  receiver permission; no package-install permission. Cleartext is disabled;
  the release is not debuggable.
- Release certificate SHA-256:
  `c6ef066920201b7294bcba7b87f6e1c6a6b8d40e7055388a0577601d101e0227`
- Delivered release APK SHA-256:
  `3081f219da70e5e9fcf837ef03c4d82ac8b18f333d1844de61f1c6970f032445`
  Rebuilding can change the APK hash; use Get-FileHash on the delivered file.

No Android device was connected, so installation, device behavior and Play
Protect's assessment of this build have not been verified. No APK or private
dictionary data was uploaded to an external scanning service. This is a
targeted local review and hardening pass, not a complete independent malware
audit or a guarantee that every dependency is vulnerability-free.

## If installation still fails

Keep Play Protect enabled. Do not uninstall an existing reader or erase reading
data as a first troubleshooting step. Connect the phone for package-manager
diagnostics to distinguish policy blocking, a conflicting signing certificate,
a damaged transfer, storage constraints and other installer failures. Android
will not update an installed package with a different signing certificate.
If Google still classifies the reviewed release as harmful, follow Google's
Play Protect appeal process with the exact APK hash and certificate fingerprint.
