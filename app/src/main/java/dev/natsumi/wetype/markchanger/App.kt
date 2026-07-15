package dev.natsumi.wetype.markchanger

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "Xposed service bind: ${service.getFrameworkName()} (${service.getFrameworkVersion()}) API ${service.getApiVersion()}")
                XposedServiceManager.service = service
            }

            override fun onServiceDied(service: XposedService) {
                Log.w(TAG, "Xposed service died")
                XposedServiceManager.service = null
            }
        })
    }

    companion object {
        private const val TAG = "App"
    }
}
