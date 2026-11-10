package io.github.shaonialife.carptocar.carlife

/** Per-HU observation only. Partial module reports do not erase earlier VR state. */
internal class VoiceModuleObservation {
    private var status: Int? = null
    private var emitted = 0
    fun update(modules: List<Pair<Int, Int>>): String? {
        val values = modules.filter { it.first == 4 }.map { it.second }.distinct()
        if (values.size != 1 || values.single() == status) return null
        val before = status
        status = values.single()
        if (emitted++ >= 32) return null
        return "AUDIO-DIAG: VR module previous=$before current=$status; engine state only, key-command causality unconfirmed"
    }
}
