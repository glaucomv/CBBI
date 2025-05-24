package net.glaucomv.cbbi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import androidx.core.content.edit

class CbbiWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val cbbiApiUrl = "https://colintalkscrypto.com/cbbi/data/latest.json"
    private val prefsName = "CbbiAppPrefs"
    private val keyLastCbbiValue = "lastCbbiValue"

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val sharedPreferences = applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            var connection: HttpURLConnection? = null
            try {
                val url = URL(cbbiApiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val response = reader.readText()
                    reader.close()
                    inputStream.close()

                    val mainJsonObject = JSONObject(response)
                    val cbbiValueString = if (mainJsonObject.has("Confidence")) {
                        val confidenceObject = mainJsonObject.getJSONObject("Confidence")
                        val latestTimestamp = confidenceObject.keys().asSequence()
                            .mapNotNull { it.toLongOrNull() }.maxOrNull()

                        if (latestTimestamp != null) {
                            val decimalValue = confidenceObject.getDouble(latestTimestamp.toString())
                            (decimalValue * 100).roundToInt().toString()
                        } else {
                            null // Não foi possível obter o valor
                        }
                    } else {
                        null // Chave "Confidence" não encontrada
                    }

                    if (cbbiValueString != null) {
                        sharedPreferences.edit { putString(keyLastCbbiValue, cbbiValueString) }
                        Result.success()
                    } else {
                        // Se não conseguiu obter o valor, pode tentar novamente mais tarde ou falhar
                        Result.retry() // ou Result.failure()
                    }
                } else {
                    Result.retry() // Problema de rede, tentar novamente
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure() // Falha na execução
            } finally {
                connection?.disconnect()
            }
        }
    }
}
