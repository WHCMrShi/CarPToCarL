package io.github.shaonialife.carptocar.audio

internal enum class AudioMode(val label: String, val sampleRate: Int) {
    QUALITY("质量（48K PCM）", 48000), STABILITY("稳定（48K AAC）", 48000),
    AAC_44K_TEST("对照测试（44.1K AAC）", 44100);

    fun memoryIdentity(receiver: String): String = "${name.lowercase()}__$receiver"

    fun order(candidates: List<AudioProfile>): List<AudioProfile> {
        val preferred = candidates.filter { !it.isInput && it.sampleRate == sampleRate && it.audioType == "media" &&
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
