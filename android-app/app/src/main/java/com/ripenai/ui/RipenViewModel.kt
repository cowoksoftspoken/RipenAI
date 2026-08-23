package com.ripenai.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ripenai.data.local.ScanHistory
import com.ripenai.data.remote.SensorData
import com.ripenai.data.repository.RipenRepository
import com.ripenai.domain.ClassificationResult
import com.ripenai.domain.ConsumerConfigRepository
import com.ripenai.domain.DynamicQuestion
import com.ripenai.domain.FusionEngine
import com.ripenai.domain.QuestionGenerator
import com.ripenai.domain.QuestionResponse
import com.ripenai.domain.TFLiteClassifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RipenViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RipenRepository(application)
    private val classifier = TFLiteClassifier(application)
    private val config = ConsumerConfigRepository(application).load()
    private val questionGenerator = QuestionGenerator(config)
    private val fusionEngine = FusionEngine(config)

    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap.asStateFlow()

    private val _selectedFruit = MutableStateFlow<String?>(null)
    val selectedFruit: StateFlow<String?> = _selectedFruit.asStateFlow()

    private val _scanResult = MutableStateFlow<ClassificationResult?>(null)
    val scanResult: StateFlow<ClassificationResult?> = _scanResult.asStateFlow()

    private val _questionResponse = MutableStateFlow<QuestionResponse?>(null)
    val questionResponse: StateFlow<QuestionResponse?> = _questionResponse.asStateFlow()

    private val _questionAnswers = MutableStateFlow<Map<String, String>>(emptyMap())
    val questionAnswers: StateFlow<Map<String, String>> = _questionAnswers.asStateFlow()

    private val _isLoadingQuestions = MutableStateFlow(false)
    val isLoadingQuestions: StateFlow<Boolean> = _isLoadingQuestions.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _savedImagePath = MutableStateFlow<String?>(null)
    val savedImagePath: StateFlow<String?> = _savedImagePath.asStateFlow()

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private val _espConnected = MutableStateFlow(false)
    val espConnected: StateFlow<Boolean> = _espConnected.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _espIp = MutableStateFlow(repository.getEsp32Ip())
    val espIp: StateFlow<String> = _espIp.asStateFlow()

    private val _testConnectionResult = MutableStateFlow<String?>(null)
    val testConnectionResult: StateFlow<String?> = _testConnectionResult.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _showGrid = MutableStateFlow(repository.getShowGrid())
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    private val _saveImage = MutableStateFlow(repository.getSaveImage())
    val saveImage: StateFlow<Boolean> = _saveImage.asStateFlow()

    private val _soundNotification = MutableStateFlow(repository.getSoundNotification())
    val soundNotification: StateFlow<Boolean> = _soundNotification.asStateFlow()

    private val _showSimulation = MutableStateFlow(repository.getShowSimulation())
    val showSimulation: StateFlow<Boolean> = _showSimulation.asStateFlow()

    val historyList: StateFlow<List<ScanHistory>> = repository.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private var pollingJob: Job? = null

    fun setSelectedFruit(fruit: String?) {
        _selectedFruit.value = fruit?.lowercase()
        _analysisError.value = null
        if (_scanResult.value != null) resetScan()
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    _sensorData.value = repository.getSensorData()
                    _espConnected.value = true
                } catch (error: Exception) {
                    _espConnected.value = false
                    Log.d(TAG, "Gateway unavailable: ${error.message}")
                }
                delay(5000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun testConnection() {
        _isTestingConnection.value = true
        _testConnectionResult.value = null
        viewModelScope.launch {
            try {
                _sensorData.value = repository.getSensorData()
                _espConnected.value = true
                _testConnectionResult.value = "Terhubung"
            } catch (_: Exception) {
                _espConnected.value = false
                _testConnectionResult.value = "Terputus"
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun loadDemoFruit(commodity: String, status: String) {
        setSelectedFruit(commodity)
        val stage = when (status.lowercase()) {
            "mentah", "unripe" -> "unripe"
            "hampir matang", "hampir_matang", "nearly ripe", "nearly_ripe" -> "nearly_ripe"
            "terlalu matang", "terlalu_matang", "overripe" -> "overripe"
            "busuk", "rotten" -> "rotten"
            else -> "ripe"
        }
        try {
            val image = getApplication<Application>().assets.open("samples/${commodity.lowercase()}_$stage.jpg").use { BitmapFactory.decodeStream(it) }
            if (image != null) setCapturedBitmap(image)
        } catch (error: Exception) {
            Log.e(TAG, "Sample image could not be loaded", error)
        }
    }

    fun setCapturedBitmap(bitmap: Bitmap?) {
        _capturedBitmap.value = bitmap
        _scanResult.value = null
        _questionResponse.value = null
        _questionAnswers.value = emptyMap()
        _analysisError.value = null
        _savedImagePath.value = null
    }

    fun setCapturedUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val image = getApplication<Application>().contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (image != null) setCapturedBitmap(image)
                else _analysisError.value = "Foto tidak dapat dibaca. Pilih gambar lain."
            } catch (error: Exception) {
                Log.e(TAG, "Gallery image could not be loaded", error)
                _analysisError.value = "Foto tidak dapat dibuka. Coba pilih gambar lain."
            }
        }
    }

    fun analyzeRipeness() {
        val bitmap = _capturedBitmap.value
        val fruit = _selectedFruit.value
        if (bitmap == null) {
            _analysisError.value = "Ambil atau pilih foto buah terlebih dahulu."
            return
        }
        if (fruit.isNullOrBlank()) {
            _analysisError.value = "Pilih jenis buah terlebih dahulu agar model yang tepat digunakan."
            return
        }
        if (config.requireOnlineQuestions && !hasValidatedInternet()) {
            _analysisError.value = "Mode konsumen membutuhkan internet untuk memastikan hasil. Hubungkan perangkat lalu coba lagi."
            return
        }

        _isAnalyzing.value = true
        _analysisError.value = null
        viewModelScope.launch {
            try {
                val preliminary = classifier.classify(
                    bitmap = bitmap,
                    selectedCommodity = fruit,
                    ambiguityThreshold = config.ambiguityThreshold,
                    confidenceGapThreshold = config.confidenceGapThreshold,
                    retakeConfidenceThreshold = config.retakeConfidenceThreshold
                )
                if (preliminary.isAnalysisUnavailable) {
                    _analysisError.value = preliminary.disclaimer ?: preliminary.recommendation
                    _scanResult.value = null
                } else if (preliminary.requiresRetake) {
                    _analysisError.value = "Foto belum cukup jelas untuk dipercaya. Ambil ulang dengan cahaya merata dan satu buah memenuhi bingkai."
                    _scanResult.value = null
                } else {
                    _scanResult.value = preliminary
                    _isLoadingQuestions.value = true
                    val generated = questionGenerator.generate(
                        fruitType = fruit,
                        cvResult = preliminary,
                        online = hasValidatedInternet()
                    )
                    val response = generated.response
                    if (response == null) {
                        _scanResult.value = null
                        _questionResponse.value = null
                        _analysisError.value = generated.error ?: "Pertanyaan konfirmasi belum tersedia. Coba lagi."
                    } else {
                        _questionAnswers.value = emptyMap()
                        _questionResponse.value = response
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Analysis failed", error)
                _analysisError.value = "Analisis gagal. Coba foto ulang dengan pencahayaan yang lebih baik."
            } finally {
                _isAnalyzing.value = false
                _isLoadingQuestions.value = false
            }
        }
    }

    fun selectQuestionAnswer(question: DynamicQuestion, option: String) {
        _questionAnswers.value = _questionAnswers.value.toMutableMap().apply { put(question.id, option) }
    }

    fun submitQuestions() {
        val preliminary = _scanResult.value ?: return
        val response = _questionResponse.value ?: return
        if (response.questions.any { _questionAnswers.value[it.id].isNullOrBlank() }) return
        finishAnalysis(fusionEngine.fuse(preliminary, _questionAnswers.value, response.source, response.questions))
    }

    private fun finishAnalysis(result: ClassificationResult) {
        _scanResult.value = result
        _questionResponse.value = null
        _questionAnswers.value = emptyMap()
        viewModelScope.launch {
            var imagePath: String? = null
            if (repository.getSaveImage()) {
                imagePath = saveBitmapToLocal(_capturedBitmap.value)
                _savedImagePath.value = imagePath
            }
            repository.saveScanHistory(
                ScanHistory(
                    commodity = result.commodity,
                    ripeness = result.ripeness,
                    // Riwayat menyimpan konsistensi observasi hasil fusi, bukan confidence
                    // mentah dari model visual yang tidak setara dengan tingkat keyakinan akhir.
                    confidence = result.displayConfidence,
                    daysEstimate = result.daysEstimate,
                    recommendation = result.recommendation,
                    imagePath = imagePath
                )
            )
            if (repository.getSoundNotification()) {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                delay(240)
                tone.release()
            }
        }
    }

    private fun saveBitmapToLocal(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(getApplication<Application>().filesDir, "RIPEN_$timestamp.jpg")
            FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
            file.absolutePath
        } catch (error: Exception) {
            Log.e(TAG, "Could not save scan image", error)
            null
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val connectivity = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    }

    fun resetScan() {
        setCapturedBitmap(null)
    }

    fun updateEspIp(ip: String) {
        repository.saveEsp32Ip(ip)
        _espIp.value = ip
        startPolling()
    }

    fun toggleShowGrid(value: Boolean) { repository.setShowGrid(value); _showGrid.value = value }
    fun toggleSaveImage(value: Boolean) { repository.setSaveImage(value); _saveImage.value = value }
    fun toggleSoundNotification(value: Boolean) { repository.setSoundNotification(value); _soundNotification.value = value }
    fun toggleShowSimulation(value: Boolean) { repository.setShowSimulation(value); _showSimulation.value = value }

    fun deleteHistoryItem(id: Int) { viewModelScope.launch { repository.deleteHistory(id) } }
    fun clearAllHistory() { viewModelScope.launch { repository.clearAllHistory() } }

    fun setHistoricalResult(result: ClassificationResult, imagePath: String?) {
        _questionResponse.value = null
        _questionAnswers.value = emptyMap()
        _scanResult.value = result
        _capturedBitmap.value = imagePath?.let { path -> if (File(path).exists()) BitmapFactory.decodeFile(path) else null }
    }

    override fun onCleared() {
        stopPolling()
        classifier.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RipenViewModel"
    }
}
