package io.github.shaonialife.carptocar.audio

/** HOLD is only for an in-flight ownership request. DROP is car mute/denial/retirement. */
enum class AudioOutputMode { ALLOW, HOLD, DROP }
