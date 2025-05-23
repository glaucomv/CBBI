package net.glaucomv.cbbi

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var cbbiValueTextView: TextView
    private lateinit var refreshButton: Button // Botão para atualização manual
    private lateinit var sharedPreferences: SharedPreferences

    private val cbbiApiUrl = "https://colintalkscrypto.com/cbbi/data/latest.json"
    private val prefsName = "CbbiAppPrefs"
    private val keyLastCbbiValue = "lastCbbiValue"

    companion object {
        const val CBBI_WORKER_TAG = "CbbiPeriodicWorker" // Tag para o WorkManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Define o layout da atividade

        // Inicializa as Views
        cbbiValueTextView = findViewById(R.id.cbbiValueTextView)
        refreshButton = findViewById(R.id.refreshButton)
        sharedPreferences = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Configura o clique do botão para atualização manual
        refreshButton.setOnClickListener {
            fetchAndDisplayData() // Chama a função de busca manual
        }

        // Exibe o último valor salvo ao iniciar o aplicativo
        displayLastSavedCbbiValue()

        // Agenda o trabalho periódico em segundo plano para atualizações automáticas
        scheduleCbbiWorker()
    }

    override fun onResume() {
        super.onResume()
        // Atualiza a UI com o valor mais recente sempre que o app retornar ao primeiro plano
        displayLastSavedCbbiValue()
    }

    // Função para exibir o último valor CBBI salvo nas SharedPreferences
    private fun displayLastSavedCbbiValue() {
        val lastValue = sharedPreferences.getString(keyLastCbbiValue, "Carregando...")
        cbbiValueTextView.text = lastValue
    }

    // Função para agendar o Worker que fará as atualizações automáticas
    private fun scheduleCbbiWorker() {
        // Define restrições para o worker (ex: precisa de conexão com a internet)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Cria uma requisição periódica para rodar a cada 4 horas
        // Este intervalo longo é bom para economizar bateria.
        val periodicWorkRequest = PeriodicWorkRequestBuilder<CbbiWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(CBBI_WORKER_TAG) // Adiciona uma tag para identificar o trabalho
            .build()

        // Agenda o trabalho com o WorkManager,
        // Usando KEEP para não duplicar o trabalho se ele já estiver agendado
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            CBBI_WORKER_TAG,
            ExistingPeriodicWorkPolicy.KEEP, // Política para trabalhos existentes
            periodicWorkRequest
        )
    }

    // Função para buscar os dados da API manualmente, atualizar a UI e salvar
    private fun fetchAndDisplayData() {
        // Inicia uma coroutine no escopo do ciclo de vida da Activity, na thread de IO
        // Dispatchers.IO é otimizado para operações de entrada/saída como chamadas de rede.
        lifecycleScope.launch(Dispatchers.IO) {
            // Mostra "Atualizando..." na UI principal antes de iniciar a busca
            withContext(Dispatchers.Main) {
                cbbiValueTextView.text = "Atualizando..."
            }

            var connection: HttpURLConnection? = null
            try {
                val url = URL(cbbiApiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000 // Timeout de conexão em milissegundos
                connection.readTimeout = 8000    // Timeout de leitura em milissegundos

                // Verifica se a requisição foi bem-sucedida (código HTTP 200 OK)
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    // Usa o bloco 'use' para garantir que o InputStream e o BufferedReader sejam fechados automaticamente
                    // após o uso, mesmo que ocorram exceções. Isso ajuda a liberar recursos de forma eficiente.
                    val response = connection.inputStream.bufferedReader().use { it.readText() }

                    // Analisa o JSON para obter o valor do CBBI
                    val mainJsonObject = JSONObject(response)
                    val cbbiValueString = if (mainJsonObject.has("Confidence")) {
                        val confidenceObject = mainJsonObject.getJSONObject("Confidence")
                        // Encontra o timestamp mais recente
                        val latestTimestamp = confidenceObject.keys().asSequence()
                            .mapNotNull { it.toLongOrNull() }.maxOrNull()

                        if (latestTimestamp != null) {
                            // Obtém o valor decimal e converte para o formato de índice (0-100)
                            val decimalValue = confidenceObject.getDouble(latestTimestamp.toString())
                            (decimalValue * 100).roundToInt().toString()
                        } else {
                            "N/A" // Caso não encontre timestamp
                        }
                    } else {
                        "N/A" // Caso a chave "Confidence" não exista
                    }

                    // Salva o valor obtido nas SharedPreferences de forma assíncrona (apply)
                    sharedPreferences.edit().putString(keyLastCbbiValue, cbbiValueString).apply()

                    // Atualiza a UI na thread principal com o novo valor
                    withContext(Dispatchers.Main) {
                        cbbiValueTextView.text = cbbiValueString
                    }
                } else {
                    // Em caso de erro na requisição, mostra mensagem de erro
                    withContext(Dispatchers.Main) {
                        cbbiValueTextView.text = "Erro: ${connection.responseCode}"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace() // Loga o erro para depuração
                // Em caso de exceção, mostra mensagem de falha
                withContext(Dispatchers.Main) {
                    cbbiValueTextView.text = "Falha"
                }
            } finally {
                connection?.disconnect() // Garante que a conexão HttpURLConnection seja fechada
            }
        }
    }
}
