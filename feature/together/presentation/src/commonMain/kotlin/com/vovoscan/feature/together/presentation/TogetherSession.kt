package com.vovoscan.feature.together.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.TogetherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The circle this account reads in, kept while the app runs: reader screens come and go (the next chapter replaces
 * the reader), the circle stays. What was shown and applied is kept here too, so a new reader screen neither shows a
 * signal twice nor pulls the reader back to an older place.
 */
class TogetherSession(
    private val repository: TogetherRepository,
    private val scope: CoroutineScope,
) {
    private val current = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = current.asStateFlow()

    /** The newest `positionSeq` this device applied (followers glide only to newer places). */
    internal var appliedSeq = -1L

    /** The chapter a follower's reader was sent to, once. */
    internal var openedChapter: String? = null

    /** The local choice to follow or not, until a snapshot agrees with it (a late snapshot never undoes a tap). */
    internal var following: Boolean? = null

    /** `GET /reading-rooms` asked once per launch (a room left open from before, or from another device). */
    internal var looked = false

    private val shown = LinkedHashSet<String>()

    internal fun shown(id: String): Boolean = id in shown

    internal fun markShown(id: String) {
        shown += id
        while (shown.size > SHOWN_KEPT) shown.remove(shown.first())
    }

    internal fun set(room: Room) {
        val before = current.value
        if (before != null && before.id != room.id) reset()
        if (following == room.following) following = null
        current.value = room
    }

    fun clear() {
        current.value = null
        reset()
    }

    private fun reset() {
        appliedSeq = -1
        openedChapter = null
        following = null
    }

    /**
     * The host's reader opens another chapter: the circle moves first (`PUT /chapter`), in the app's scope since the
     * reader screen that asked is going away. Another series is refused by the server: the circle stays where it is.
     */
    internal fun moveOn(chapterId: String) {
        val room = current.value ?: return
        if (!room.isHost || room.chapter.id == chapterId) return
        scope.launch {
            val moved = repository.moveTo(room.id, chapterId, null)
            if (moved is AppResult.Ok) set(moved.value)
        }
    }

    private companion object {
        const val SHOWN_KEPT = 300
    }
}
