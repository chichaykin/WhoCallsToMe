package com.whocalltome.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String)

class HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1_500
                readTimeout = 2_500
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WhoCallToMe/0.1")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
}
