package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class MediaArtworkIdentityTest {
    private fun id(session: String = "session", pkg: String = "qqmusic",
        media: String? = null, artist: String? = "artist", album: String? = "album", duration: Long = 200000) =
        MediaArtworkIdentity.of(session, pkg, media, artist, album, duration)

    @Test fun fallbackDoesNotNeedTitleOrPlaybackPosition() {
        val first = id()
        assertEquals(first, id())
        assertEquals(first, id(media = " "))
    }

    @Test fun explicitMediaIdSurvivesLateDescriptiveMetadata() {
        val first = id(media = "songA")
        assertEquals(first, id(media = "songA", artist = null, album = null, duration = 0))
        assertNotEquals(first, id(media = "songB"))
    }

    @Test fun realFallbackChangesAndSessionChangesHaveDifferentIdentities() {
        val variants: List<() -> MediaArtworkIdentity> = listOf(
            { id(artist = "other") }, { id(album = "other") }, { id(duration = 210000) },
            { id(session = "other") }, { id(pkg = "other") }, { id(media = "songA") })
        for (change in variants) {
            val first = id()
            val next = change()
            assertNotEquals(first, next)
        }
    }
}
