package com.ripenai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.ripenai.BuildConfig
import com.ripenai.R
import com.ripenai.ui.RipenViewModel
import com.ripenai.ui.theme.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: RipenViewModel,
    onNavigateToSettings: () -> Unit,
    onSwitchMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val capturedBitmap by viewModel.capturedBitmap.collectAsState()
    val selectedFruit by viewModel.selectedFruit.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisError by viewModel.analysisError.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val showSimulation by viewModel.showSimulation.collectAsState()
    var selectedCommodity by rememberSaveable { mutableStateOf("banana") }
    var selectedStage by rememberSaveable { mutableStateOf("ripe") }

    val isEmulator = remember {
        android.os.Build.FINGERPRINT.startsWith("generic")
                || android.os.Build.FINGERPRINT.startsWith("unknown")
                || android.os.Build.MODEL.contains("google_sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86")
                || android.os.Build.MANUFACTURER.contains("Genymotion")
                || android.os.Build.PRODUCT.contains("sdk_gphone", ignoreCase = true)
                || android.os.Build.HARDWARE.contains("ranchu", ignoreCase = true)
                || (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic"))
                || "google_sdk" == android.os.Build.PRODUCT
    }

    // Permissions state
    var hasCameraPermission by remember {
        mutableStateOf(
            isEmulator || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var showCameraFallback by remember {
        mutableStateOf(
            isEmulator || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        )
    }
    var isCameraBound by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Gallery selector launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setCapturedUri(it) }
    }

    // CameraX helper variables
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraProviderFuture = remember(context, hasCameraPermission, showCameraFallback) {
        if (!isEmulator && hasCameraPermission && !showCameraFallback) {
            try {
                ProcessCameraProvider.getInstance(context)
            } catch (e: Exception) {
                Log.e("ScanScreen", "Failed to get ProcessCameraProvider", e)
                null
            }
        } else {
            null
        }
    }
    val cameraReady = selectedFruit != null && hasCameraPermission && !showCameraFallback && isCameraBound
    val isDisposed = remember { booleanArrayOf(false) }

    DisposableEffect(lifecycleOwner, cameraProviderFuture) {
        onDispose {
            isDisposed[0] = true
            isCameraBound = false
            if (cameraProviderFuture != null) {
                try {
                    cameraProviderFuture.addListener({
                        try {
                            cameraProviderFuture.get().unbindAll()
                        } catch (e: Exception) {
                            Log.e("ScanScreen", "Error unbinding in onDispose", e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (exc: Exception) {
                    Log.e("ScanScreen", "Error adding unbind listener on dispose", exc)
                }
            }
            try {
                cameraExecutor.shutdown()
            } catch (exc: Exception) {
                Log.e("ScanScreen", "Error shutting down camera executor", exc)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.White,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "RipenAI",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = AgriPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSwitchMode,
                        modifier = Modifier.testTag("mode_switch_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Agriculture,
                            contentDescription = "Ganti mode",
                            tint = AgriPrimary
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Pengaturan",
                            tint = Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FruitSelector(
                selectedFruit = selectedFruit,
                onFruitSelected = viewModel::setSelectedFruit
            )

            if (analysisError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("analysis_error_card"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                    border = BorderStroke(1.dp, Color(0xFFFED7AA)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = StatusOverripe)
                        Text(analysisError.orEmpty(), color = Color(0xFF7C2D12), fontSize = 13.sp)
                    }
                }
            }

            // Central Viewfinder Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Keep the fruit preview as the visual focus instead of
                    // letting the selector and bottom actions squeeze it.
                    .height(300.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFF9FAFB))
                    .drawBehind {
                        // Dashed borders around view finder
                        drawRoundRect(
                            color = Color(0xFFD1D5DB),
                            style = Stroke(
                                width = 2f,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(15f, 15f),
                                    0f
                                )
                            ),
                            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (capturedBitmap != null) {
                    // Show Preview of selected/captured image
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = capturedBitmap!!.asImageBitmap(),
                            contentDescription = "Pratinjau Foto",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Beautiful scan lines overlay if analyzing
                        if (isAnalyzing) {
                            val infiniteTransition = rememberInfiniteTransition(label = "scanning")
                            val yOffset by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "yOffset"
                            )

                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .offset(y = (maxHeight.value * yOffset).dp)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    AgriPrimary.copy(alpha = 0.1f),
                                                    AgriPrimary,
                                                    AgriPrimary.copy(alpha = 0.1f)
                                                )
                                            )
                                        )
                                )
                            }
                        }

                        // Grid reticle option if enabled
                        if (showGrid) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, Color.White.copy(alpha = 0.2f))
                            ) {
                                // Simple Reticle
                                Icon(
                                    imageVector = Icons.Default.CenterFocusWeak,
                                    contentDescription = null,
                                    tint = AgriPrimary.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .size(64.dp)
                                        .align(Alignment.Center)
                                )
                            }
                        }
                    }
                } else if (hasCameraPermission && !showCameraFallback && cameraProviderFuture != null) {
                    // Live camera view
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            cameraProviderFuture.addListener({
                                if (isDisposed[0]) return@addListener
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }

                                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                    if (cameraProvider.hasCamera(cameraSelector)) {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            cameraSelector,
                                            preview,
                                            imageCapture
                                        )
                                        isCameraBound = true
                                    } else {
                                        Log.w("ScanScreen", "No back camera available, using fallback")
                                        isCameraBound = false
                                        showCameraFallback = true
                                    }
                                } catch (exc: Exception) {
                                    Log.e("ScanScreen", "CameraX initialization failed, using fallback", exc)
                                    isCameraBound = false
                                    showCameraFallback = true
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Target indicator in viewfinder center
                    Icon(
                        imageVector = Icons.Default.CenterFocusWeak,
                        contentDescription = "Fokus Target",
                        tint = AgriPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.size(56.dp)
                    )
                } else if (hasCameraPermission && showCameraFallback) {
                    // The emulator has no usable camera feed. Keep this state
                    // explicit and calm so the gallery action remains the
                    // primary test path without presenting a fake preview.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFFF8FAFC), Color(0xFFF1F5F9))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(AgriPrimaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = AgriPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Text(
                                text = "Preview kamera belum tersedia",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF1E293B),
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = if (isEmulator) {
                                    "Pilih foto dari galeri untuk menguji model dengan alur yang sama."
                                } else {
                                    "Kamera perangkat tidak dapat digunakan. Pilih foto dari galeri untuk melanjutkan."
                                },
                                fontSize = 12.sp,
                                color = Color(0xFF64748B),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                } else {
                    // Permission Request Area
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "Izinkan Akses Kamera",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "Untuk mendeteksi kematangan buah secara langsung, aplikasi membutuhkan izin menggunakan kamera.",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = AgriPrimary),
                            modifier = Modifier.testTag("request_camera_btn")
                        ) {
                            Text("Berikan Izin")
                        }
                    }
                }

            }

            // Keep debug simulation controls outside the camera surface. The
            // preview should describe the image source, not also host a test
            // panel over it.
            if (BuildConfig.DEBUG && showSimulation && capturedBitmap == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Science, contentDescription = null, tint = AgriPrimary)
                            Column {
                                Text("Uji cepat", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E293B))
                                Text("Kontrol pengujian untuk build debug", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }

                        Text("Komoditas", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("banana" to "Pisang", "mango" to "Mangga", "tomato" to "Tomat").forEach { (key, label) ->
                                FilterChip(
                                    selected = selectedCommodity == key,
                                    onClick = { selectedCommodity = key },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.height(34.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AgriPrimaryContainer,
                                        selectedLabelColor = AgriPrimary
                                    )
                                )
                            }
                        }

                        Text("Kematangan", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(
                                listOf(
                                    "unripe" to "Mentah",
                                    "nearly_ripe" to "Hampir",
                                    "ripe" to "Matang",
                                    "overripe" to "Terlalu matang",
                                    "rotten" to "Busuk"
                                ),
                                key = { it.first }
                            ) { (key, label) ->
                                FilterChip(
                                    selected = selectedStage == key,
                                    onClick = { selectedStage = key },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.height(34.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = when (key) {
                                            "unripe" -> StatusMentah.copy(alpha = 0.18f)
                                            "nearly_ripe" -> StatusHampirMatang.copy(alpha = 0.18f)
                                            "overripe" -> StatusOverripe.copy(alpha = 0.18f)
                                            "rotten" -> StatusRotten.copy(alpha = 0.18f)
                                            else -> StatusMatang.copy(alpha = 0.18f)
                                        },
                                        selectedLabelColor = when (key) {
                                            "unripe" -> StatusMentah
                                            "nearly_ripe" -> StatusHampirMatang
                                            "overripe" -> StatusOverripe
                                            "rotten" -> StatusRotten
                                            else -> StatusMatang
                                        }
                                    )
                                )
                            }
                        }

                        Button(
                            onClick = { viewModel.loadDemoFruit(selectedCommodity, selectedStage) },
                            colors = ButtonDefaults.buttonColors(containerColor = AgriPrimary),
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Simulasikan gambar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Trigger Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (capturedBitmap == null) {
                    // Actions when no image selected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Capture Button
                        Button(
                            onClick = {
                                if (hasCameraPermission) {
                                    val photoFile = File(
                                        context.cacheDir,
                                        "ripenai_temp_capture.jpg"
                                    )
                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                                    imageCapture.takePicture(
                                        outputOptions,
                                        cameraExecutor,
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                                try {
                                                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                                                    val orientedBitmap = bitmap?.let { applyExifOrientation(it, photoFile) }
                                                    if (orientedBitmap != null) {
                                                        ContextCompat.getMainExecutor(context).execute {
                                                            viewModel.setCapturedBitmap(orientedBitmap)
                                                        }
                                                    }
                                                } finally {
                                                    photoFile.delete()
                                                }
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                Log.e("ScanScreen", "Photo capture failed: ${exception.message}")
                                            }
                                        }
                                    )
                                } else {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            enabled = cameraReady,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("capture_photo_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = AgriPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Camera, contentDescription = null)
                                Text("Ambil Foto", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }

                        // Gallery Selector
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            enabled = selectedFruit != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("gallery_select_btn"),
                            border = BorderStroke(1.dp, AgriPrimary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AgriPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                Text("Pilih Galeri", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                } else {
                    // Actions when image is selected/captured -> "Analisis Kematangan"
                    Button(
                        onClick = { viewModel.analyzeRipeness() },
                        enabled = !isAnalyzing && selectedFruit != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("analyze_ripenness_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            AgriPrimary,
                                            AgriPrimaryDark
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = Color.White)
                                    Text(
                                        text = "Analisis Kematangan",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Reset button to discard chosen image
                    TextButton(
                        onClick = { viewModel.setCapturedBitmap(null as Bitmap?) },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("discard_image_btn")
                    ) {
                        Text("Batal & Ambil Ulang", color = Color.Red, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

        }
    }
}

private fun applyExifOrientation(bitmap: Bitmap, photoFile: File): Bitmap {
    val orientation = runCatching {
        ExifInterface(photoFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)
}
