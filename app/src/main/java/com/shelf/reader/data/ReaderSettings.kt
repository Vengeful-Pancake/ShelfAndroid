package com.shelf.reader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "reader_settings")

/** Typeface choices that actually ship with Android, including Vietnamese coverage. */
enum class ReaderFont(val label: String, val css: String) {
    SERIF("Serif", "'Noto Serif', serif"),
    SANS("Sans", "'Roboto', sans-serif"),
    CONDENSED("Condensed", "'Roboto Condensed', 'Roboto', sans-serif"),
    MONO("Mono", "'Roboto Mono', monospace"),
    SYSTEM("System", "system-ui, sans-serif");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.name == key } ?: SERIF
    }
}

data class ReaderSettings(
    val background: String = "#ECEBE8",
    val foreground: String = "#1F1F1F",
    val font: ReaderFont = ReaderFont.SERIF,
    val textSize: Int = 19,
    val lineHeight: Float = 1.7f,
    /** Side gutter in dp — the phone equivalent of the desktop column-width slider. */
    val margin: Int = 20,
    val justify: Boolean = false,
    val indent: Boolean = false,
    val keepScreenOn: Boolean = true,
    val tapToTurn: Boolean = true,
    val fullscreen: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val ttsVoice: String? = null,
    val titleMaxLines: Int = 2,
    val titleFontSize: Int = 14,
    val wordReplacements: String = "",
    val wordReplacementsUri: String? = null,
    val twoColumns: Boolean = false,
    val useFilenameAsTitle: Boolean = false,
    /** Append the next section below the current one instead of stopping at its end. */
    val continuousScroll: Boolean = true,
    /** Library shows text-only cards, with no cover thumbnails anywhere. */
    val hideThumbnails: Boolean = false,
    val gridDensity: String = "adaptive",
    val sort: String = "recent",
    val filter: String = "all",
) {
    val align: String get() = if (justify) "justify" else "left"

    /** Page colours dark enough that the shell should switch to a light-on-dark reader. */
    val isDarkPage: Boolean get() = luminance(background) < 0.5f

    companion object {
        val PAGE_COLORS = listOf(
            "#ECEBE8", "#FFFFFF", "#F6EFDC", "#E9E3D4",
            "#DFE7DF", "#FDF6E3", "#002B36", "#3E2723",
            "#0D1117", "#1E2126", "#111315", "#2B2622",
        )
        val TEXT_COLORS = listOf(
            "#1F1F1F", "#000000", "#3A3226", "#26343A",
            "#657B83", "#839496", "#D7CCC8", "#C9D1D9",
            "#D8D4CC", "#B9B4AA", "#9FB3A6", "#E0C9A8",
        )

        fun luminance(hex: String): Float {
            val value = hex.removePrefix("#").toLongOrNull(16) ?: return 1f
            val r = ((value shr 16) and 0xFF).toFloat()
            val g = ((value shr 8) and 0xFF).toFloat()
            val b = (value and 0xFF).toFloat()
            return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        fun isDark(hex: String): Boolean = luminance(hex) < 0.5f
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BG = stringPreferencesKey("bg")
        val FG = stringPreferencesKey("fg")
        val FONT = stringPreferencesKey("font")
        val SIZE = intPreferencesKey("size")
        val LINE = floatPreferencesKey("line")
        val MARGIN = intPreferencesKey("margin")
        val JUSTIFY = booleanPreferencesKey("justify")
        val INDENT = booleanPreferencesKey("indent")
        val KEEP_ON = booleanPreferencesKey("keep_screen_on")
        val TAP = booleanPreferencesKey("tap_to_turn")
        val FULLSCREEN = booleanPreferencesKey("fullscreen")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TITLE_LINES = intPreferencesKey("title_max_lines")
        val TITLE_SIZE = intPreferencesKey("title_font_size")
        val REPLACEMENTS = stringPreferencesKey("word_replacements")
        val REPLACEMENTS_URI = stringPreferencesKey("word_replacements_uri")
        val TWO_COLUMNS = booleanPreferencesKey("two_columns")
        val USE_FILENAME = booleanPreferencesKey("use_filename_as_title")
        val CONTINUOUS = booleanPreferencesKey("continuous_scroll")
        val HIDE_THUMBS = booleanPreferencesKey("hide_thumbnails")
        val DENSITY = stringPreferencesKey("grid_density")
        val COLUMNS = intPreferencesKey("grid_columns") // Keep for migration
        val SORT = stringPreferencesKey("sort")
        val FILTER = stringPreferencesKey("filter")
    }

    val flow: Flow<ReaderSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): ReaderSettings = flow.first()

    suspend fun replace(settings: ReaderSettings) = update { settings }

    private fun Preferences.toSettings() = ReaderSettings(
        background = this[Keys.BG] ?: ReaderSettings().background,
        foreground = this[Keys.FG] ?: ReaderSettings().foreground,
        font = ReaderFont.from(this[Keys.FONT]),
        textSize = this[Keys.SIZE] ?: ReaderSettings().textSize,
        lineHeight = this[Keys.LINE] ?: ReaderSettings().lineHeight,
        margin = this[Keys.MARGIN] ?: ReaderSettings().margin,
        justify = this[Keys.JUSTIFY] ?: false,
        indent = this[Keys.INDENT] ?: false,
        keepScreenOn = this[Keys.KEEP_ON] ?: true,
        tapToTurn = this[Keys.TAP] ?: true,
        fullscreen = this[Keys.FULLSCREEN] ?: true,
        ttsSpeed = this[Keys.TTS_SPEED] ?: 1.0f,
        ttsVoice = this[Keys.TTS_VOICE]?.takeIf { it.isNotBlank() },
        titleMaxLines = this[Keys.TITLE_LINES] ?: 2,
        titleFontSize = this[Keys.TITLE_SIZE] ?: 14,
        wordReplacements = this[Keys.REPLACEMENTS] ?: "",
        wordReplacementsUri = this[Keys.REPLACEMENTS_URI]?.takeIf { it.isNotBlank() },
        twoColumns = this[Keys.TWO_COLUMNS] ?: false,
        useFilenameAsTitle = this[Keys.USE_FILENAME] ?: false,
        continuousScroll = this[Keys.CONTINUOUS] ?: true,
        hideThumbnails = this[Keys.HIDE_THUMBS] ?: false,
        gridDensity = this[Keys.DENSITY] ?: migrateColumns(this[Keys.COLUMNS]),
        sort = this[Keys.SORT] ?: "recent",
        filter = this[Keys.FILTER] ?: "all",
    )

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.BG] = next.background
            prefs[Keys.FG] = next.foreground
            prefs[Keys.FONT] = next.font.name
            prefs[Keys.SIZE] = next.textSize
            prefs[Keys.LINE] = next.lineHeight
            prefs[Keys.MARGIN] = next.margin
            prefs[Keys.JUSTIFY] = next.justify
            prefs[Keys.INDENT] = next.indent
            prefs[Keys.KEEP_ON] = next.keepScreenOn
            prefs[Keys.TAP] = next.tapToTurn
            prefs[Keys.FULLSCREEN] = next.fullscreen
            prefs[Keys.TTS_SPEED] = next.ttsSpeed
            if (next.ttsVoice == null) prefs.remove(Keys.TTS_VOICE)
            else prefs[Keys.TTS_VOICE] = next.ttsVoice
            prefs[Keys.TITLE_LINES] = next.titleMaxLines
            prefs[Keys.TITLE_SIZE] = next.titleFontSize
            prefs[Keys.REPLACEMENTS] = next.wordReplacements
            if (next.wordReplacementsUri == null) prefs.remove(Keys.REPLACEMENTS_URI)
            else prefs[Keys.REPLACEMENTS_URI] = next.wordReplacementsUri
            prefs[Keys.TWO_COLUMNS] = next.twoColumns
            prefs[Keys.USE_FILENAME] = next.useFilenameAsTitle
            prefs[Keys.CONTINUOUS] = next.continuousScroll
            prefs[Keys.HIDE_THUMBS] = next.hideThumbnails
            prefs[Keys.DENSITY] = next.gridDensity
            prefs[Keys.SORT] = next.sort
            prefs[Keys.FILTER] = next.filter
        }
    }

    private fun migrateColumns(cols: Int?): String = when (cols) {
        1 -> "list"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        else -> "adaptive"
    }
}
