package dev.natsumi.wetype.markchanger

import io.github.libxposed.service.XposedService

object XposedServiceManager {
    @Volatile
    var service: XposedService? = null
        internal set

    val isAvailable: Boolean
        get() = service != null
}
