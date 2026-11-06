# Shelf 0.3.5: Play Protect report and review draft

Date: 6 November 2026. Status: **reported block remains unresolved**.

The user supplied a screenshot of Google Play Protect's "Harmful app blocked"
dialog for Shelf and confirmed version 0.3.5. Expanded text reported by the user:
"installing this app may put your device at risk". This does not identify a
specific malware family or the behavior that triggered Google's classification.
It is not sufficient evidence to call the classification a false positive.

The user subsequently authorized uploading this public APK to VirusTotal and
submitting the Google appeal, and accepted the VirusTotal terms/sample sharing.
VirusTotal upload and analysis completed for the exact SHA-256 below. The report
displayed "No security vendors flagged this file as malicious", with Google
listed as "Undetected". Some other engines timed out or could not process the
file type. This is not clearance by Google Play Protect. The Google appeal was
submitted on 6 November 2026 after the user provided the contact email. The
visible confirmation was "Your email has been sent". Google also requested
verification through the confirmation email; that verification remains for
the user because the connected mailbox is not the supplied contact mailbox.
The private contact address is intentionally omitted from this repository file.

## Exact artifact

- Package: `com.shelf.reader`
- Version: `0.3.5`, versionCode `10`
- APK size: `6081456` bytes
- APK SHA-256: `5f96b883a40792ae2b9f64c1b637012c5087c37cd3b109c9d2c395ee1ae13e67`
- Signing-certificate SHA-256: `c6ef066920201b7294bcba7b87f6e1c6a6b8d40e7055388a0577601d101e0227`
- Download: https://github.com/Vengeful-Pancake/ShelfAndroid/releases/download/v0.3.5/Shelf-0.3.5.apk
- Source tag: https://github.com/Vengeful-Pancake/ShelfAndroid/tree/v0.3.5
- VirusTotal report: https://www.virustotal.com/gui/file/5f96b883a40792ae2b9f64c1b637012c5087c37cd3b109c9d2c395ee1ae13e67

## Checks actually performed

- Local APK hash matches the published 0.3.5 artifact hash recorded at release.
- Android apksigner verifies the APK using v2 signing, RSA-4096. The certificate
  is unchanged from the reviewed 0.3.2, 0.3.3 and 0.3.4 releases.
- ZIP header is standard `PK\x03\x04`; archive integrity check passed, with no
  duplicate member paths and no embedded APK files.
- Effective APK permissions: INTERNET, REQUEST_INSTALL_PACKAGES and AndroidX's
  app-private DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. No SMS, Accessibility,
  Notification Listener, contacts, overlay or device-admin permission declared.
- Native libraries in the APK are AndroidX graphics-path and DataStore shared
  counter libraries for the supported CPU architectures.
- Dependency repositories in source are Google Maven and Maven Central. The
  declared runtime libraries are AndroidX/Compose/Room/DataStore/WebKit, Coil
  and Jsoup. This is a dependency inventory, not a full supply-chain attestation.
- Application-owned source searches did not find shell execution, dynamic DEX
  loading, SMS sending, AccessibilityService or NotificationListenerService.
- Reviewed updater flow requires user agreement to download and install, checks
  size/SHA-256/package/version/increasing build/signing certificate, and uses
  Android's installer. Its FileProvider exposes only `cache/updates`.
- Microsoft Defender custom scan of an isolated APK copy returned successfully;
  no threat-detection records matching that copy were observed. This is a local
  Windows scan and does **not** establish an Android/Play Protect verdict.

These are scoped checks, not a certification that all code and SDKs are safe.
Build success, functional tests, signature validity and an emulator install do
not prove that Play Protect will accept the APK on the user's device. Google's
classification reason has not been obtained. VirusTotal upload and the Google
appeal were authorized and submitted. No clearance decision has been obtained.

## Official route and required information

