package com.vortexdbg.linux.android.dvm.api

import com.vortexdbg.arm.backend.BackendException
import com.vortexdbg.linux.android.dvm.DvmClass
import com.vortexdbg.linux.android.dvm.DvmObject
import com.vortexdbg.linux.android.dvm.VM

open class SystemService(vm: VM, serviceName: String) : DvmObject<String>(resolveObjectType(vm, serviceName), serviceName) {

    companion object {
        const val WIFI_SERVICE = "wifi"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val TELEPHONY_SERVICE = "phone"
        const val ACCESSIBILITY_SERVICE = "accessibility"
        const val KEYGUARD_SERVICE = "keyguard"
        const val ACTIVITY_SERVICE = "activity"
        const val SENSOR_SERVICE = "sensor"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val LOCATION_SERVICE = "location"
        const val WINDOW_SERVICE = "window"
        const val UI_MODE_SERVICE = "uimode"
        const val DISPLAY_SERVICE = "display"
        const val AUDIO_SERVICE = "audio"

        private fun resolveObjectType(vm: VM, serviceName: String): DvmClass {
            return when (serviceName) {
                TELEPHONY_SERVICE -> vm.resolveClass("android/telephony/TelephonyManager")
                WIFI_SERVICE -> vm.resolveClass("android/net/wifi/WifiManager")
                CONNECTIVITY_SERVICE -> vm.resolveClass("android/net/ConnectivityManager")
                ACCESSIBILITY_SERVICE -> vm.resolveClass("android/view/accessibility/AccessibilityManager")
                KEYGUARD_SERVICE -> vm.resolveClass("android/app/KeyguardManager")
                ACTIVITY_SERVICE -> vm.resolveClass("android/os/BinderProxy") // android/app/ActivityManager
                SENSOR_SERVICE -> vm.resolveClass("android/hardware/SensorManager")
                INPUT_METHOD_SERVICE -> vm.resolveClass("android/view/inputmethod/InputMethodManager")
                LOCATION_SERVICE -> vm.resolveClass("android/location/LocationManager")
                WINDOW_SERVICE -> vm.resolveClass("android/view/WindowManager")
                UI_MODE_SERVICE -> vm.resolveClass("android/app/UiModeManager")
                DISPLAY_SERVICE -> vm.resolveClass("android/hardware/display/DisplayManager")
                AUDIO_SERVICE -> vm.resolveClass("android/media/AudioManager")
                else -> throw BackendException("service failed: $serviceName")
            }
        }
    }

}
