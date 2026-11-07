package de.enginecompanion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbStorageState(val connected: Boolean = false, val deviceCount: Int = 0)

/** Observes USB mass-storage interfaces only. File access still goes through SAF. */
class UsbStorageMonitor(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val mutable = MutableStateFlow(scan())
    val state = mutable.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    }

    fun refresh() { mutable.value = scan() }

    fun close() { runCatching { context.unregisterReceiver(receiver) } }

    private fun scan(): UsbStorageState {
        val devices = manager?.deviceList?.values.orEmpty().filter(::isMassStorage)
        return UsbStorageState(devices.isNotEmpty(), devices.size)
    }

    companion object {
        internal fun isMassStorage(device: UsbDevice): Boolean {
            return isMassStorage(
                device.deviceClass,
                (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass }
            )
        }

        internal fun isMassStorage(deviceClass: Int, interfaceClasses: List<Int>): Boolean =
            deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
                UsbConstants.USB_CLASS_MASS_STORAGE in interfaceClasses
    }
}
