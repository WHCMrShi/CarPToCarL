package io.github.shaonialife.carptocar.airplay

/** Audio-only adaptation of upstream c12d30e; screen ownership/streams are unchanged. */
internal class Q4AudioResources {
    data class State(
        val revision: Long, val takeRevision: Long, val permanent: Long, val borrowedByPhone: Boolean,
        val borrowers: Int, val takeConstraint: Int?, val borrowConstraint: Int?,
    ) {
        val owned: Boolean get() = borrowers == 0 && (permanent == 1L || borrowedByPhone)
        fun resource(): Map<String, Any> = mapOf("resourceID" to 2L,
            "entity" to if (owned) 1L else 2L, "permanentEntity" to permanent)
    }
    data class Proposal(val before: State, val after: State, val action: String)
    private var revision = 0L
    private var takeRevision = 0L
    private var permanent = 1L
    private var phoneBorrow = false
    private var takeConstraint: Int? = null
    private var borrowConstraint: Int? = null
    private val borrowers = linkedSetOf<String>()
    private var anonymous = 0
    private var overflow = false

    @Synchronized fun snapshot() = State(revision, takeRevision, permanent, phoneBorrow,
        borrowers.size + anonymous + if (overflow) 1 else 0, takeConstraint, borrowConstraint)

    @Synchronized fun change(item: Map<*, *>) {
        val type = (item["transferType"] as? Number)?.toInt() ?: return
        if (type !in 1..4) return
        val before = snapshot()
        val id = (item["borrowID"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
        when (type) {
            1 -> {
                permanent = 2; phoneBorrow = false
                takeConstraint = (item["takeConstraint"] as? Number)?.toInt()
                borrowConstraint = (item["borrowConstraint"] as? Number)?.toInt()
                takeRevision++
            }
            2 -> { permanent = 1; phoneBorrow = false; takeConstraint = null; borrowConstraint = null }
            3 -> {
                if (id == null) { if (anonymous < 16) anonymous++ else overflow = true }
                else if (borrowers.size < 16 || id in borrowers) borrowers.add(id) else overflow = true
            }
            4 -> if (id == null) { if (anonymous > 0) anonymous-- } else borrowers.remove(id)
        }
        if (snapshot() != before) revision++
    }

    @Synchronized fun prepare(temporary: Boolean): Proposal? {
        val state = snapshot()
        if (state.borrowers != 0) return null
        if (temporary) {
            if (state.owned || !allows(100, borrowConstraint)) return null
            return Proposal(state, state.copy(borrowedByPhone = true), "borrow-navigation")
        }
        if ((state.owned && !phoneBorrow) || !allows(500, takeConstraint)) return null
        return Proposal(state, state.copy(permanent = 1, borrowedByPhone = false), "take-media")
    }

    @Synchronized fun release(): Proposal? {
        val state = snapshot()
        return if (phoneBorrow) Proposal(state, state.copy(borrowedByPhone = false), "return-navigation") else null
    }

    @Synchronized fun commit(proposal: Proposal): Boolean {
        if (snapshot() != proposal.before) return false
        permanent = proposal.after.permanent
        phoneBorrow = proposal.after.borrowedByPhone
        revision++
        return true
    }

    private fun allows(priority: Int, constraint: Int?) =
        constraint == null || constraint == 100 || (constraint == 500 && priority == 500)

    companion object {
        fun accepted(statusLine: String, body: ByteArray): Boolean {
            if (statusLine.split(' ').getOrNull(1) != "200") return false
            if (body.isEmpty()) return true
            val response = runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull() ?: return false
            return !response.containsKey("status") || (response["status"] as? Number)?.toLong() == 0L
        }
    }
}
