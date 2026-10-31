package app.d60link

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Status {
    data object NoCamera : Status
    data object MassStorage : Status
    data object NeedPermission : Status
    data object Connecting : Status
    data class Reading(val done: Int, val total: Int) : Status
    data object Ready : Status
    data class Error(val message: String) : Status
}

data class Progress(val done: Int, val total: Int, val failed: Int, val deleting: Boolean = false)

data class UiState(
    val status: Status = Status.NoCamera,
    val model: String = "",
    val items: List<Item> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val selected: Set<Int> = emptySet(),
    val onlyNew: Boolean = false,
    val progress: Progress? = null,
    val storage: Pair<Long, Long>? = null,
    val settings: Settings = Settings(),
    val message: String? = null,
    /** Archivos recién bajados que se ofrece borrar de la tarjeta. */
    val offerDelete: List<Photo>? = null,
) {
    fun isNew(item: Item): Boolean = item.filesFor(settings.pairMode).any { it.key !in downloaded }
    val newItems: List<Item> get() = items.filter(::isNew)
    val visibleItems: List<Item> get() = if (onlyNew) newItems else items
    val selecting: Boolean get() = selected.isNotEmpty()
    val selectedItems: List<Item> get() = items.filter { it.id in selected }
    val busy: Boolean get() = progress != null
}

/**
 * Estado y lógica de la app. Vive en la Application, no en la Activity,
 * para que la descarga siga aunque se cierre la pantalla.
 */
class Controller(private val app: Application) {

