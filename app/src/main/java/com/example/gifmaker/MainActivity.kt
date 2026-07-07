package com.example.gifmaker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.gifmaker.ui.theme.GifMakerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

private enum class ThemePreference { SYSTEM, LIGHT, DARK }
private enum class Screen { MAIN, STUDIO }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themePref by remember { mutableStateOf(ThemePreference.DARK) }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val darkTheme = when (themePref) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            GifMakerTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    GifMakerApp(themePref = themePref, onThemePrefChange = { themePref = it })
                }
            }
        }
    }
}

private fun formatSeconds(ms: Long): String {
    val totalSeconds = ms / 1000.0
    return String.format(Locale.US, "%.1fs", totalSeconds)
}

/** Holds settings that Studio's "Edit ulang" can hand back to the main screen. */
private data class PrefillSettings(
    val uri: Uri,
    val fps: Int,
    val targetWidth: Int,
    val trimStartMs: Long,
    val trimEndMs: Long
)

@Composable
private fun GifMakerApp(
    themePref: ThemePreference,
    onThemePrefChange: (ThemePreference) -> Unit
) {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var prefill by remember { mutableStateOf<PrefillSettings?>(null) }
    var studioMessage by remember { mutableStateOf<String?>(null) }

    when (screen) {
        Screen.MAIN -> GifMakerScreen(
            themePref = themePref,
            onThemePrefChange = onThemePrefChange,
            onOpenStudio = { screen = Screen.STUDIO },
            prefill = prefill,
            onPrefillConsumed = { prefill = null },
            studioMessage = studioMessage,
            onStudioMessageShown = { studioMessage = null }
        )
        Screen.STUDIO -> StudioScreen(
            onBack = { screen = Screen.MAIN },
            onEditAgain = { entry, durationMs ->
                prefill = PrefillSettings(
                    uri = Uri.parse(entry.sourceUri),
                    fps = entry.fps,
                    targetWidth = entry.targetWidth,
                    trimStartMs = entry.trimStartMs,
                    trimEndMs = entry.trimEndMs
                )
                screen = Screen.MAIN
            },
            onEditFailed = {
                studioMessage = "Video sumber tidak lagi bisa diakses (mungkin sudah dihapus/dipindah)."
                screen = Screen.MAIN
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GifMakerScreen(
    themePref: ThemePreference,
    onThemePrefChange: (ThemePreference) -> Unit,
    onOpenStudio: () -> Unit,
    prefill: PrefillSettings?,
    onPrefillConsumed: () -> Unit,
    studioMessage: String?,
    onStudioMessageShown: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    var sourceWidth by remember { mutableStateOf(0) }
    var sourceHeight by remember { mutableStateOf(0) }
    var trimRange by remember { mutableStateOf(0f..1f) }
    var fps by remember { mutableStateOf(10f) }
    var targetWidth by remember { mutableStateOf(320f) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var estimatedSizeBytes by remember { mutableStateOf<Long?>(null) }
    var isEstimatingSize by remember { mutableStateOf(false) }

    fun loadVideoMetadata(uri: Uri, onLoaded: (Long, Int, Int) -> Unit, onFailed: () -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (d <= 0) { onFailed(); return }
            onLoaded(d, w, h)
        } catch (_: Exception) {
            onFailed()
        } finally {
            retriever.release()
        }
    }

    // Apply settings coming back from Studio's "Edit ulang", once.
    LaunchedEffect(prefill) {
        val p = prefill ?: return@LaunchedEffect
        loadVideoMetadata(
            p.uri,
            onLoaded = { d, w, h ->
                selectedUri = p.uri
                durationMs = d
                sourceWidth = w
                sourceHeight = h
                trimRange = (p.trimStartMs.toFloat() / d).coerceIn(0f, 1f)..(p.trimEndMs.toFloat() / d).coerceIn(0f, 1f)
                fps = p.fps.toFloat()
                targetWidth = p.targetWidth.toFloat()
                resultMessage = null
                outputFile = null
            },
            onFailed = { resultMessage = "Video sumber tidak lagi bisa diakses (mungkin sudah dihapus/dipindah)." }
        )
        onPrefillConsumed()
    }

    LaunchedEffect(studioMessage) {
        if (studioMessage != null) {
            resultMessage = studioMessage
            onStudioMessageShown()
        }
    }

    var pendingRequest by remember { mutableStateOf<GifRequest?>(null) }
    val storagePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingRequest
        pendingRequest = null
        if (granted && request != null) {
            runConversion(context, scope, request,
                onProgress = { cur, total -> progressText = "Memproses frame $cur/$total" },
                onDone = { message, file ->
                    isProcessing = false
                    progressText = null
                    resultMessage = message
                    outputFile = file
                }
            )
        } else if (request != null) {
            isProcessing = false
            resultMessage = "Izin penyimpanan diperlukan untuk menyimpan GIF ke galeri."
        }
    }

    val pickVideoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            resultMessage = null
            outputFile = null
            loadVideoMetadata(
                uri,
                onLoaded = { d, w, h ->
                    selectedUri = uri
                    durationMs = d
                    sourceWidth = w
                    sourceHeight = h
                    trimRange = 0f..1f
                },
                onFailed = { durationMs = 0L }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GIF Maker", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenStudio) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Studio")
                    }
                    Box {
                        IconButton(onClick = { showThemeMenu = true }) {
                            Icon(
                                imageVector = if (themePref == ThemePreference.LIGHT) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                contentDescription = "Theme"
                            )
                        }
                        DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                            DropdownMenuItem(text = { Text("Dark") }, onClick = { onThemePrefChange(ThemePreference.DARK); showThemeMenu = false })
                            DropdownMenuItem(text = { Text("Light") }, onClick = { onThemePrefChange(ThemePreference.LIGHT); showThemeMenu = false })
                            DropdownMenuItem(text = { Text("Follow system") }, onClick = { onThemePrefChange(ThemePreference.SYSTEM); showThemeMenu = false })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Card(
                onClick = { pickVideoLauncher.launch("video/*") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.ImageIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    if (selectedUri == null) {
                        Text("Tap untuk pilih video", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text("MP4, MOV, dan format umum lainnya", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Video dipilih", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Durasi: ${formatSeconds(durationMs)}" + if (sourceWidth > 0) " • ${sourceWidth}×${sourceHeight}" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { pickVideoLauncher.launch("video/*") }) { Text("Pilih video lain") }
                    }
                }
            }

            if (selectedUri != null && durationMs > 0) {
                val startMs = (trimRange.start * durationMs).toLong()
                val endMs = (trimRange.endInclusive * durationMs).toLong()
                val fpsInt = fps.toInt().coerceIn(5, 24)
                val widthInt = targetWidth.toInt().coerceIn(120, 640)

                LaunchedEffect(selectedUri, startMs, endMs, fpsInt, widthInt) {
                    isEstimatingSize = true
                    delay(350) // debounce: avoid re-encoding samples on every slider tick
                    val uri = selectedUri
                    if (uri != null && endMs > startMs) {
                        val estimateRequest = GifRequest(
                            sourceUri = uri,
                            outputFile = File(context.cacheDir, "estimate_probe.gif"),
                            startMs = startMs,
                            endMs = endMs,
                            fps = fpsInt,
                            targetWidth = widthInt,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight
                        )
                        estimatedSizeBytes = withContext(Dispatchers.IO) {
                            VideoToGifConverter.estimateSizeBytes(context, estimateRequest)
                        }
                    }
                    isEstimatingSize = false
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Potong bagian video", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "${formatSeconds(startMs)} — ${formatSeconds(endMs)} (durasi hasil: ${formatSeconds(endMs - startMs)})",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    RangeSlider(
                        value = trimRange,
                        onValueChange = { trimRange = it },
                        valueRange = 0f..1f
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Kecepatan: $fpsInt fps", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Slider(
                        value = fps,
                        onValueChange = { fps = it },
                        valueRange = 5f..24f,
                        steps = 18 // snaps to whole fps values between 5 and 24
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Lebar output: ${widthInt}px", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Slider(
                        value = targetWidth,
                        onValueChange = { targetWidth = it },
                        valueRange = 120f..640f
                    )
                }

                val trimDurationMs = (endMs - startMs).coerceAtLeast(1)
                val estimatedFrames = ((trimDurationMs / 1000.0) * fpsInt).toInt().coerceAtLeast(1)
                val willBeCapped = estimatedFrames > VideoToGifConverter.MAX_FRAMES
                val effectiveFpsPreview = if (willBeCapped) {
                    (VideoToGifConverter.MAX_FRAMES * 1000.0 / trimDurationMs).toInt().coerceAtLeast(1)
                } else fpsInt

                Text(
                    if (willBeCapped) {
                        "Estimasi: ${VideoToGifConverter.MAX_FRAMES} frame — FPS otomatis diturunkan ke ~$effectiveFpsPreview fps untuk durasi ini."
                    } else {
                        "Estimasi: $estimatedFrames frame @ ${widthInt}px lebar."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )

                val sizeText = when {
                    isEstimatingSize -> "Menghitung estimasi ukuran…"
                    estimatedSizeBytes != null -> {
                        val mb = estimatedSizeBytes!! / (1024.0 * 1024.0)
                        val base = "Estimasi ukuran: ~${String.format(Locale.US, "%.1f", mb)} MB"
                        if (mb > 14.0) "$base — melebihi 14 MB" else base
                    }
                    else -> null
                }
                sizeText?.let {
                    Text(
                        it,
                        color = if (!isEstimatingSize && (estimatedSizeBytes ?: 0L) / (1024.0 * 1024.0) > 14.0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = {
                        val uri = selectedUri ?: return@Button
                        isProcessing = true
                        resultMessage = null
                        progressText = "Memulai…"

                        val thumbDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
                        val id = UUID.randomUUID().toString()
                        val request = GifRequest(
                            sourceUri = uri,
                            outputFile = File(context.cacheDir, "gif_$id.gif"),
                            startMs = startMs,
                            endMs = endMs,
                            fps = fpsInt,
                            targetWidth = widthInt,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            thumbnailFile = File(thumbDir, "thumb_$id.jpg")
                        )

                        val needsLegacyPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                        val alreadyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (needsLegacyPermission && !alreadyGranted) {
                            pendingRequest = request
                            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            runConversion(context, scope, request,
                                onProgress = { cur, total -> progressText = "Memproses frame $cur/$total" },
                                onDone = { message, file ->
                                    isProcessing = false
                                    progressText = null
                                    resultMessage = message
                                    outputFile = file
                                }
                            )
                        }
                    },
                    enabled = !isProcessing && endMs > startMs,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(progressText ?: "Memproses…")
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buat GIF")
                    }
                }

                resultMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(msg, color = MaterialTheme.colorScheme.onSurface)
                            outputFile?.let { file ->
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { shareGif(context, file) }) {
                                    Text("Share / Buka")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreen(
    onBack: () -> Unit,
    onEditAgain: (GifHistoryEntry, Long) -> Unit,
    onEditFailed: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by remember { mutableStateOf(GifHistoryStore.getAll(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Studio", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Belum ada GIF yang dibuat. Hasil konversi akan muncul di sini.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    StudioEntryCard(
                        entry = entry,
                        onEditAgain = {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, Uri.parse(entry.sourceUri))
                                val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                if (d > 0) onEditAgain(entry, d) else onEditFailed()
                            } catch (_: Exception) {
                                onEditFailed()
                            } finally {
                                retriever.release()
                            }
                        },
                        onShare = { shareGif(context, File(entry.outputFilePath)) },
                        onDelete = {
                            GifHistoryStore.deleteWithFiles(context, entry)
                            entries = GifHistoryStore.getAll(context)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioEntryCard(
    entry: GifHistoryEntry,
    onEditAgain: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var thumb by remember(entry.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(entry.id) {
        thumb = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(entry.thumbnailPath) }.getOrNull()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.ImageIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${entry.fps} fps • ${entry.targetWidth}px • ${entry.frameCount} frame",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Durasi potongan: ${formatSeconds(entry.trimEndMs - entry.trimStartMs)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEditAgain, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Edit ulang") }
                    TextButton(onClick = onShare, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Share") }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun runConversion(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    request: GifRequest,
    onProgress: (Int, Int) -> Unit,
    onDone: (message: String, file: File?) -> Unit
) {
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            VideoToGifConverter.convert(context, request) { cur, total ->
                onProgress(cur, total)
            }
        }
        when (result) {
            is GifResult.Success -> {
                val displayName = result.outputFile.name
                val publicUri = withContext(Dispatchers.IO) {
                    PublicGifExporter.publish(context, result.outputFile, displayName)
                }
                val savedLine = if (publicUri != null) {
                    "Selesai (${result.frameCount} frame). Tersimpan di Galeri > Pictures > GifMaker."
                } else {
                    "Selesai, tetapi gagal menyalin ke galeri publik. GIF tetap tersedia lewat tombol Share."
                }

                val sizeMb = result.sizeBytes / (1024.0 * 1024.0)
                val sizeLine = "Ukuran file: ${String.format(Locale.US, "%.1f", sizeMb)} MB."
                val sizeWarning = if (sizeMb > 14.0) {
                    "\n⚠️ Melebihi 14 MB — beberapa aplikasi chat/media membatasi ukuran GIF. Turunkan FPS, lebar, atau durasi potongan kalau perlu file lebih kecil."
                } else {
                    ""
                }
                val message = "$savedLine\n$sizeLine$sizeWarning"

                if (request.thumbnailFile != null) {
                    GifHistoryStore.add(
                        context,
                        GifHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            createdAt = System.currentTimeMillis(),
                            outputFilePath = result.outputFile.absolutePath,
                            thumbnailPath = request.thumbnailFile.absolutePath,
                            sourceUri = request.sourceUri.toString(),
                            fps = result.finalFps,
                            targetWidth = result.finalWidth,
                            trimStartMs = request.startMs,
                            trimEndMs = request.endMs,
                            frameCount = result.frameCount
                        )
                    )
                }

                onDone(message, result.outputFile)
            }
            is GifResult.Failure -> {
                onDone("Gagal membuat GIF: ${result.message}", null)
            }
        }
    }
}

private fun shareGif(context: android.content.Context, file: File) {
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File GIF tidak ditemukan (mungkin cache sudah dibersihkan).", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/gif"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share GIF"))
}
