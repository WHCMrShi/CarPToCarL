package io.github.shaonialife.carptocar.audio

internal enum class AudioMode(val label: String) {
    QUALITY("质量（48K PCM）"), STABILITY("稳定（48K AAC）");

    fun order(candidates: List<AudioProfile>): List<AudioProfile> {
        val preferred = candidates.filter { !it.isInput && it.sampleRate == 48000 && it.audioType == "media" &&
            if (this == QUALITY) it.codec == AudioCodec.PCM && it.streamType == 100L
            else it.codec == AudioCodec.AAC_LC }
        return preferred.distinctBy { it.key }
    }
    companion object {
        fun decode(value: String?): AudioMode = entries.firstOrNull { it.name == value } ?: QUALITY
    }
}

internal object AudioModePrefs {
    private fun prefs(context: android.content.Context) = context.applicationContext.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    fun load(context: android.content.Context): AudioMode = AudioMode.decode(prefs(context).getString("audio_mode", null))
    fun save(context: android.content.Context, mode: AudioMode) { prefs(context).edit().putString("audio_mode", mode.name).apply() }
}