    private val scope = MainScope()
    private val usb = app.getSystemService(UsbManager::class.java)
    private val prefs = app.getSharedPreferences("downloaded", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(downloaded = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet(), settings = Settings.load(app)),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var camera: Camera? = null
    private var job: Job? = null

    /** Hay una descarga o un borrado en marcha. */
    val isWorking: Boolean get() = job?.isActive == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(device)
                    else setStatus(Status.NeedPermission)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice() ?: return
                    if (device.deviceName == camera?.usbDevice?.deviceName) disconnect()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---- USB ----

    fun handleIntent(intent: Intent?) {
        val attached = intent?.takeIf { it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }?.usbDevice()
        if (attached != null && isCamera(attached) && usb.hasPermission(attached)) connect(attached) else scan()
    }

    /** Busca la cámara entre los dispositivos USB conectados. */
    fun scan() {
        val devices = usb.deviceList.values
        val cam = devices.firstOrNull(::isCamera)
        when {
            cam == null && devices.any(::isNikonMassStorage) -> setStatus(Status.MassStorage)
            cam == null -> if (_state.value.status !is Status.Ready) setStatus(Status.NoCamera)
            usb.hasPermission(cam) -> connect(cam)
            else -> {
                setStatus(Status.NeedPermission)
                val pi = PendingIntent.getBroadcast(
                    app,
                    0,
                    Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                usb.requestPermission(cam, pi)
            }
        }
    }

    private fun connect(device: UsbDevice) {
        if (camera?.usbDevice?.deviceName == device.deviceName &&
            _state.value.status.let { it is Status.Ready || it is Status.Reading || it is Status.Connecting }
        ) return
        disconnect()
        val cam = Camera(app, device)
        camera = cam
        _state.update { it.copy(status = Status.Connecting, items = emptyList(), selected = emptySet()) }
        scope.launch {
            if (!cam.open()) {
                _state.update { it.copy(status = Status.Error(app.getString(R.string.err_open))) }
                return@launch
            }
            _state.update { it.copy(model = cam.model) }
            reload()
        }
    }

    fun reload() {
        val cam = camera ?: return
        scope.launch {
            _state.update { it.copy(status = Status.Reading(0, 0)) }
            val photos = cam.listPhotos { done, total ->
                _state.update { it.copy(status = Status.Reading(done, total)) }
            }
            _state.update {
                it.copy(items = Item.group(photos), selected = emptySet(), storage = cam.storage(), status = Status.Ready)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        camera?.close()
        camera = null
        _state.update {
            it.copy(
                status = Status.NoCamera, items = emptyList(), selected = emptySet(),
                progress = null, storage = null, offerDelete = null,
            )
        }
    }

    // ---- Imágenes ----

    suspend fun thumbnail(photo: Photo): Bitmap? = camera?.thumbnail(photo)
    suspend fun preview(photo: Photo): Bitmap? = camera?.preview(photo)

    // ---- Selección y ajustes ----

    fun toggleSelect(item: Item) = _state.update {
        it.copy(selected = if (item.id in it.selected) it.selected - item.id else it.selected + item.id)
    }

    fun selectAll(items: List<Item>) = _state.update {
        val ids = items.map { i -> i.id }.toSet()
        it.copy(selected = if (it.selected.containsAll(ids)) it.selected - ids else it.selected + ids)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }
    fun setOnlyNew(value: Boolean) = _state.update { it.copy(onlyNew = value) }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun dismissOfferDelete() = _state.update { it.copy(offerDelete = null) }

    fun updateSettings(settings: Settings) {
        settings.save(app)
        _state.update { it.copy(settings = settings) }
    }

    // ---- Descarga ----

    fun downloadSelected() = download(_state.value.selectedItems)
    fun downloadNew() = download(_state.value.newItems)
    fun downloadOne(item: Item) = download(listOf(item))

    private fun download(items: List<Item>) {
        val cam = camera ?: return
        val mode = _state.value.settings.pairMode
        val files = items.flatMap { it.filesFor(mode) }
        if (files.isEmpty() || job?.isActive == true) return

        DownloadService.start(app)
        job = scope.launch {
            var failed = 0
            val failedKeys = HashSet<String>()
            _state.update { it.copy(progress = Progress(0, files.size, 0), selected = emptySet()) }
            for ((i, photo) in files.withIndex()) {
                if (!isActive) break
                if (cam.download(photo)) markDownloaded(photo) else { failed++; failedKeys += photo.key }
                _state.update { it.copy(progress = Progress(i + 1, files.size, failed)) }
            }
            val ok = files.size - failed
            val res = app.resources
            val msg = when {
                failed == 0 -> res.getQuantityString(R.plurals.msg_saved, ok, ok, Camera.FOLDER)
                ok == 0 -> res.getString(R.string.msg_none_saved)
                else -> res.getString(R.string.msg_partial, ok, failed)
            }
            val done = files.filter { it.key !in failedKeys }
            _state.update {
                it.copy(
                    progress = null,
                    message = msg,
                    offerDelete = done.takeIf { d -> it.settings.offerDelete && d.isNotEmpty() },
                )
            }
        }
    }

    private fun markDownloaded(photo: Photo) {
        _state.update { it.copy(downloaded = it.downloaded + photo.key) }
        prefs.edit().putStringSet(KEY, HashSet(_state.value.downloaded)).apply()
    }

    // ---- Borrado de la tarjeta ----

    /** Borra de la tarjeta todos los archivos de las fotos seleccionadas (RAW y JPG). */
    fun deleteSelected() = deleteFromCard(_state.value.selectedItems.flatMap { it.files })

    /** Borra de la tarjeta los archivos que se acaban de bajar. */
    fun deleteOffered() = deleteFromCard(_state.value.offerDelete.orEmpty())

    private fun deleteFromCard(files: List<Photo>) {
        val cam = camera ?: return
        if (files.isEmpty() || job?.isActive == true) return

        DownloadService.start(app)
        job = scope.launch {
            val deleted = HashSet<Int>()
            _state.update { it.copy(progress = Progress(0, files.size, 0, deleting = true), selected = emptySet(), offerDelete = null) }
            for ((i, photo) in files.withIndex()) {
                if (!isActive) break
                if (cam.deleteFromCard(photo)) deleted += photo.handle
                _state.update { it.copy(progress = Progress(i + 1, files.size, i + 1 - deleted.size, deleting = true)) }
            }
            val failed = files.size - deleted.size
            val res = app.resources
            val msg = if (failed == 0) res.getQuantityString(R.plurals.msg_deleted, deleted.size, deleted.size)
            else res.getString(R.string.msg_delete_partial, deleted.size, failed)
            _state.update { s ->
                val remaining = s.items.mapNotNull { item ->
                    val jpg = item.jpg?.takeUnless { it.handle in deleted }
                    val raw = item.raw?.takeUnless { it.handle in deleted }
                    if (jpg == null && raw == null) null else Item(jpg, raw)
                }
                s.copy(items = remaining, progress = null, message = msg)
            }
            _state.update { it.copy(storage = cam.storage()) }
        }
    }

    // ---- Utilidades ----

    private fun setStatus(status: Status) = _state.update { it.copy(status = status) }

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
        const val KEY = "keys"
        const val ACTION_USB_PERMISSION = "app.d60link.USB_PERMISSION"
        const val NIKON_VENDOR_ID = 0x04B0
    }
}

@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
