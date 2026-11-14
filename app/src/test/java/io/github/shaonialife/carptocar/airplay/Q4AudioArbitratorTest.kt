package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.audio.AudioOutputMode
import org.junit.Assert.*
import org.junit.Test

class Q4AudioArbitratorTest {
    private class Fixture {
        val state = Q4AudioResources()
        var input = Q4AudioArbitrator.Input(false, false, 0, "source-song-A")
        var alive = true
        var pauses = 0; var resumes = 0; var requests = 0
        val modes = mutableListOf<AudioOutputMode>()
        var publication: (Q4AudioResources.Proposal) -> Boolean = { state.commit(it) }
        val arbiter = Q4AudioArbitrator(state, { input }, { alive },
            { requests++; publication(it) }, { modes.add(it) },
            { pauses++; input = input.copy(media = false) },
            { resumes++; input = input.copy(media = true) }, {})
        fun car(type: Int, id: String? = null) {
            state.change(mutableMapOf<String, Any>("transferType" to type).apply { id?.let { put("borrowID", it) } })
        }
    }
    @Test fun nestedTemporaryBorrowResumesOnlyOnceIfMediaWasPlaying() {
        val f = Fixture(); f.input = f.input.copy(media = true)
        f.car(3, "mute"); f.arbiter.step(); f.car(3, "call"); f.arbiter.step()
        assertEquals(1, f.pauses)
        f.car(4, "mute"); f.arbiter.step(); assertEquals(0, f.resumes)
        f.car(4, "call"); f.arbiter.step(); f.arbiter.step()
        assertEquals(1, f.resumes); assertEquals(AudioOutputMode.ALLOW, f.modes.last())
    }
    @Test fun idleBorrowDoesNotStartMusicAndPermanentTakeNeverAutoResumes() {
        val f = Fixture(); f.car(3, "mute"); f.arbiter.step(); f.car(4, "mute"); f.arbiter.step()
        assertEquals(0, f.resumes)
        f.input = f.input.copy(media = true); f.car(1); f.arbiter.step()
        assertEquals(1, f.pauses); assertEquals(0, f.requests)
        f.car(2); f.arbiter.step(); assertEquals(0, f.resumes)
    }
    @Test fun userPauseOrSourceChangeDuringMuteCancelsAutomaticResume() {
        for (sourceChanges in listOf(false, true)) {
            val f = Fixture(); f.input = f.input.copy(media = true)
            f.car(3, "mute"); f.arbiter.step()
            f.input = if (sourceChanges) f.input.copy(source = "new-song") else f.input.copy(userVersion = 1)
            f.car(4, "mute"); f.arbiter.step()
            assertEquals(0, f.resumes)
        }
    }
    @Test fun permanentTakeDuringMuteInvalidatesTheResumeTicket() {
        val f = Fixture(); f.input = f.input.copy(media = true)
        f.car(3, "mute"); f.arbiter.step(); f.car(1); f.arbiter.step()
        f.car(4, "mute"); f.car(2); f.arbiter.step()
        assertEquals(0, f.resumes)
    }
    @Test fun navigationBorrowsThenReturnsWithoutTakingRadioPermanently() {
        val f = Fixture(); f.car(1); f.arbiter.step()
        f.input = f.input.copy(navigation = true); f.arbiter.step()
        assertTrue(f.state.snapshot().borrowedByPhone); assertEquals(2L, f.state.snapshot().permanent)
        f.input = f.input.copy(navigation = false); f.arbiter.step()
        assertFalse(f.state.snapshot().owned); assertEquals(AudioOutputMode.DROP, f.modes.last())
    }
    @Test fun rejectionIsNotRetriedUntilFreshIntentAndLateReplyCannotStealBorrow() {
        val f = Fixture(); f.car(1); f.arbiter.step()
        f.publication = { false }; f.input = f.input.copy(media = true)
        repeat(20) { f.arbiter.step() }
        assertEquals(1, f.requests); assertEquals(AudioOutputMode.DROP, f.modes.last())
        f.input = f.input.copy(userVersion = 1)
        f.publication = { f.car(3, "call"); f.state.commit(it) }
        f.arbiter.step()
        assertEquals(2, f.requests); assertFalse(f.state.snapshot().owned)
        assertEquals(AudioOutputMode.DROP, f.modes.last())
    }
    @Test fun closedOwnerCannotEnableOutputAfterBlockingPublicationReturns() {
        val f = Fixture(); f.car(1); f.arbiter.step(); f.input = f.input.copy(navigation = true)
        f.publication = { f.alive = false; false }
        f.arbiter.step()
        assertEquals(AudioOutputMode.HOLD, f.modes.last()) // no callback after retirement
        val count = f.modes.size; f.arbiter.step(); assertEquals(count, f.modes.size)
    }
    @Test fun bothStartupOrdersKeepExistingPhoneOwnershipAndAllowLaterPlayback() {
        for (carLifeFirst in listOf(false, true)) {
            val f = Fixture(); f.input = f.input.copy(media = carLifeFirst)
            f.arbiter.step(); assertEquals(AudioOutputMode.ALLOW, f.modes.last())
            f.input = f.input.copy(media = true); f.arbiter.step()
            assertEquals(AudioOutputMode.ALLOW, f.modes.last()); assertEquals(0, f.requests)
        }
    }
}
