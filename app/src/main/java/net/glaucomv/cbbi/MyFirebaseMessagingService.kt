package net.glaucomv.cbbi

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "MyFirebaseMsgService"

    /**
     * Chamado quando uma mensagem é recebida.
     *
     * @param remoteMessage Objeto que representa a mensagem recebida do Firebase Cloud Messaging.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Log para ver que a mensagem foi recebida
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Verifica se a mensagem contém uma notificação (payload de notificação)
        remoteMessage.notification?.let {
            Log.d(TAG, "Notification Message Body: ${it.body}")
            // Aqui você pode criar uma notificação local customizada se desejar,
            // mas para notificações simples enviadas pelo console do Firebase,
            // o próprio SDK do FCM geralmente trata de exibir a notificação
            // quando o app está em segundo plano.
        }

        // Verifica se a mensagem contém dados (payload de dados)
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Data Payload: " + remoteMessage.data)
            // Aqui você pode processar os dados recebidos.
        }
    }

    /**
     * Chamado quando um novo token de registo do FCM é gerado.
     * Este token é o ID único do dispositivo que o FCM usa para enviar mensagens.
     *
     * @param token O novo token.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")

        // TODO: Enviar este token para o seu servidor
        // Se você tiver um servidor, é aqui que você envia o token para que
        // possa enviar notificações para este dispositivo específico mais tarde.
        // sendRegistrationToServer(token)
    }
}