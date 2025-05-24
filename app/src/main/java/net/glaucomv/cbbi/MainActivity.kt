package net.glaucomv.cbbi

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // --- VARIÁVEIS DA CLASSE ---
    private lateinit var cbbiValueTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var sharedPreferences: SharedPreferences

    private val cbbiApiUrl = "https://colintalkscrypto.com/cbbi/data/latest.json"
    private val prefsName = "CbbiAppPrefs"
    private val keyLastCbbiValue = "lastCbbiValue"

    // --- LAUNCHER PARA PEDIR A PERMISSÃO DE NOTIFICAÇÃO ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG_FCM, getString(R.string.log_notification_permission_granted))
            subscribeToTopic()
        } else {
            Log.d(TAG_FCM, getString(R.string.log_notification_permission_denied))
        }
    }

    // --- MÉTODO ONCREATE ---
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
        askNotificationPermission()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                subscribeToTopic()
            }
        } else {
            subscribeToTopic()
        }
    }

    // --- FUNÇÃO PARA INSCREVER NO TÓPICO FCM ---
    private fun subscribeToTopic() {
        lifecycleScope.launch {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC_ALL_USERS).await()
                Log.d(TAG_FCM, getString(R.string.log_subscribed_to_topic_success, FCM_TOPIC_ALL_USERS))
            } catch (e: Exception) {
                Log.e(TAG_FCM, getString(R.string.log_subscribe_to_topic_failed, FCM_TOPIC_ALL_USERS), e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        displayLastSavedCbbiValue()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setCbbiTextColor(valueString: String?) {
        val numericValue = valueString?.toDoubleOrNull()
        if (numericValue == null) {
            cbbiValueTextView.setTextColor(ContextCompat.getColor(this, R.color.cbbi_default_text_color))
            cbbiValueTextView.typeface = Typeface.DEFAULT
            return
        }
        when {
            numericValue >= 85.0 -> {
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
                cbbiValueTextView.text = getString(R.string.status_updating)
                setCbbiTextColor(null)
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
                            getString(R.string.status_not_available)
                        }
                    } else {
                        getString(R.string.status_not_available)
                    }
                    sharedPreferences.edit { putString(keyLastCbbiValue, cbbiValueString) }
                    withContext(Dispatchers.Main) {
                        cbbiValueTextView.text = cbbiValueString
                        setCbbiTextColor(cbbiValueString)
                    }
                } else {
                    val errorMsg = getString(R.string.status_error_with_code, connection.responseCode.toString())
                    withContext(Dispatchers.Main) {
                        cbbiValueTextView.text = errorMsg
                        setCbbiTextColor(null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val failureMsg = getString(R.string.status_failure)
                withContext(Dispatchers.Main) {
                    cbbiValueTextView.text = failureMsg
                    setCbbiTextColor(null)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // --- COMPANION OBJECT ---
    companion object {
        const val CBBI_WORKER_TAG = "CbbiPeriodicWorker"
        const val FCM_TOPIC_ALL_USERS = "all_users"
        const val TAG_FCM = "MainActivityFCM"
    }
}