1. Use the exact APK/hash above when creating the VirusTotal report requested by
   Google's form. VirusTotal submissions can be shared with security vendors;
   this APK is already public, but no private key or local reading data should
   ever be included.
2. Open the official Play Protect appeal form:
   https://support.google.com/googleplay/android-developer/contact/protectappeals
3. Supply the developer's contact email, package name, APK SHA-256 and the text
   below. Developer name is optional; the GitHub publisher is Vengeful-Pancake.
4. Submission was authorized after the draft was prepared. The user supplied
   the contact email; form submission was visibly confirmed. The remaining
   action is clicking Google's verification link in that contact mailbox.

Google's form currently states that appeal decisions are final and that a reply
is not guaranteed/provided. The request is for classification review; clearance
cannot be promised. Preserve the installed app and reading-data backups while
the block is investigated. Do not disable device protection to validate a build.

## English text for "Additional information"

The live Google form limits this field to 1,000 characters. The prepared form
uses this 945-character summary; the longer evidence text follows for reference.

> Please review the Play Protect classification of Shelf 0.3.5 (com.shelf.reader, build 10). A user receives Harmful app blocked / installing this app may put your device at risk.
>
> Shelf is an EPUB reader/browser with offline Chinese-Vietnamese translation. Permissions are INTERNET, REQUEST_INSTALL_PACKAGES and an AndroidX app-private receiver permission; no SMS, Accessibility or notification listener. Self-updates require user consent and verify SHA-256, package, version, increasing build and the installed signing certificate before Android's installer. There is no silent installation. The APK has a valid v2 signature; that alone does not establish why it was flagged. Please review this exact artifact.
>
> APK: https://github.com/Vengeful-Pancake/ShelfAndroid/releases/download/v0.3.5/Shelf-0.3.5.apk
> Source: https://github.com/Vengeful-Pancake/ShelfAndroid/tree/v0.3.5
>
> VirusTotal currently shows no detections; Google engine: Undetected.

Please review the Google Play Protect classification of Shelf 0.3.5
(package com.shelf.reader, versionCode 10), distributed outside Google Play.
A user reports "Harmful app blocked" and "installing this app may put your
device at risk" when installing the exact public release linked below. The
warning does not identify a specific malware category or offending behavior.

APK SHA-256:
5f96b883a40792ae2b9f64c1b637012c5087c37cd3b109c9d2c395ee1ae13e67

APK download:
https://github.com/Vengeful-Pancake/ShelfAndroid/releases/download/v0.3.5/Shelf-0.3.5.apk

Source:
https://github.com/Vengeful-Pancake/ShelfAndroid/tree/v0.3.5

Shelf is a local EPUB reader and user-operated web browser with offline
Chinese-to-Vietnamese dictionary translation. User-selected books, reading
positions and custom translation rules remain in app-private local storage.
The app's effective permissions are INTERNET, REQUEST_INSTALL_PACKAGES and an
AndroidX app-private receiver permission. It does not declare SMS, Accessibility,
Notification Listener, contacts, overlay or device-admin permissions.

REQUEST_INSTALL_PACKAGES is used only for user-approved self-updates from the
maintainer's public GitHub releases. The app validates the downloaded APK's
size, SHA-256, package, versionName, increasing versionCode and exact installed
signing certificate, then opens Android's installer for user confirmation.
There is no silent installation or downloaded DEX execution in the reviewed
application-owned code. The updater FileProvider is non-exported and limited
to the update cache directory.

Our preliminary review verified standard APK/ZIP structure and a valid APK v2
signature with the same RSA-4096 certificate used by preceding releases.
The native libraries packaged are AndroidX graphics-path and DataStore
shared-counter libraries. The source is public for inspection. These checks
do not establish why Google classified this APK; we request a review of this
specific artifact's classification.

## Primary references

- https://developers.google.com/android/play-protect/warning-dev-guidance
- https://support.google.com/googleplay/android-developer/answer/2992033
- https://support.google.com/googleplay/android-developer/contact/protectappeals
