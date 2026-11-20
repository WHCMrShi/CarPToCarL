package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.util.SessionEpoch

/** A packet uses one sender binding, published by the CarPlay session that installed it. */
internal class CarLifeAudioRoute<Owner : Any, Media : Any, Navigation : Any>(
    private val epoch: SessionEpoch,
    private val isQ4: (Owner) -> Boolean,
) {
    enum class Result { ROUTED, MEDIA_UNAVAILABLE, NAVIGATION_UNAVAILABLE, IGNORED }

    private class Binding<T>(val lease: SessionEpoch.Lease, val sender: T)

    @Volatile private var media: Binding<Media>? = null
    @Volatile private var navigation: Binding<Navigation>? = null

    val mediaSender: Media? get() = media?.sender
    val navigationSender: Navigation? get() = navigation?.sender

    fun publishMedia(lease: SessionEpoch.Lease, sender: Media) =
        epoch.commit(lease) { media = Binding(lease, sender) }

    fun publishNavigation(lease: SessionEpoch.Lease, sender: Navigation) =
        epoch.commit(lease) { navigation = Binding(lease, sender) }

    /** Caller holds the epoch lock during retirement. Sender shutdown happens after detaching. */
    fun detach() {
        media = null
        navigation = null
    }

    fun dispatch(
        owner: Owner?,
        q4NavigationProbe: Boolean,
        type: Int,
        sourceCurrent: () -> Boolean,
        forwardMedia: (Media) -> Boolean,
        forwardNavigation: (Navigation) -> Unit,
    ): Result {
        if (!sourceCurrent()) return Result.IGNORED
        if (q4NavigationProbe && owner != null && isQ4(owner)) {
            if (type != 0x40003) return Result.IGNORED
            val target = navigation?.takeIf { it.lease.owner === owner } ?: return Result.NAVIGATION_UNAVAILABLE
            if (!sourceCurrent() || !epoch.isCurrent(target.lease) || navigation !== target) return Result.IGNORED
            forwardNavigation(target.sender)
        } else {
            val target = media?.takeIf { it.lease.owner === owner } ?: return Result.MEDIA_UNAVAILABLE
            if (!sourceCurrent() || !epoch.isCurrent(target.lease) || media !== target) return Result.IGNORED
            if (!forwardMedia(target.sender)) return Result.MEDIA_UNAVAILABLE
        }
        return Result.ROUTED
    }
}
