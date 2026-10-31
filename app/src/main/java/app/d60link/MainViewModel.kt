package app.d60link

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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

data class DownloadProgress(val done: Int, val total: Int, val failed: Int)

data class UiState(
    val status: Status = Status.NoCamera,
    val model: String = "",
    val photos: List<Photo> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val selected: Set<Int> = emptySet(),
    val onlyNew: Boolean = false,
    val download: DownloadProgress? = null,
    val message: String? = null,
) {
    val newPhotos: List<Photo> get() = photos.filter { it.key !in downloaded }
    val visiblePhotos: List<Photo> get() = if (onlyNew) newPhotos else photos
    val selecting: Boolean get() = selected.isNotEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("downloaded", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(downloaded = loadDownloaded()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var camera: Camera? = null
    private var downloadJob: Job? = null

    fun setStatus(status: Status) = _state.update { it.copy(status = status) }

    fun connect(device: UsbDevice) {
        if (camera?.usbDevice?.deviceName == device.deviceName && _state.value.status is Status.Ready) return
        disconnect()
        val cam = Camera(getApplication(), device)
        camera = cam
        _state.update { it.copy(status = Status.Connecting, photos = emptyList(), selected = emptySet()) }
        viewModelScope.launch {
            if (!cam.open()) {
                _state.update { it.copy(status = Status.Error("No he podido abrir la cámara. Desconecta y vuelve a conectar el cable.")) }
                return@launch
            }
            _state.update { it.copy(model = cam.model, status = Status.Reading(0, 0)) }
            reload()
        }
    }

    fun reload() {
        val cam = camera ?: return
        viewModelScope.launch {
            _state.update { it.copy(status = Status.Reading(0, 0)) }
            val photos = cam.listPhotos { done, total ->
                _state.update { it.copy(status = Status.Reading(done, total)) }
            }
            _state.update { it.copy(photos = photos, selected = emptySet(), status = Status.Ready) }
        }
    }

    fun disconnect() {
        downloadJob?.cancel()
        camera?.close()
        camera = null
        _state.update {
            it.copy(status = Status.NoCamera, photos = emptyList(), selected = emptySet(), download = null)
        }
    }

    suspend fun thumbnail(photo: Photo): Bitmap? = camera?.thumbnail(photo)
    suspend fun preview(photo: Photo): Bitmap? = camera?.preview(photo)

    fun toggleSelect(photo: Photo) = _state.update {
        val sel = if (photo.handle in it.selected) it.selected - photo.handle else it.selected + photo.handle
        it.copy(selected = sel)
    }

    fun selectAll(photos: List<Photo>) = _state.update {
        val handles = photos.map { p -> p.handle }.toSet()
        val sel = if (it.selected.containsAll(handles)) it.selected - handles else it.selected + handles
        it.copy(selected = sel)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun setOnlyNew(value: Boolean) = _state.update { it.copy(onlyNew = value) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun downloadSelected() {
        val s = _state.value
        download(s.photos.filter { it.handle in s.selected })
    }

    fun downloadNew() = download(_state.value.newPhotos)

    fun downloadOne(photo: Photo) = download(listOf(photo))

    private fun download(photos: List<Photo>) {
        val cam = camera ?: return
        if (photos.isEmpty() || downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            var failed = 0
            _state.update { it.copy(download = DownloadProgress(0, photos.size, 0), selected = emptySet()) }
            for ((i, photo) in photos.withIndex()) {
                if (!isActive) break
                if (cam.download(photo)) markDownloaded(photo) else failed++
                _state.update { it.copy(download = DownloadProgress(i + 1, photos.size, failed)) }
            }
            val ok = photos.size - failed
            val msg = when {
                failed == 0 -> "$ok ${if (ok == 1) "foto guardada" else "fotos guardadas"} en DCIM/${Camera.FOLDER}"
                ok == 0 -> "No se ha podido guardar ninguna foto"
                else -> "$ok guardadas, $failed con error"
            }
            _state.update { it.copy(download = null, message = msg) }
        }
    }

    private fun markDownloaded(photo: Photo) {
        _state.update { it.copy(downloaded = it.downloaded + photo.key) }
        prefs.edit().putStringSet(KEY, HashSet(_state.value.downloaded)).apply()
    }

    private fun loadDownloaded(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    override fun onCleared() {
        camera?.close()
        camera = null
    }

    private companion object {
        const val KEY = "keys"
    }
}
