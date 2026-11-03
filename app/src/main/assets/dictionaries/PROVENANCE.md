# Bundled translation data

On 3 November 2026 the project user explicitly requested public publication of
the source and APK including these dictionaries. Original provenance and license
notes below are retained; public availability does not grant a new license.

Copied for this app from the user's TangThu Studio 1.0.0 project:
resources/dictionaries/Dictionary_Smooth_Xianxia/.

Includes ChinesePhienAmWords.txt, VietPhrase.compact.txt, Names.txt, Names2.txt,
VietPhraseOverride.txt and IgnoredChinesePhrases.txt.

The Android matcher and punctuation writer adapt TangThu's translation code in
src/tangthu/translation/ and engine/src/TangThu.Engine/Translation/.
Later dictionary files override matching keys in earlier files; global user
names and terminology take priority over bundled phrases.

TangThu's LICENSE_NOTES.md describes these as private Smooth Xianxia /
QuickTranslator dictionaries and says to preserve attribution and review
provenance before redistribution. Copying these files does not grant a new
license. The upstream project-specific source reserves rights unless a file
states otherwise. This Android integration does not include NLLB or an AI model.

The browser runtime adapts the text-node and mutation translation approach of
the STV RealtimeChineseTranslate extension supplied in R:/C2V/src/inject/.
Chrome extension APIs are replaced by an Android WebView messaging adapter and
the local translator; no STV translation-server call is required.
