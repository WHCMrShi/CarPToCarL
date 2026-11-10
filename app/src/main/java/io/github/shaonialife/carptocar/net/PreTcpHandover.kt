package io.github.shaonialife.carptocar.net

/** One short continuation per setup; repeated BT closures never extend its deadline. */
internal class PreTcpHandover(private val clock: () -> Long, private val windowMs: Long = 5_000L) {
    data class Claim(val remainingMs: Long, val first: Boolean)
    private var deadline: Long? = null
    @Synchronized fun retain(eligible: Boolean): Claim? {
        if (!eligible) return null
        val first = deadline == null
        val end = deadline ?: (clock() + windowMs).also { deadline = it }
        val remaining = end - clock()
        return if (remaining > 0) Claim(remaining, first) else null
    }
}
