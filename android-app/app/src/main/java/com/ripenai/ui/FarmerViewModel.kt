package com.ripenai.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ripenai.data.local.FarmerContainerEntity
import com.ripenai.data.local.FarmerSensorReadingEntity
import com.ripenai.data.repository.FarmerRepository
import com.ripenai.data.repository.FarmerSyncResult
import com.ripenai.domain.FarmerRiskEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class FarmerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FarmerRepository(application)
    private val riskEngine = FarmerRiskEngine.fromAssets(application)

    val containers: StateFlow<List<FarmerContainerEntity>> = repository.containers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _selectedContainerId = MutableStateFlow<Long?>(null)
    val selectedContainerId: StateFlow<Long?> = _selectedContainerId.asStateFlow()

    val selectedContainer: StateFlow<FarmerContainerEntity?> = kotlinx.coroutines.flow.combine(
        containers,
        _selectedContainerId
    ) { items, selectedId -> items.firstOrNull { it.id == selectedId } }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    val selectedReadings: StateFlow<List<FarmerSensorReadingEntity>> = _selectedContainerId
        .flatMapLatest { id: Long? ->
            if (id == null) flowOf(emptyList()) else repository.observeReadings(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollingJob: Job? = null
    private val notificationKeys = mutableSetOf<String>()
    private val lastRiskStatuses = mutableMapOf<Long, String>()
    private val lastNotificationMillis = mutableMapOf<Long, Long>()
    private val staleNotificationKeys = mutableSetOf<Long>()

    fun selectContainer(containerId: Long) {
        _selectedContainerId.value = containerId
        _syncMessage.value = null
        _error.value = null
    }

    fun backToDashboard() {
        _selectedContainerId.value = null
        _syncMessage.value = null
        _error.value = null
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            syncAll()
            while (true) {
                delay(30_000)
                syncAll()
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun syncAll() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            val snapshot = containers.value
            if (snapshot.isEmpty()) return@launch
            _isSyncing.value = true
            _error.value = null
            var successCount = 0
            snapshot.forEach { container ->
                val result = repository.syncContainer(container.id, riskEngine)
                if (result.success) successCount++ else _error.value = result.message
                handleResult(container, result)
            }
            _syncMessage.value = "$successCount/${snapshot.size} wadah selesai diperiksa."
            _isSyncing.value = false
        }
    }

    fun syncSelected() {
        val id = _selectedContainerId.value ?: return
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            val container = repository.getContainer(id)
            if (container == null) {
                _error.value = "Wadah tidak ditemukan."
            } else {
                val result = repository.syncContainer(id, riskEngine)
                handleResult(container, result)
                if (result.success) _syncMessage.value = result.message else _error.value = result.message
            }
            _isSyncing.value = false
        }
    }

    fun addContainer(name: String, fruitType: String, ipAddress: String, ssid: String) {
        if (name.isBlank() || ipAddress.isBlank()) return
        viewModelScope.launch {
            val id = repository.addContainer(name, fruitType, ipAddress, ssid)
            selectContainer(id)
            _syncMessage.value = "Wadah ditambahkan. Tekan sinkronkan saat terhubung ke WiFi unit."
        }
    }

    fun deleteSelected() {
        val id = _selectedContainerId.value ?: return
        viewModelScope.launch {
            repository.deleteContainer(id)
            backToDashboard()
            _syncMessage.value = "Wadah dihapus dari perangkat ini."
        }
    }

    fun createDemoData() {
        viewModelScope.launch {
            val id = repository.seedDemoContainer(riskEngine)
            selectContainer(id)
            _syncMessage.value = "Data contoh siap. Nilainya dihitung dari tren sensor simulasi."
        }
    }

    private fun handleResult(container: FarmerContainerEntity, result: FarmerSyncResult) {
        result.risk?.let { risk ->
            val previousStatus = lastRiskStatuses.put(container.id, risk.status)
            val crossedRiskBand = risk.score != null && risk.score >= 0.4f && previousStatus != risk.status
            val cooldownPassed = System.currentTimeMillis() - (lastNotificationMillis[container.id] ?: 0L) >= NOTIFICATION_COOLDOWN_MILLIS
            if (crossedRiskBand && cooldownPassed) {
                lastNotificationMillis[container.id] = System.currentTimeMillis()
                notifyOnce("risk:${container.id}", container.name, risk.recommendation)
            }
        }
        if (result.success) staleNotificationKeys.remove(container.id)
        val staleForOneDay = container.lastSyncMillis?.let {
            System.currentTimeMillis() - it >= 24 * 60 * 60 * 1000L
        } == true
        if (!result.success && staleForOneDay && staleNotificationKeys.add(container.id)) {
            notifyOnce("stale:${container.id}", container.name, "Unit belum tersinkron. Dekati wadah untuk memeriksa data terbaru.")
        }
    }

    private fun notifyOnce(key: String, title: String, message: String) {
        if (!notificationKeys.add(key)) return
        val manager = getApplication<Application>().getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Peringatan wadah", NotificationManager.IMPORTANCE_DEFAULT))
        }
        try {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(getApplication(), CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(getApplication())
            }
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            manager.notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
            // Android 13+ may require the runtime notification permission. The
            // same alert remains visible in the dashboard when permission is off.
        }
    }

    override fun onCleared() {
        stopPolling()
        repository.close()
        super.onCleared()
    }

    private companion object {
        const val CHANNEL_ID = "farmer_alerts"
        const val NOTIFICATION_COOLDOWN_MILLIS = 6 * 60 * 60 * 1000L
    }
}
