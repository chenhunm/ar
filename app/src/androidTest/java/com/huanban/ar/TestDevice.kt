package com.huanban.ar

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

/** Fixture cleanup only: never dismisses this app's own crash/ANR dialogs. */
internal object TestDevice {
    fun prepare() {
        // Continuous 3D/statistics updates never produce a long accessibility idle
        // interval. Use explicit element/state waits instead of the default 10s idle.
        androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(100)
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("settings put system screen_off_timeout 1800000")
        repeat(5) {
            val title=device.findObject(By.res("android:id/alertTitle"))?.text.orEmpty()
            if(title=="System UI isn't responding" || title=="Process system isn't responding") {
                device.findObject(By.res("android:id/aerr_wait"))?.click()
                SystemClock.sleep(500)
            } else return
        }
    }
}
