package lt.skautai.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.nio.file.Files
import kotlin.io.path.Path

class FirebaseNotificationService(
    private val deviceService: DeviceService,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(FirebaseNotificationService::class.java)
    private val messaging: FirebaseMessaging? = initializeMessaging()

    fun sendToUser(
        userId: java.util.UUID,
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        val resource = data["resource"] ?: "unknown"
        val entityId = data["reservationId"] ?: data["entityId"] ?: "unknown"
        notificationService.createForUser(
            userId = userId,
            title = title,
            body = body,
            data = data
        )
        val firebaseMessaging = messaging
        if (firebaseMessaging == null) {
            logger.info(
                "Skipping push notification: Firebase is not configured (resource={}, entityId={})",
                resource,
                entityId
            )
            return
        }
        val tokens = deviceService.deviceTokensForUser(userId).distinct()
        if (tokens.isEmpty()) {
            logger.info(
                "Skipping push notification: user has no registered devices (userId={}, resource={}, entityId={})",
                userId,
                resource,
                entityId
            )
            return
        }

        var successCount = 0
        var failureCount = 0
        tokens.forEach { token ->
            runCatching {
                val message = Message.builder()
                    .setToken(token)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .putAllData(data)
                    .build()
                firebaseMessaging.send(message)
                successCount += 1
            }.onFailure { error ->
                failureCount += 1
                if (error.isStaleDeviceTokenFailure()) {
                    deviceService.deleteDeviceToken(token)
                    logger.info(
                        "Deleted stale push notification token (userId={}, resource={}, entityId={}, errorType={})",
                        userId,
                        resource,
                        entityId,
                        error::class.simpleName
                    )
                }
                logger.warn(
                    "Failed to send push notification (userId={}, resource={}, entityId={}, errorType={}, message={})",
                    userId,
                    resource,
                    entityId,
                    error::class.simpleName,
                    error.message
                )
            }
        }
        logger.info(
            "Push notification send finished (userId={}, resource={}, entityId={}, tokenCount={}, successCount={}, failureCount={})",
            userId,
            resource,
            entityId,
            tokens.size,
            successCount,
            failureCount
        )
    }

    private fun initializeMessaging(): FirebaseMessaging? {
        val path = System.getProperty("FIREBASE_SERVICE_ACCOUNT_PATH")
            ?: System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH")
            ?: run {
                logger.info("Firebase Admin is not configured: FIREBASE_SERVICE_ACCOUNT_PATH is not set")
                return null
            }
        val keyPath = Path(path)
        if (!Files.exists(keyPath)) {
            logger.warn("Firebase Admin is not configured: service account file does not exist")
            return null
        }
        return runCatching {
            if (FirebaseApp.getApps().isEmpty()) {
                FileInputStream(keyPath.toFile()).use { stream ->
                    val options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(stream))
                        .build()
                    FirebaseApp.initializeApp(options)
                }
            }
            logger.info("Firebase Admin initialized for push notifications")
            FirebaseMessaging.getInstance()
        }.onFailure { error ->
            logger.warn(
                "Firebase Admin initialization failed (errorType={}, message={})",
                error::class.simpleName,
                error.message
            )
        }.getOrNull()
    }

    private fun Throwable.isStaleDeviceTokenFailure(): Boolean {
        val messagingError = (this as? FirebaseMessagingException)?.messagingErrorCode
        if (messagingError == MessagingErrorCode.UNREGISTERED || messagingError == MessagingErrorCode.INVALID_ARGUMENT) {
            return true
        }
        val normalized = message.orEmpty().lowercase()
        return "registration-token-not-registered" in normalized ||
            "requested entity was not found" in normalized ||
            "invalid registration token" in normalized
    }
}
