package net.glaucomv.cbbi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var cbbiValueTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var sharedPreferences: SharedPreferences

    private val cbbiApiUrl = "https://colintalkscrypto.com/cbbi/data/latest.json"
    private val prefsName = "CbbiAppPrefs"
    private val keyLastCbbiValue = "lastCbbiValue"

    companion object {
        const val CBBI_WORKER_TAG = "CbbiPeriodicWorker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cbbiValueTextView = findViewById(R.id.cbbiValueTextView)
        refreshButton = findViewById(R.id.refreshButton)
        sharedPreferences = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        refreshButton.setOnClickListener {
            fetchAndDisplayData()
        }

        displayLastSavedCbbiValue()
        scheduleCbbiWorker()
    }

    override fun onResume() {
        super.onResume()
        displayLastSavedCbbiValue()
    }

    private fun setCbbiTextColor(valueString: String?) {
        val numericValue = valueString?.toDoubleOrNull()

        if (numericValue == null) {
            cbbiValueTextView.setTextColor(ContextCompat.getColor(this, R.color.cbbi_default_text_color))
            cbbiValueTextView.typeface = Typeface.DEFAULT
            return
        }

        when {
            numericValue >= 75.0 -> {
                cbbiValueTextView.setTextColor(ContextCompat.getColor(this, R.color.cbbi_high_red))
                cbbiValueTextView.typeface = Typeface.DEFAULT_BOLD
            }
            numericValue <= 25.0 -> {
                cbbiValueTextView.setTextColor(ContextCompat.getColor(this, R.color.cbbi_low_green))
                cbbiValueTextView.typeface = Typeface.DEFAULT_BOLD
            }
            else -> {
                cbbiValueTextView.setTextColor(ContextCompat.getColor(this, R.color.cbbi_default_text_color))
                cbbiValueTextView.typeface = Typeface.DEFAULT
            }
        }
    }

    private fun displayLastSavedCbbiValue() {
        // Usa getString para pegar a string traduzível, e a string "N/A" como fallback se necessário
        val lastValueString = sharedPreferences.getString(keyLastCbbiValue, getString(R.string.status_loading))
        cbbiValueTextView.text = lastValueString
        setCbbiTextColor(lastValueString)
    }

    private fun scheduleCbbiWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodicWorkRequest = PeriodicWorkRequestBuilder<CbbiWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(CBBI_WORKER_TAG)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            CBBI_WORKER_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun fetchAndDisplayData() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                cbbiValueTextView.text = getString(R.string.status_updating) // Usa string do recurso
                setCbbiTextColor(getString(R.string.status_updating)) // Passa a string do recurso
            }

            var connection: HttpURLConnection? = null
            try {
                val url = URL(cbbiApiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val mainJsonObject = JSONObject(response)
                    val cbbiValueString = if (mainJsonObject.has("Confidence")) {
                        val confidenceObject = mainJsonObject.getJSONObject("Confidence")
                        val latestTimestamp = confidenceObject.keys().asSequence()
                            .mapNotNull { it.toLongOrNull() }.maxOrNull()
                        if (latestTimestamp != null) {
                            val decimalValue = confidenceObject.getDouble(latestTimestamp.toString())
                            (decimalValue * 100).roundToInt().toString()
                        } else {
                            getString(R.string.status_not_available) // Usa string do recurso
                        }
                    } else {
                        getString(R.string.status_not_available) // Usa string do recurso
                    }

                    sharedPreferences.edit { putString(keyLastCbbiValue, cbbiValueString) }

                    withContext(Dispatchers.Main) {
                        cbbiValueTextView.text = cbbiValueString
                        setCbbiTextColor(cbbiValueString)
                    }
                } else {
                    // Para mensagens de erro que incluem dados variáveis (como o código de erro)
                    val errorMsg = getString(R.string.status_error_prefix) + connection.responseCode
                    withContext(Dispatchers.Main) {
                        cbbiValueTextView.text = errorMsg
                        setCbbiTextColor(errorMsg) // Passa a string completa
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val failureMsg = getString(R.string.status_failure) // Usa string do recurso
                withContext(Dispatchers.Main) {
                    cbbiValueTextView.text = failureMsg
                    setCbbiTextColor(failureMsg) // Passa a string do recurso
                }
            } finally {
                connection?.disconnect()
            }
        }
    }
}