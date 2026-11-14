package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.audio.AudioOutputMode

/** Single-worker decisions; transport publication and lifetime are injected for deterministic regressions. */
internal class Q4AudioArbitrator(
    private val resources: Q4AudioResources,
    private val input: () -> Input,
    private val alive: () -> Boolean,
    private val publish: (Q4AudioResources.Proposal) -> Boolean,
    private val output: (AudioOutputMode) -> Unit,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val log: (String) -> Unit,
) {
    data class Input(val media: Boolean, val navigation: Boolean, val userVersion: Long, val source: Any?)
    private data class Resume(val userVersion: Long, val source: Any?, val takeRevision: Long)
    private var resumeTicket: Resume? = null
    private var seenTake = resources.snapshot().takeRevision
    private var attempted: Pair<Q4AudioResources.State, Input>? = null
    private var lastSummary: String? = null

    fun step() {
        if (!alive()) return
        var state = resources.snapshot()
        var desired = input()
        if (state.takeRevision != seenTake) {
            seenTake = state.takeRevision
            resumeTicket = null
            output(AudioOutputMode.DROP)
            if (desired.media) {
                log("AUDIO-RESOURCE: action=pause reason=permanent-take autoResume=false")
                pause()
                desired = input()
            }
        }
        if (!alive()) return
        resumeTicket?.let {
            if (it.userVersion != desired.userVersion || it.source != desired.source || it.takeRevision != state.takeRevision) {
                log("AUDIO-RESOURCE: autoResume=cancelled reason=user-source-or-owner-changed")
                resumeTicket = null
            }
        }
        if (state.borrowers > 0) {
            output(AudioOutputMode.DROP)
            if (desired.media) {
                resumeTicket = Resume(desired.userVersion, desired.source, state.takeRevision)
                log("AUDIO-RESOURCE: action=pause reason=car-borrow borrowers=${state.borrowers} autoResume=armed")
                pause()
            }
            report(state, input(), "car-borrow")
            return
        }
        if (state.owned && resumeTicket != null) {
            resumeTicket = null
            if (!alive()) return
            log("AUDIO-RESOURCE: action=resume reason=matching-borrow-return")
            resume()
            desired = input()
        }
        if (!alive()) return
        val proposal = when {
            desired.media -> resources.prepare(temporary = false)
            desired.navigation -> resources.prepare(temporary = true)
            state.borrowedByPhone -> resources.release()
            else -> null
        }
        val attemptKey = state to desired
        if (proposal != null && attempted != attemptKey) {
            attempted = attemptKey
            output(if (proposal.action == "return-navigation") AudioOutputMode.DROP else AudioOutputMode.HOLD)
            log("AUDIO-RESOURCE: request=${proposal.action} revision=${state.revision} media=${desired.media} navigation=${desired.navigation}")
            val accepted = publish(proposal)
            if (!alive()) return
            state = resources.snapshot()
            log("AUDIO-RESOURCE: result=${proposal.action} accepted=$accepted revision=${state.revision} owned=${state.owned}; not-audibility-confirmation")
            if (!accepted) {
                output(AudioOutputMode.DROP)
                report(state, desired, "request-rejected-or-stale")
                return
            }
        } else if (proposal != null && attempted == attemptKey) {
            output(AudioOutputMode.DROP)
            return // A rejection is not retried every tick. New intent/car state can retry.
        }
        if (!alive()) return
        output(if (state.owned) AudioOutputMode.ALLOW else AudioOutputMode.DROP)
        report(state, desired, if (state.owned) "phone-owner" else "waiting-for-intent-or-constraint")
    }

    private fun report(state: Q4AudioResources.State, desired: Input, reason: String) {
        val summary = "revision=${state.revision} permanent=${state.permanent} phoneBorrow=${state.borrowedByPhone} " +
            "carBorrowers=${state.borrowers} media=${desired.media} navigation=${desired.navigation} " +
            "takeConstraint=${state.takeConstraint} borrowConstraint=${state.borrowConstraint} " +
            "autoResume=${resumeTicket != null} reason=$reason"
        if (summary != lastSummary) { lastSummary = summary; log("AUDIO-RESOURCE: $summary") }
    }
}
