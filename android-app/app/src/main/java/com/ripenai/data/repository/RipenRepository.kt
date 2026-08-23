package com.ripenai.data.repository

import android.content.Context
import com.ripenai.data.local.AppDatabase
import com.ripenai.data.local.ScanHistory
import com.ripenai.data.remote.Esp32Service
import com.ripenai.data.remote.LedRequest
import com.ripenai.data.remote.SensorData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class RipenRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val scanHistoryDao = database.scanHistoryDao()
    private val sharedPrefs = context.getSharedPreferences("ripenai_prefs", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private var cachedIp: String? = null
    private var cachedService: Esp32Service? = null

    val history: Flow<List<ScanHistory>> = scanHistoryDao.getAllHistory()

    fun getEsp32Ip(): String {
        return sharedPrefs.getString("esp32_ip", "192.168.4.1") ?: "192.168.4.1"
    }

    fun saveEsp32Ip(ip: String) {
        sharedPrefs.edit().putString("esp32_ip", ip).apply()
    }

    fun getShowGrid(): Boolean = sharedPrefs.getBoolean("show_grid", true)
    fun setShowGrid(value: Boolean) = sharedPrefs.edit().putBoolean("show_grid", value).apply()

    fun getSaveImage(): Boolean = sharedPrefs.getBoolean("save_image", true)
    fun setSaveImage(value: Boolean) = sharedPrefs.edit().putBoolean("save_image", value).apply()

    fun getSoundNotification(): Boolean = sharedPrefs.getBoolean("sound_notif", false)
    fun setSoundNotification(value: Boolean) = sharedPrefs.edit().putBoolean("sound_notif", value).apply()

    fun getShowSimulation(): Boolean = sharedPrefs.getBoolean("show_simulation", false)
    fun setShowSimulation(value: Boolean) = sharedPrefs.edit().putBoolean("show_simulation", value).apply()

    @Synchronized
    private fun getEsp32Service(): Esp32Service {
        val ip = getEsp32Ip().trim()
        if (ip == cachedIp && cachedService != null) {
            return cachedService!!
        }

        val baseUrl = if (ip.startsWith("http://") || ip.startsWith("https://")) {
            if (ip.endsWith("/")) ip else "$ip/"
        } else {
            "http://$ip/"
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val service = retrofit.create(Esp32Service::class.java)
        cachedIp = ip
        cachedService = service
        return service
    }

    suspend fun getSensorData(): SensorData {
        return getEsp32Service().getSensorData()
    }

    suspend fun controlLed(ripeness: String): Boolean {
        return try {
            val response = getEsp32Service().controlLed(LedRequest(ripeness))
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun saveScanHistory(historyItem: ScanHistory): Long {
        return scanHistoryDao.insertHistory(historyItem)
    }

    suspend fun deleteHistory(id: Int) {
        scanHistoryDao.deleteHistoryById(id)
    }

    suspend fun clearAllHistory() {
        scanHistoryDao.clearHistory()
    }
}
