package com.ripenai.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface Esp32Service {
    @GET("data")
    suspend fun getSensorData(): SensorData

    @POST("led")
    suspend fun controlLed(@Body request: LedRequest): Response<Unit>
}
