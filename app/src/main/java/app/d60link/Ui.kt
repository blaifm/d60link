package app.d60link

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES = Locale.forLanguageTag("es-ES")
private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", ES)
private val FULL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", ES)

private fun Long.toDay(): LocalDate? =
    if (this <= 0) null else Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dayLabel(day: LocalDate?): String {
    if (day == null) return "Sin fecha"
    val today = LocalDate.now()
    return when (day) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> day.format(DAY_FMT).replaceFirstChar { it.titlecase(ES) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel, onScan: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Photo?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(s.message) {
        s.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    BackHandler(enabled = s.selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (s.selecting) {
                TopAppBar(
                    title = { Text("${s.selected.size} seleccionadas") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar selección")
                        }
                    },
                    actions = {
                        TextButton(onClick = { vm.selectAll(s.visiblePhotos) }) { Text("Todas") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("D60 Link")
                            if (s.status is Status.Ready) {
                                Text(
                                    s.model,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (s.status is Status.Ready && s.download == null) {
                            IconButton(onClick = vm::reload) {
                                Icon(Icons.Default.Refresh, contentDescription = "Volver a leer la tarjeta")
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            val canDownload = s.status is Status.Ready && s.download == null
            when {
                canDownload && s.selecting -> ExtendedFloatingActionButton(
                    onClick = vm::downloadSelected,
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    text = { Text("Bajar ${s.selected.size}") },
                )
                canDownload && s.newPhotos.isNotEmpty() -> ExtendedFloatingActionButton(
                    onClick = vm::downloadNew,
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    text = { Text("Bajar nuevas (${s.newPhotos.size})") },
                )
            }
        },
        bottomBar = { s.download?.let { DownloadBar(it) } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val st = s.status) {
                Status.NoCamera -> Message(
                    title = "Conecta la cámara",
                    body = "Enciende la D60 y conéctala con el cable OTG.\n\nEn la cámara: Menú configuración › USB › MTP/PTP.",
                    action = "Buscar cámara",
                    onAction = onScan,
                )
                Status.MassStorage -> Message(
                    title = "La cámara está en modo almacenamiento",
                    body = "Cambia en la cámara: Menú configuración › USB › MTP/PTP.\nLuego desconecta y vuelve a conectar el cable.",
                    action = "Buscar otra vez",
                    onAction = onScan,
                )
                Status.NeedPermission -> Message(
                    title = "Falta el permiso USB",
                    body = "Android tiene que dejar a la app hablar con la cámara.",
                    action = "Pedir permiso",
                    onAction = onScan,
                )
                Status.Connecting -> Loading("Conectando con la cámara…", null)
                is Status.Reading -> Loading(
                    if (st.total > 0) "Leyendo la tarjeta · ${st.done} de ${st.total}" else "Leyendo la tarjeta…",
                    if (st.total > 0) st.done.toFloat() / st.total else null,
                )
                is Status.Error -> Message(
                    title = "Algo ha fallado",
                    body = st.message,
                    action = "Reintentar",
                    onAction = onScan,
                )
                Status.Ready -> Gallery(
                    s = s,
                    vm = vm,
                    onOpen = { viewing = it },
                )
            }
        }
    }

    viewing?.let { photo ->
        Viewer(
            photo = photo,
            downloaded = photo.key in s.downloaded,
            busy = s.download != null,
            load = { vm.preview(photo) },
            onDownload = { vm.downloadOne(photo) },
            onClose = { viewing = null },
        )
    }
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_camera),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Loading(text: String, progress: Float?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth())
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadBar(p: DownloadProgress) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                "Descargando ${p.done} de ${p.total}" + if (p.failed > 0) " · ${p.failed} con error" else "",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Gallery(s: UiState, vm: MainViewModel, onOpen: (Photo) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !s.onlyNew,
                onClick = { vm.setOnlyNew(false) },
                label = { Text("Todas · ${s.photos.size}") },
            )
            FilterChip(
                selected = s.onlyNew,
                onClick = { vm.setOnlyNew(true) },
                label = { Text("Nuevas · ${s.newPhotos.size}") },
            )
        }

        val photos = s.visiblePhotos
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (s.onlyNew && s.photos.isNotEmpty()) "Ya tienes todas las fotos de la tarjeta 🎉"
                    else "No hay fotos en la tarjeta",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        val groups = remember(photos) { photos.groupBy { it.date.toDay() } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            groups.forEach { (day, dayPhotos) ->
                item(key = "h-$day", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dayLabel(day), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${dayPhotos.size} ${if (dayPhotos.size == 1) "foto" else "fotos"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.selectAll(dayPhotos) }) { Text("Seleccionar") }
                    }
                }
                items(dayPhotos, key = { it.handle }) { photo ->
                    PhotoCell(
                        photo = photo,
                        selected = photo.handle in s.selected,
                        downloaded = photo.key in s.downloaded,
                        load = { vm.thumbnail(photo) },
                        onClick = { if (s.selecting) vm.toggleSelect(photo) else onOpen(photo) },
                        onLongClick = { vm.toggleSelect(photo) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: Photo,
    selected: Boolean,
    downloaded: Boolean,
    load: suspend () -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, photo.handle) { value = load() }
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "sel")

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (selected) 12.dp else 0.dp))) {
            AnimatedVisibility(visible = bitmap != null, enter = fadeIn(), exit = fadeOut()) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = photo.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (photo.isRaw) {
            Text(
                "RAW",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (downloaded && !selected) {
            Badge(
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (selected) {
            Badge(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun Badge(modifier: Modifier, bg: Color, fg: Color) {
    Box(
        modifier.size(22.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun Viewer(
    photo: Photo,
    downloaded: Boolean,
    busy: Boolean,
    load: suspend () -> Bitmap?,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.handle) { value = load() }
    var zoom by remember(photo.handle) { mutableFloatStateOf(1f) }
    var offset by remember(photo.handle) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(photo.handle) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                offset = if (zoom == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(photo.handle) {
                            detectTapGestures(onDoubleTap = {
                                if (zoom > 1f) { zoom = 1f; offset = Offset.Zero } else zoom = 2.5f
                            })
                        }
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }

            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Row(
                    Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(photo.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        val date = if (photo.date > 0) {
                            Instant.ofEpochMilli(photo.date).atZone(ZoneId.systemDefault()).format(FULL_FMT)
                        } else "Sin fecha"
                        Text(
                            "$date · ${Formatter.formatShortFileSize(context, photo.size)}" +
                                if (photo.isRaw) " · vista previa reducida" else "",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = onDownload, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (downloaded) "Bajar otra vez" else "Bajar")
                    }
                }
            }
        }
    }
}
