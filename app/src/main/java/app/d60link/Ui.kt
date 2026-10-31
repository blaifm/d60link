@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.min

// ---------- Fechas ----------

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val DATETIME_FMT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val STAMP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("''yy  M  d")
private val STAMP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val STAMP_COLOR = Color(0xFFFF8A1F)

private fun Long.toZoned(): ZonedDateTime? =
    if (this <= 0) null else Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

private fun Long.toDay(): LocalDate? = toZoned()?.toLocalDate()

@Composable
private fun dayLabel(day: LocalDate?): String {
    if (day == null) return stringResource(R.string.no_date)
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> day.format(DAY_FMT.withLocale(Locale.getDefault()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

private fun barDate(date: Long, showTime: Boolean): String? = date.toZoned()?.format(
    (if (showTime) DATETIME_FMT else DATE_FMT).withLocale(Locale.getDefault()),
)

/** Formato de los sellos de fecha de las cámaras de carrete: '26 10 31  13:50 */
private fun stampDate(date: Long, showTime: Boolean): String? = date.toZoned()?.let {
    if (showTime) "${it.format(STAMP_DATE)}   ${it.format(STAMP_TIME)}" else it.format(STAMP_DATE)
}

// ---------- Pantalla principal ----------

@Composable
fun App(c: Controller) {
    val s by c.state.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Pair<List<Item>, Int>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(s.message) {
        s.message?.let {
            snackbar.showSnackbar(it)
            c.consumeMessage()
        }
    }
    BackHandler(enabled = s.selecting) { c.clearSelection() }

    Scaffold(
        topBar = {
            if (s.selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, s.selected.size, s.selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = c::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_cancel_selection))
                        }
                    },
                    actions = {
                        TextButton(onClick = { c.selectAll(s.visibleItems) }) {
                            Text(stringResource(R.string.action_select_all))
                        }
                        IconButton(onClick = { confirmDelete = true }, enabled = !s.busy) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name))
                            if (s.status is Status.Ready) {
                                val free = s.storage?.let { (f, _) ->
                                    " · " + stringResource(R.string.free_space, Formatter.formatShortFileSize(context, f))
                                } ?: ""
                                Text(
                                    s.model + free,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (s.status is Status.Ready && !s.busy) {
                            IconButton(onClick = c::reload) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_reload))
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            val canDownload = s.status is Status.Ready && !s.busy
            when {
                canDownload && s.selecting -> ExtendedFloatingActionButton(
                    onClick = c::downloadSelected,
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    text = { Text(stringResource(R.string.fab_download_n, s.selected.size)) },
                )
                canDownload && s.newItems.isNotEmpty() -> ExtendedFloatingActionButton(
                    onClick = c::downloadNew,
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    text = { Text(stringResource(R.string.fab_download_new, s.newItems.size)) },
                )
            }
        },
        bottomBar = { s.progress?.let { ProgressBar(it) } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val st = s.status) {
                Status.NoCamera -> Message(
                    title = stringResource(R.string.no_camera_title),
                    body = stringResource(R.string.no_camera_body),
                    action = stringResource(R.string.btn_search),
                    onAction = c::scan,
                )
                Status.MassStorage -> Message(
                    title = stringResource(R.string.mass_title),
                    body = stringResource(R.string.mass_body),
                    action = stringResource(R.string.btn_search_again),
                    onAction = c::scan,
                )
                Status.NeedPermission -> Message(
                    title = stringResource(R.string.perm_title),
                    body = stringResource(R.string.perm_body),
                    action = stringResource(R.string.btn_request_permission),
                    onAction = c::scan,
                )
                Status.Connecting -> Loading(stringResource(R.string.st_connecting), null)
                is Status.Reading -> Loading(
                    if (st.total > 0) stringResource(R.string.st_reading_n, st.done, st.total)
                    else stringResource(R.string.st_reading),
                    if (st.total > 0) st.done.toFloat() / st.total else null,
                )
                is Status.Error -> Message(
                    title = stringResource(R.string.error_title),
                    body = st.message,
                    action = stringResource(R.string.btn_retry),
                    onAction = c::scan,
                )
                Status.Ready -> Gallery(
                    s = s,
                    c = c,
                    onOpen = { list, index -> viewing = list to index },
                )
            }
        }
    }

    viewing?.let { (list, index) ->
        Viewer(
            items = list,
            startIndex = index,
            s = s,
            c = c,
            onClose = { viewing = null },
        )
    }

    if (confirmDelete) {
        val selected = s.selectedItems
        val files = selected.sumOf { it.files.size }
        val notDownloaded = selected.count { s.isNew(it) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.del_title)) },
            text = {
                Column {
                    Text(pluralStringResource(R.plurals.del_body, files, files))
                    if (notDownloaded > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            pluralStringResource(R.plurals.del_warn_new, notDownloaded, notDownloaded),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; c.deleteSelected() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    s.offerDelete?.let { files ->
        AlertDialog(
            onDismissRequest = c::dismissOfferDelete,
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.offer_title)) },
            text = { Text(pluralStringResource(R.plurals.offer_body, files.size, files.size)) },
            confirmButton = {
                TextButton(
                    onClick = c::deleteOffered,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = c::dismissOfferDelete) { Text(stringResource(R.string.btn_not_now)) }
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = s.settings,
            onChange = c::updateSettings,
            onDismiss = { showSettings = false },
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
private fun ProgressBar(p: Progress) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            val label = if (p.deleting) stringResource(R.string.deleting, p.done, p.total)
            else stringResource(R.string.downloading, p.done, p.total)
            Text(
                label + if (p.failed > 0) stringResource(R.string.downloading_failed, p.failed) else "",
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

// ---------- Galería ----------

@Composable
private fun Gallery(s: UiState, c: Controller, onOpen: (List<Item>, Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !s.onlyNew,
                onClick = { c.setOnlyNew(false) },
                label = { Text(stringResource(R.string.chip_all, s.items.size)) },
            )
            FilterChip(
                selected = s.onlyNew,
                onClick = { c.setOnlyNew(true) },
                label = { Text(stringResource(R.string.chip_new, s.newItems.size)) },
            )
        }

        val items = s.visibleItems
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (s.onlyNew && s.items.isNotEmpty()) stringResource(R.string.empty_all_done)
                    else stringResource(R.string.empty_no_photos),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        val groups = remember(items) { items.groupBy { it.date.toDay() } }
        val indexOf = remember(items) { items.withIndex().associate { (i, it) -> it.id to i } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            groups.forEach { (day, dayItems) ->
                item(key = "h-$day", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dayLabel(day), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                pluralStringResource(R.plurals.photo_count, dayItems.size, dayItems.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { c.selectAll(dayItems) }) { Text(stringResource(R.string.btn_select_day)) }
                    }
                }
                items(dayItems, key = { it.id }) { item ->
                    Cell(
                        item = item,
                        selected = item.id in s.selected,
                        downloaded = !s.isNew(item),
                        load = { c.thumbnail(item.main) },
                        onClick = {
                            if (s.selecting) c.toggleSelect(item) else onOpen(items, indexOf[item.id] ?: 0)
                        },
                        onLongClick = { c.toggleSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(
    item: Item,
    selected: Boolean,
    downloaded: Boolean,
    load: suspend () -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, item.id) { value = load() }
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
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val badge = when {
            item.isPair -> stringResource(R.string.badge_pair)
            item.raw != null -> "RAW"
            else -> null
        }
        badge?.let { FormatBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
        if (downloaded && !selected) {
            CheckBadge(
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (selected) {
            CheckBadge(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun CheckBadge(modifier: Modifier, bg: Color, fg: Color) {
    Box(
        modifier.size(22.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
    }
}

// ---------- Visor ----------

@Composable
private fun Viewer(items: List<Item>, startIndex: Int, s: UiState, c: Controller, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    var zoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { items[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                ZoomablePhoto(
                    item = item,
                    stamp = if (s.settings.dateStyle == DateStyle.STAMP) stampDate(item.date, s.settings.showTime) else null,
                    load = { c.preview(item.jpg ?: item.raw!!) },
                    onZoomChange = { if (page == pager.currentPage) zoomed = it },
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = Color.White)
            }
            Text(
                "${pager.currentPage + 1} / ${items.size}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(20.dp),
            )

            val item = items[pager.currentPage]
            val downloaded = !s.isNew(item)
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Row(
                    Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                            val badge = when {
                                item.isPair -> stringResource(R.string.badge_pair)
                                item.raw != null -> "RAW"
                                else -> null
                            }
                            badge?.let {
                                Spacer(Modifier.size(8.dp))
                                FormatBadge(it)
                            }
                        }
                        val parts = buildList {
                            if (s.settings.dateStyle == DateStyle.BAR) {
                                add(barDate(item.date, s.settings.showTime) ?: stringResource(R.string.no_date))
                            }
                            add(Formatter.formatShortFileSize(context, item.totalSize))
                        }
                        Text(
                            parts.joinToString(" · ") + if (item.jpg == null) stringResource(R.string.raw_preview_note) else "",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(onClick = { c.downloadOne(item) }, enabled = !s.busy) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (downloaded) R.string.btn_download_again else R.string.btn_download))
                    }
                }
            }
        }
    }
}

/**
 * Foto a pantalla completa con zoom (dos dedos o doble toque). Sin zoom, los
 * deslizamientos de un dedo pasan al pager para cambiar de foto.
 */
@Composable
private fun ZoomablePhoto(
    item: Item,
    stamp: String?,
    load: suspend () -> Bitmap?,
    onZoomChange: (Boolean) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, item.id) { value = load() }
    var zoom by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(zoom > 1f) { onZoomChange(zoom > 1f) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || zoom > 1f) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                            val maxX = (zoom - 1f) * size.width / 2f
                            val maxY = (zoom - 1f) * size.height / 2f
                            val next = offset + event.calculatePan()
                            offset = if (zoom <= 1f) Offset.Zero
                            else Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(onDoubleTap = {
                    if (zoom > 1f) {
                        zoom = 1f
                        offset = Offset.Zero
                    } else {
                        zoom = 2.5f
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
            return@BoxWithConstraints
        }
        // Tamaño de la foto encajada en pantalla, para colocar el sello sobre la imagen
        val density = LocalDensity.current
        val fit = min(constraints.maxWidth / bmp.width.toFloat(), constraints.maxHeight / bmp.height.toFloat())
        val w = with(density) { (bmp.width * fit).toDp() }
        val h = with(density) { (bmp.height * fit).toDp() }

        Box(
            Modifier
                .size(w, h)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (stamp != null) {
                Text(
                    stamp,
                    color = STAMP_COLOR,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = (w.value / 26f).coerceIn(11f, 22f).sp,
                        letterSpacing = 1.sp,
                        shadow = Shadow(STAMP_COLOR.copy(alpha = 0.6f), blurRadius = 6f),
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = w * 0.05f, bottom = h * 0.06f),
                )
            }
        }
    }
}

// ---------- Ajustes ----------

@Composable
private fun SettingsSheet(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            SettingLabel(stringResource(R.string.set_pairs), stringResource(R.string.set_pairs_desc))
            Segmented(
                options = listOf(
                    PairMode.BOTH to stringResource(R.string.opt_both),
                    PairMode.JPG to stringResource(R.string.opt_jpg),
                    PairMode.RAW to stringResource(R.string.opt_raw),
                ),
                selected = settings.pairMode,
                onSelect = { onChange(settings.copy(pairMode = it)) },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            SettingLabel(stringResource(R.string.set_date), stringResource(R.string.set_date_desc))
            Segmented(
                options = listOf(
                    DateStyle.OFF to stringResource(R.string.opt_off),
                    DateStyle.BAR to stringResource(R.string.opt_bar),
                    DateStyle.STAMP to stringResource(R.string.opt_stamp),
                ),
                selected = settings.dateStyle,
                onSelect = { onChange(settings.copy(dateStyle = it)) },
            )
            SwitchRow(
                text = stringResource(R.string.set_time),
                checked = settings.showTime,
                enabled = settings.dateStyle != DateStyle.OFF,
                onChange = { onChange(settings.copy(showTime = it)) },
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            SwitchRow(
                text = stringResource(R.string.set_offer_delete),
                checked = settings.offerDelete,
                onChange = { onChange(settings.copy(offerDelete = it)) },
            )
            Text(
                stringResource(R.string.set_delete_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingLabel(title: String, desc: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
