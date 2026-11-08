package io.github.shaonialife.carptocar.carlife

/** 静默很久后开始播放，不代表这次播放已经断流；格式切换也给独立宽限期。 */
internal class AudioExpectationWindow {
    private var expectedSince: Long? = null
    private var formatAt: Long? = null

    @Synchronized fun observe(now: Long, expected: Boolean) {
        if (!expected) expectedSince = null
        else if (expectedSince == null) expectedSince = now
    }

    @Synchronized fun format(now: Long) { formatAt = now }

    @Synchronized fun ready(now: Long, graceMs: Long): Boolean {
        val since = expectedSince ?: return false
        return now - since >= graceMs && (formatAt == null || now - formatAt!! >= graceMs)
    }
}
