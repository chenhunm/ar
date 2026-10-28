package com.huanban.ar

import android.content.ComponentCallbacks2

object ResourcePolicy {
    /** UI_HIDDEN (20) is a normal app switch, not a request to hide a foreground desktop pet. */
    fun pausePetForMemory(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
}
