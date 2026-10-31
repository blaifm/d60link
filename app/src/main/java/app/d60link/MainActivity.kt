package app.d60link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val usb by lazy { getSystemService(UsbManager::class.java) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        vm.connect(device)
                    } else {
                        vm.setStatus(Status.NeedPermission)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice() ?: return
                    if (isCamera(device)) vm.disconnect()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            D60Theme {
                App(vm = vm, onScan = ::scan)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val attached = intent?.takeIf { it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }?.usbDevice()
        if (attached != null && isCamera(attached) && usb.hasPermission(attached)) {
            vm.connect(attached)
        } else {
            scan()
        }
    }

    /** Busca la cámara entre los dispositivos USB conectados. */
    private fun scan() {
        val devices = usb.deviceList.values
        val camera = devices.firstOrNull(::isCamera)
        when {
            camera == null && devices.any(::isNikonMassStorage) -> vm.setStatus(Status.MassStorage)
            camera == null -> if (vm.state.value.status !is Status.Ready) vm.setStatus(Status.NoCamera)
            usb.hasPermission(camera) -> vm.connect(camera)
            else -> {
                vm.setStatus(Status.NeedPermission)
                val pi = PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                usb.requestPermission(camera, pi)
            }
        }
    }

    private fun isCamera(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
            }

    private fun isNikonMassStorage(device: UsbDevice): Boolean =
        device.vendorId == NIKON_VENDOR_ID &&
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }

    private companion object {
        const val ACTION_USB_PERMISSION = "app.d60link.USB_PERMISSION"
        const val NIKON_VENDOR_ID = 0x04B0
    }
}

@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
