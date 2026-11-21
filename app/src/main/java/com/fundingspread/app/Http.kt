package com.fundingspread.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class HttpError(message: String) : Exception(message)

object Http {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val UA = "FundingSpread/1.0 (Android)"

    private suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", UA)
            }
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw HttpError("HTTP $code")
            val decoded = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(stream)
            } else {
                stream
            }
            val body = BufferedReader(InputStreamReader(decoded, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw HttpError("HTTP $code")
            body
        } catch (e: HttpError) {
            throw e
        } catch (e: Exception) {
            throw HttpError(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    suspend fun obj(url: String): JSONObject = try {
        JSONObject(text(url))
    } catch (e: HttpError) {
        throw e
    } catch (e: Exception) {
        throw HttpError("unexpected response body")
    }

    suspend fun arr(url: String): JSONArray = try {
        JSONArray(text(url))
    } catch (e: HttpError) {
        throw e
    } catch (e: Exception) {
        throw HttpError("unexpected response body")
    }
}

/**
 * Exchanges are inconsistent about whether numbers arrive as JSON numbers or
 * as strings ("0.00010000"). These read either, and return null for absent,
 * null or unparseable values rather than a misleading zero.
 */
fun JSONObject.dbl(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key, "")
    if (s.isEmpty()) return null
    return s.toDoubleOrNull()
}

fun JSONObject.lng(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key, "")
    if (s.isEmpty()) return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
