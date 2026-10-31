package app.d60link

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class Photo(
    val handle: Int,
    val name: String,
    val size: Long,
    val date: Long,
    val isRaw: Boolean,
) {
    /** Identifica la foto aunque cambie el handle entre conexiones. */
    val key: String get() = "$name|$size|$date"
}

/**
 * Acceso PTP/MTP a la cámara. Todas las llamadas a MtpDevice van por un único hilo,
 * porque la API no es segura entre hilos.
 */
class Camera(private val context: Context, val usbDevice: UsbDevice) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mtpDispatcher = executor.asCoroutineDispatcher()
    private var device: MtpDevice? = null
    private val thumbs = LruCache<Int, Bitmap>(400)

    var model: String = "Cámara"
        private set

    suspend fun open(): Boolean = withContext(mtpDispatcher) {
        val usb = context.getSystemService(UsbManager::class.java)
        val connection = usb.openDevice(usbDevice) ?: return@withContext false
        val mtp = MtpDevice(usbDevice)
        if (!mtp.open(connection)) {
            connection.close()
            return@withContext false
        }
        device = mtp
        mtp.deviceInfo?.let { info ->
            val name = listOfNotNull(info.manufacturer, info.model)
                .joinToString(" ")
                .replace("Nikon Corporation", "Nikon")
                .trim()
            if (name.isNotEmpty()) model = name
        }
        true
    }

    suspend fun listPhotos(onProgress: (done: Int, total: Int) -> Unit): List<Photo> =
        withContext(mtpDispatcher) {
            val mtp = device ?: return@withContext emptyList()
            val handles = buildList {
                mtp.storageIds?.forEach { storage ->
                    // 0 = todos los objetos de la tarjeta, sin recorrer carpetas
                    mtp.getObjectHandles(storage, 0, 0)?.let { addAll(it.toList()) }
                }
            }
            val photos = ArrayList<Photo>(handles.size)
            handles.forEachIndexed { index, handle ->
                val info = mtp.getObjectInfo(handle)
                if (info != null && info.format != MtpConstants.FORMAT_ASSOCIATION) {
                    val name = info.name ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in PHOTO_EXTENSIONS) {
                        photos += Photo(
                            handle = handle,
                            name = name,
                            size = info.compressedSizeLong,
                            date = if (info.dateCreated > 0) info.dateCreated else info.dateModified,
                            isRaw = ext in RAW_EXTENSIONS,
                        )
                    }
                }
                if (index % 10 == 0 || index == handles.lastIndex) onProgress(index + 1, handles.size)
            }
            photos.sortedWith(compareByDescending<Photo> { it.date }.thenByDescending { it.name })
        }

    suspend fun thumbnail(photo: Photo): Bitmap? {
        thumbs.get(photo.handle)?.let { return it }
        val bytes = withContext(mtpDispatcher) { device?.getThumbnail(photo.handle) } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: return null
        thumbs.put(photo.handle, bitmap)
        return bitmap
    }

    /** Vista ampliada: el JPG completo reducido a ~2000 px. Los RAW se quedan en miniatura. */
    suspend fun preview(photo: Photo): Bitmap? {
        if (photo.isRaw || photo.size <= 0 || photo.size > MAX_PREVIEW_BYTES) return thumbnail(photo)
        val bytes = withContext(mtpDispatcher) {
            device?.getObject(photo.handle, photo.size.toInt())
        } ?: return thumbnail(photo)
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_MAX_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: thumbnail(photo)
    }

    /** Copia la foto a DCIM/NikonD60 vía MediaStore. */
    suspend fun download(photo: Photo): Boolean = withContext(mtpDispatcher) {
        val mtp = device ?: return@withContext false
        val resolver = context.contentResolver
        val mime = if (photo.isRaw) "image/x-nikon-nef" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (photo.date > 0) put(MediaStore.Images.ImageColumns.DATE_TAKEN, photo.date)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return@withContext false
        val ok = runCatching {
            resolver.openFileDescriptor(uri, "w")?.use { pfd -> mtp.importFile(photo.handle, pfd) } ?: false
        }.getOrDefault(false)
        if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            // Solo borra la entrada vacía que acabamos de crear nosotros
            runCatching { resolver.delete(uri, null, null) }
        }
        ok
    }

    fun close() {
        executor.execute {
            runCatching { device?.close() }
            device = null
        }
        executor.shutdown()
        thumbs.evictAll()
    }

    companion object {
        const val FOLDER = "NikonD60"
        private val RAW_EXTENSIONS = setOf("nef", "nrw")
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg") + RAW_EXTENSIONS
        private const val MAX_PREVIEW_BYTES = 25L * 1024 * 1024
        private const val PREVIEW_MAX_SIDE = 2048
    }
}
