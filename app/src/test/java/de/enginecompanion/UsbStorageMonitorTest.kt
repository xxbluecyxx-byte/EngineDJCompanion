package de.enginecompanion

import android.hardware.usb.UsbConstants
import de.enginecompanion.ui.UsbStorageMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStorageMonitorTest {
    @Test fun detectsMassStorageDeviceClass() {
        assertTrue(UsbStorageMonitor.isMassStorage(UsbConstants.USB_CLASS_MASS_STORAGE, emptyList()))
    }

    @Test fun detectsCompositeDeviceMassStorageInterface() {
        assertTrue(UsbStorageMonitor.isMassStorage(0, listOf(UsbConstants.USB_CLASS_AUDIO, UsbConstants.USB_CLASS_MASS_STORAGE)))
    }

    @Test fun rejectsUnrelatedUsbDevice() {
        assertFalse(UsbStorageMonitor.isMassStorage(0, listOf(UsbConstants.USB_CLASS_AUDIO)))
    }
}
