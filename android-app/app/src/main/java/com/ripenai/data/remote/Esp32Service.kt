package com.ripenai.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface Esp32Service {
    @GET("status")
    suspend fun getStatus(): SensorStatusResponse

    @GET("data")
    suspend fun getSensorHistory(@Query("since") since: Long = 0L): SensorHistoryResponse

    @POST("led")
    suspend fun controlLed(@Body request: LedRequest): Response<Unit>
}
