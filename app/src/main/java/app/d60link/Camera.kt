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
import kotlin.math.abs

/** Un archivo de la tarjeta. */
data class Photo(
    val handle: Int,
    val name: String,
    val size: Long,
    val date: Long,
    val isRaw: Boolean,
) {
    /** Identifica el archivo aunque cambie el handle entre conexiones. */
    val key: String get() = "$name|$size|$date"
    val baseName: String get() = name.substringBeforeLast('.')
}

/** Una foto tal como se ve en la galería: un JPG, un RAW o una pareja RAW+JPG. */
data class Item(val jpg: Photo?, val raw: Photo?) {
    val id: Int get() = (jpg ?: raw)!!.handle
    val main: Photo get() = (jpg ?: raw)!!
    val date: Long get() = main.date
    val name: String get() = main.baseName
    val isPair: Boolean get() = jpg != null && raw != null
    val files: List<Photo> get() = listOfNotNull(jpg, raw)
    val totalSize: Long get() = files.sumOf { it.size }

    /** Archivos a bajar según la preferencia para parejas RAW+JPG. */
    fun filesFor(mode: PairMode): List<Photo> = when {
        !isPair -> files
        mode == PairMode.JPG -> listOf(jpg!!)
        mode == PairMode.RAW -> listOf(raw!!)
        else -> files
    }

    companion object {
        /** Empareja DSC_0001.JPG con DSC_0001.NEF si se hicieron en el mismo disparo. */
        fun group(photos: List<Photo>): List<Item> {
            val items = ArrayList<Item>()
            photos.groupBy { it.baseName.uppercase() }.values.forEach { same ->
                val raws = same.filter { it.isRaw }.toMutableList()
                same.filterNot { it.isRaw }.forEach { jpg ->
                    val match = raws.minByOrNull { abs(it.date - jpg.date) }
                        ?.takeIf { abs(it.date - jpg.date) <= PAIR_WINDOW_MS }
                    if (match != null) raws.remove(match)
                    items += Item(jpg, match)
                }
                raws.forEach { items += Item(null, it) }
            }
            return items.sortedWith(compareByDescending<Item> { it.date }.thenByDescending { it.name })
        }

        private const val PAIR_WINDOW_MS = 5_000L
    }
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
    private val previews = LruCache<Int, Bitmap>(3)

    var model: String = context.getString(R.string.camera_default)
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
            photos
        }

    /** Espacio libre y total de la tarjeta, en bytes. */
    suspend fun storage(): Pair<Long, Long>? = withContext(mtpDispatcher) {
        val mtp = device ?: return@withContext null
        var free = 0L
        var total = 0L
        mtp.storageIds?.forEach { id ->
            mtp.getStorageInfo(id)?.let {
                free += it.freeSpace
                total += it.maxCapacity
            }
        }
        if (total > 0) free to total else null
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
        previews.get(photo.handle)?.let { return it }
        if (photo.isRaw || photo.size <= 0 || photo.size > MAX_PREVIEW_BYTES) return thumbnail(photo)
        val bytes = withContext(mtpDispatcher) {
            device?.getObject(photo.handle, photo.size.toInt())
        } ?: return thumbnail(photo)
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_MAX_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return thumbnail(photo)
        previews.put(photo.handle, bitmap)
        return bitmap
    }

    /** Copia el archivo a DCIM/NikonD60 vía MediaStore. */
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

    /** Borra el archivo de la tarjeta de la cámara. */
    suspend fun deleteFromCard(photo: Photo): Boolean = withContext(mtpDispatcher) {
        val ok = runCatching { device?.deleteObject(photo.handle) ?: false }.getOrDefault(false)
        if (ok) {
            thumbs.remove(photo.handle)
            previews.remove(photo.handle)
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
        previews.evictAll()
    }

    companion object {
        const val FOLDER = "NikonD60"
        private val RAW_EXTENSIONS = setOf("nef", "nrw")
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg") + RAW_EXTENSIONS
        private const val MAX_PREVIEW_BYTES = 25L * 1024 * 1024
        private const val PREVIEW_MAX_SIDE = 2048
    }
}
