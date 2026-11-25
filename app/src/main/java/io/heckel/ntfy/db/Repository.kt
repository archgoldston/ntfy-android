package io.heckel.ntfy.db

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.validUrl
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class Repository(private val sharedPrefs: SharedPreferences, database: Database) {
    private val subscriptionDao = database.subscriptionDao()
    private val notificationDao = database.notificationDao()
    private val deletedMessageIdDao = database.deletedMessageIdDao()
    private val userDao = database.userDao()
    private val trustedCertificateDao = database.trustedCertificateDao()
    private val clientCertificateDao = database.clientCertificateDao()
    private val customHeaderDao = database.customHeaderDao()

    private val connectionDetails = ConcurrentHashMap<String, ConnectionDetails>()
    private val connectionDetailsLiveData = MutableLiveData<Map<String, ConnectionDetails>>(connectionDetails)
    private val connectionForceReconnectVersions = ConcurrentHashMap<String, Long>() // Base URL -> Number of times "Retry" was pressed

    // TODO Move these into an ApplicationState singleton
    val detailViewSubscriptionId = AtomicLong(0L) // Omg, what a hack ...
    val mediaPlayer = MediaPlayer()

    init {
        Log.d(TAG, "Created $this")
    }

    fun getSubscriptionsLiveData(): LiveData<List<Subscription>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .combineWith(connectionDetailsLiveData) { subscriptionsWithMetadata, _ ->
                toSubscriptionList(subscriptionsWithMetadata.orEmpty())
            }
    }

    fun getSubscriptionIdsWithInstantStatusLiveData(): LiveData<Set<Pair<Long, Boolean>>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .map { list -> list.map { Pair(it.id, it.instant) }.toSet() }
    }

    suspend fun getSubscriptions(): List<Subscription> {
        return toSubscriptionList(subscriptionDao.list())
    }

    suspend fun getSubscriptionIdsWithInstantStatus(): Set<Pair<Long, Boolean>> {
        return subscriptionDao
            .list()
            .map { Pair(it.id, it.instant) }.toSet()
    }

    fun getSubscription(subscriptionId: Long): Subscription? {
        return toSubscription(subscriptionDao.get(subscriptionId))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(baseUrl: String, topic: String): Subscription? {
        return toSubscription(subscriptionDao.get(baseUrl, topic))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscriptionByConnectorToken(connectorToken: String): Subscription? {
        return toSubscription(subscriptionDao.getByConnectorToken(connectorToken))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addSubscription(subscription: Subscription) {
        subscriptionDao.add(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun updateSubscription(subscription: Subscription) {
        subscriptionDao.update(subscription)
    }

    fun updateSubscriptionIcon(subscriptionId: Long, icon: String?) {
        subscriptionDao.updateSubscriptionIcon(subscriptionId, icon)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun removeSubscription(subscription: Subscription) {
        notificationDao.removeAll(subscription.id)
        subscriptionDao.remove(subscription.id)
        updateConnectionDetails(subscription.baseUrl, ConnectionState.NOT_APPLICABLE)
    }

    suspend fun getNotifications(): List<Notification> {
        return notificationDao.list()
    }

    fun getDeletedNotificationsWithAttachments(): List<Notification> {
        return notificationDao.listDeletedWithAttachments()
    }

    fun getTrashMessages(subscriptionId: Long): List<Notification> = notificationDao.listTrash(subscriptionId)

    fun restoreAllTrash(subscriptionId: Long) = notificationDao.restoreAllTrash(subscriptionId)

    // Preserve only the (ID, subscription) tombstone to prevent re-delivery after hard deletion.
    // We delete the local attachment before deleting the database row.
    fun permanentlyDeleteTrash(context: Context, notification: Notification) {
        val current = notificationDao.get(notification.id) ?: return
        if (!current.deleted || current.recycledAt == 0L || current.subscriptionId != notification.subscriptionId) return
        val uri = current.attachment?.contentUri
        if (!uri.isNullOrEmpty()) {
            try {
                context.contentResolver.delete(android.net.Uri.parse(uri), null, null)
            } catch (e: Exception) {
                Log.w("NtfyTrash", "Could not delete local attachment for ${current.id}", e)
            }
        }
        deletedMessageIdDao.add(DeletedMessageId(current.id, current.subscriptionId))
        notificationDao.permanentlyDeleteTrash(current.id, current.subscriptionId)
    }

    fun emptyTrash(context: Context, subscriptionId: Long) {
        notificationDao.listTrash(subscriptionId).forEach { permanentlyDeleteTrash(context, it) }
    }

    fun purgeExpiredTrash(context: Context) {
        val retention = getTrashRetentionSeconds()
        if (retention == 0L) return
        val expired = notificationDao.expiredTrash(System.currentTimeMillis() / 1000L - retention)
        expired.forEach { permanentlyDeleteTrash(context, it) }
    }

    fun getTrashRetentionSeconds(): Long = sharedPrefs.getLong(SHARED_PREFS_TRASH_RETENTION, TRASH_RETENTION_DEFAULT)

    fun setTrashRetentionSeconds(value: Long) {
        require(value in listOf(0L, 86400L, 604800L, 2592000L, 7776000L))
        sharedPrefs.edit { putLong(SHARED_PREFS_TRASH_RETENTION, value) }
    }

    fun getActiveIconUris(): Set<String> {
        return notificationDao.listActiveIconUris().toSet()
    }

    fun clearIconUri(uri: String) {
        notificationDao.clearIconUri(uri)
    }

    fun getNotificationsLiveData(subscriptionId: Long): LiveData<List<Notification>> {
        return notificationDao.listFlow(subscriptionId).asLiveData()
    }

    fun getNotificationsFilteredLiveData(subscriptionId: Long, query: String): LiveData<List<Notification>> {
        return notificationDao.listFlowFiltered(subscriptionId, query).asLiveData()
    }

    fun getNotification(notificationId: String): Notification? {
        return notificationDao.get(notificationId)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNotification(notification: Notification): Boolean {
        val maybeExistingNotification = notificationDao.get(notification.id)
        if (maybeExistingNotification != null ||
            deletedMessageIdDao.contains(notification.id, notification.subscriptionId) ||
            notification.event != ApiService.EVENT_MESSAGE) {
            return false
        }
        // Mark old notifications with the same sequence ID as deleted (this is an update to an existing sequence)
        if (notification.sequenceId.isNotEmpty()) {
            notificationDao.markAsDeletedBySequenceId(notification.subscriptionId, notification.sequenceId)
        }
        subscriptionDao.updateLastNotificationId(notification.subscriptionId, notification.id)
        notificationDao.add(notification)
        return true
    }

    fun updateNotification(notification: Notification) {
        notificationDao.update(notification)
    }

    fun undeleteNotification(notificationId: String) {
        notificationDao.undelete(notificationId)
    }

    fun markAsDeleted(notificationId: String) {
        notificationDao.markAsDeleted(notificationId, System.currentTimeMillis() / 1000L)
    }

    fun markAsDeletedBySequenceId(subscriptionId: Long, sequenceId: String) {
        notificationDao.markAsDeletedBySequenceId(subscriptionId, sequenceId)
    }

    fun updateLastNotificationId(subscriptionId: Long, notificationId: String) {
        subscriptionDao.updateLastNotificationId(subscriptionId, notificationId)
    }

    /**
     * Returns the lastNotificationId to use as "since" parameter for subscribing.
     * Selects the lastNotificationId from the subscription with the most recent notification.
     * Returns null if no subscriptions have a lastNotificationId.
     */
    fun getLastNotificationId(subscriptionIds: Collection<Long>): String? {
        return subscriptionDao.getLastNotificationId(subscriptionIds)
    }

    fun markAllAsDeleted(subscriptionId: Long) {
        notificationDao.markAllAsDeleted(subscriptionId, System.currentTimeMillis() / 1000L)
    }

    fun markAllAsRead(subscriptionId: Long) {
        notificationDao.markAllAsRead(subscriptionId)
    }

    fun markAsReadBySequenceId(subscriptionId: Long, sequenceId: String) {
        notificationDao.markAsReadBySequenceId(subscriptionId, sequenceId)
    }

    fun markAsDeletedIfOlderThan(subscriptionId: Long, olderThanTimestamp: Long) {
        notificationDao.markAsDeletedIfOlderThan(subscriptionId, olderThanTimestamp)
    }

    fun removeNotificationsIfOlderThan(subscriptionId: Long, olderThanTimestamp: Long) {
        notificationDao.removeIfOlderThan(subscriptionId, olderThanTimestamp)
    }

    suspend fun getUsers(): List<User> {
        return userDao.list()
    }

    fun getUsersLiveData(): LiveData<List<User>> {
        return userDao.listFlow().asLiveData()
    }

    suspend fun addUser(user: User) {
        userDao.insert(user)
    }

    suspend fun updateUser(user: User) {
        userDao.update(user)
    }

    suspend fun getUser(baseUrl: String): User? {
        return userDao.get(baseUrl)
    }

    suspend fun deleteUser(baseUrl: String) {
        userDao.delete(baseUrl)
    }

    // Trusted certificates

    suspend fun getTrustedCertificates(): List<TrustedCertificate> {
        return trustedCertificateDao.list()
    }

    suspend fun getTrustedCertificate(baseUrl: String): TrustedCertificate? {
        return trustedCertificateDao.get(baseUrl)
    }

    suspend fun addTrustedCertificate(baseUrl: String, pem: String) {
        trustedCertificateDao.insert(TrustedCertificate(baseUrl, pem))
    }

    suspend fun removeTrustedCertificate(baseUrl: String) {
        trustedCertificateDao.delete(baseUrl)
    }

    // Client certificates

    suspend fun getClientCertificates(): List<ClientCertificate> {
        return clientCertificateDao.list()
    }

    suspend fun getClientCertificate(baseUrl: String): ClientCertificate? {
        return clientCertificateDao.get(baseUrl)
    }

    suspend fun addClientCertificate(baseUrl: String, p12Base64: String, password: String) {
        clientCertificateDao.insert(ClientCertificate(baseUrl, p12Base64, password))
    }

    suspend fun removeClientCertificate(baseUrl: String) {
        clientCertificateDao.delete(baseUrl)
    }

    fun getPollWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_POLL_WORKER_VERSION, 0)
    }

    fun setPollWorkerVersion(version: Int) {
        sharedPrefs.edit {
            putInt(SHARED_PREFS_POLL_WORKER_VERSION, version)
        }
    }

    fun getDeleteWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_DELETE_WORKER_VERSION, 0)
    }

    fun setDeleteWorkerVersion(version: Int) {
        sharedPrefs.edit {
            putInt(SHARED_PREFS_DELETE_WORKER_VERSION, version)
        }
    }

    fun getAutoRestartWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_AUTO_RESTART_WORKER_VERSION, 0)
    }

    fun setAutoRestartWorkerVersion(version: Int) {
        sharedPrefs.edit {
            putInt(SHARED_PREFS_AUTO_RESTART_WORKER_VERSION, version)
        }
    }

    fun setMinPriority(minPriority: Int) {
        if (minPriority <= MIN_PRIORITY_ANY) {
            sharedPrefs.edit {
                remove(SHARED_PREFS_MIN_PRIORITY)
            }
        } else {
            sharedPrefs.edit {
                putInt(SHARED_PREFS_MIN_PRIORITY, minPriority)
            }
        }
    }

    fun getMinPriority(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_MIN_PRIORITY, MIN_PRIORITY_ANY)
    }

    /** One secret topic per installation; survives launches and app upgrades. */
    fun getOrCreateSingleTopic(): String {
        synchronized(this) {
            sharedPrefs.getString(SHARED_PREFS_SINGLE_TOPIC, null)?.let { return it }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            var topic: String
            do {
                topic = CharArray(50) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            } while (topic.none { it.isDigit() } || topic.none { it.isLetter() })
            sharedPrefs.edit(commit = true) { putString(SHARED_PREFS_SINGLE_TOPIC, topic) }
            return topic
        }
    }

    fun getAutoDownloadMaxSize(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE, AUTO_DOWNLOAD_IMAGES)
    }

    /** The new MIME-type choices have no size limit. Existing size limits remain intact. */
    fun isUnlimitedMediaDownload(name: String?, mimeType: String?, url: String?): Boolean {
        val choice = getAutoDownloadMaxSize()
        if (choice !in listOf(AUTO_DOWNLOAD_IMAGES, AUTO_DOWNLOAD_AUDIO, AUTO_DOWNLOAD_VIDEO, AUTO_DOWNLOAD_ALL_MEDIA)) return false
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        val ext = (name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            ?: url?.substringBefore('?')?.substringAfterLast('.', ""))?.lowercase(java.util.Locale.ROOT).orEmpty()
        val category = when {
            type.startsWith("image/") -> AUTO_DOWNLOAD_IMAGES
            type.startsWith("audio/") -> AUTO_DOWNLOAD_AUDIO
            type.startsWith("video/") -> AUTO_DOWNLOAD_VIDEO
            type.isNotEmpty() && type != "application/octet-stream" -> null
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg") -> AUTO_DOWNLOAD_IMAGES
            ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "aiff") -> AUTO_DOWNLOAD_AUDIO
            ext in setOf("mp4", "mkv", "mov", "webm", "avi", "flv", "wmv", "m4v", "3gp", "mpeg", "mpg") -> AUTO_DOWNLOAD_VIDEO
            else -> null
        }
        return category != null && (choice == AUTO_DOWNLOAD_ALL_MEDIA || category == choice)
    }

    fun setAutoDownloadMaxSize(maxSize: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE, maxSize)
        }
    }

    fun getAutoDeleteSeconds(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_AUTO_DELETE_SECONDS, AUTO_DELETE_DEFAULT_SECONDS)
    }

    fun setAutoDeleteSeconds(seconds: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_AUTO_DELETE_SECONDS, seconds)
        }
    }

    fun setDarkMode(mode: Int) {
        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            sharedPrefs.edit {
                remove(SHARED_PREFS_DARK_MODE)
            }
        } else {
            sharedPrefs.edit {
                putInt(SHARED_PREFS_DARK_MODE, mode)
            }
        }
    }

    fun getDarkMode(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_DARK_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    fun setDynamicColorsEnabled(enabled: Boolean) {
        sharedPrefs.edit(commit = true) {
            putBoolean(SHARED_PREFS_DYNAMIC_COLORS, enabled)
        }
    }

    fun getDynamicColorsEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_DYNAMIC_COLORS, false)
    }

    fun setConnectionProtocol(connectionProtocol: String) {
        sharedPrefs.edit {
            putString(SHARED_PREFS_CONNECTION_PROTOCOL, connectionProtocol)
        }
    }

    fun getConnectionProtocol(): String {
        return sharedPrefs.getString(SHARED_PREFS_CONNECTION_PROTOCOL, null) ?: CONNECTION_PROTOCOL_JSONHTTP
    }

    fun getBroadcastEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_BROADCAST_ENABLED, true) // Enabled by default
    }

    fun setBroadcastEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_BROADCAST_ENABLED, enabled)
        }
    }

    fun getUnifiedPushEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_UNIFIEDPUSH_ENABLED, false) // Disabled by default
    }

    fun setUnifiedPushEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_UNIFIEDPUSH_ENABLED, enabled)
        }
    }

    fun getInsistentMaxPriorityEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_INSISTENT_MAX_PRIORITY_ENABLED, false) // Disabled by default
    }

    fun setInsistentMaxPriorityEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_INSISTENT_MAX_PRIORITY_ENABLED, enabled)
        }
    }

    fun getRecordLogs(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_RECORD_LOGS_ENABLED, false) // Disabled by default
    }

    fun setRecordLogsEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_RECORD_LOGS_ENABLED, enabled)
        }
    }

    fun getMessageBarEnabled(): Boolean {
        // Single-topic receiver never displays a publishing/message bar,
        // including after upgrading from an installation where it was enabled.
        return false
    }

    fun setMessageBarEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_MESSAGE_BAR_ENABLED, enabled)
        }
    }

    fun getBatteryOptimizationsRemindTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_BATTERY_OPTIMIZATIONS_REMIND_TIME, BATTERY_OPTIMIZATIONS_REMIND_TIME_ALWAYS)
    }

    fun setBatteryOptimizationsRemindTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_BATTERY_OPTIMIZATIONS_REMIND_TIME, timeMillis)
        }
    }

    fun getWebSocketRemindTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_WEBSOCKET_REMIND_TIME, WEBSOCKET_REMIND_TIME_ALWAYS)
    }

    fun setWebSocketRemindTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_WEBSOCKET_REMIND_TIME, timeMillis)
        }
    }

    fun getWebSocketReconnectRemindTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_WEBSOCKET_RECONNECT_REMIND_TIME, WEBSOCKET_RECONNECT_REMIND_TIME_ALWAYS)
    }

    fun setWebSocketReconnectRemindTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_WEBSOCKET_RECONNECT_REMIND_TIME, timeMillis)
        }
    }

    fun getConnectionAlertSeconds(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_CONNECTION_ALERT_SECONDS, CONNECTION_ALERT_DEFAULT)
    }

    fun setConnectionAlertSeconds(seconds: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_CONNECTION_ALERT_SECONDS, seconds)
            putLong(SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL_TIME, 0L)
        }
    }

    fun getConnectionAlertSnoozeUntilTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL_TIME, 0L)
    }

    fun setConnectionAlertSnoozeUntilTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL_TIME, timeMillis)
        }
    }

    fun getDefaultBaseUrl(): String? {
        return sharedPrefs.getString(SHARED_PREFS_DEFAULT_BASE_URL, null) ?:
            sharedPrefs.getString(SHARED_PREFS_UNIFIED_PUSH_BASE_URL, null) // Fall back to UP URL, removed when default is set!
    }

    fun setDefaultBaseUrl(baseUrl: String) {
        if (baseUrl == "") {
            sharedPrefs.edit {
                remove(SHARED_PREFS_UNIFIED_PUSH_BASE_URL) // Remove legacy key
                    .remove(SHARED_PREFS_DEFAULT_BASE_URL)
            }
        } else {
            sharedPrefs.edit {
                remove(SHARED_PREFS_UNIFIED_PUSH_BASE_URL) // Remove legacy key
                    .putString(SHARED_PREFS_DEFAULT_BASE_URL, baseUrl)
            }
        }
    }

    suspend fun getCustomHeaders(): List<CustomHeader> {
        return customHeaderDao.list()
    }

    suspend fun getCustomHeaders(baseUrl: String): List<CustomHeader> {
        return customHeaderDao.get(baseUrl)
    }

    suspend fun addCustomHeader(header: CustomHeader) {
        customHeaderDao.insert(header)
    }

    suspend fun updateCustomHeader(oldHeader: CustomHeader, newHeader: CustomHeader) {
        customHeaderDao.delete(oldHeader.baseUrl, oldHeader.name)
        customHeaderDao.insert(newHeader)
    }

    suspend fun deleteCustomHeader(header: CustomHeader) {
        customHeaderDao.delete(header.baseUrl, header.name)
    }

    fun isGlobalMuted(): Boolean {
        val mutedUntil = getGlobalMutedUntil()
        return mutedUntil == 1L || (mutedUntil > 1L && mutedUntil > System.currentTimeMillis()/1000)
    }

    fun getGlobalMutedUntil(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
    }

    fun setGlobalMutedUntil(mutedUntilTimestamp: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, mutedUntilTimestamp)
        }
    }

    fun checkGlobalMutedUntil(): Boolean {
        val mutedUntil = sharedPrefs.getLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
        val expired = mutedUntil > 1L && System.currentTimeMillis()/1000 > mutedUntil
        if (expired) {
            sharedPrefs.edit {
                putLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
            }
            return true
        }
        return false
    }

    fun getLastShareTopics(): List<String> {
        val topics = sharedPrefs.getString(SHARED_PREFS_LAST_TOPICS, "") ?: ""
        return topics.split("\n").filter { validUrl(it) }
    }

    fun addLastShareTopic(topic: String) {
        val topics = (getLastShareTopics().filterNot { it == topic } + topic).takeLast(LAST_TOPICS_COUNT)
        sharedPrefs.edit {
            putString(SHARED_PREFS_LAST_TOPICS, topics.joinToString(separator = "\n"))
        }
    }

    private fun toSubscriptionList(list: List<SubscriptionWithMetadata>): List<Subscription> {
        return list.map { s ->
            Subscription(
                id = s.id,
                baseUrl = s.baseUrl,
                topic = s.topic,
                instant = s.instant,
                dedicatedChannels = s.dedicatedChannels,
                mutedUntil = s.mutedUntil,
                minPriority = s.minPriority,
                autoDelete = s.autoDelete,
                insistent = s.insistent,
                lastNotificationId = s.lastNotificationId,
                icon = s.icon,
                upAppId = s.upAppId,
                upConnectorToken = s.upConnectorToken,
                displayName = s.displayName,
                totalCount = s.totalCount,
                newCount = s.newCount,
                lastActive = s.lastActive,
                connectionDetails = connectionDetails[s.baseUrl] ?: ConnectionDetails()
            )
        }
    }

    private fun toSubscription(s: SubscriptionWithMetadata?): Subscription? {
        if (s == null) {
            return null
        }
        return Subscription(
            id = s.id,
            baseUrl = s.baseUrl,
            topic = s.topic,
            instant = s.instant,
            dedicatedChannels = s.dedicatedChannels,
            mutedUntil = s.mutedUntil,
            minPriority = s.minPriority,
            autoDelete = s.autoDelete,
            insistent = s.insistent,
            lastNotificationId = s.lastNotificationId,
            icon = s.icon,
            upAppId = s.upAppId,
            upConnectorToken = s.upConnectorToken,
            displayName = s.displayName,
            totalCount = s.totalCount,
            newCount = s.newCount,
            lastActive = s.lastActive,
            connectionDetails = connectionDetails[s.baseUrl] ?: ConnectionDetails()
        )
    }

    fun updateConnectionDetails(baseUrl: String, state: ConnectionState, error: Throwable? = null, nextRetryTime: Long = 0L) {
        val current = connectionDetails[baseUrl]
        // How long we've been away from a healthy CONNECTED state, for the connection-lost alert.
        // Must NOT key off `error`: routine drops (EOF / stream reset) are reported with error=null to
        // suppress the UI warning icon, but they are still disconnections and must keep the clock running.
        // Cleared only once we're actually CONNECTED (or the subscription goes away).
        val disconnectedSince = when {
            state == ConnectionState.CONNECTED -> 0L
            state == ConnectionState.NOT_APPLICABLE -> 0L
            current != null && current.disconnectedSince > 0L -> current.disconnectedSince // preserve episode start
            else -> System.currentTimeMillis() // first tick of a new disconnection episode
        }
        val details = ConnectionDetails(state, error, nextRetryTime, disconnectedSince)
        if (current != details) {
            if (state == ConnectionState.NOT_APPLICABLE && error == null) {
                connectionDetails.remove(baseUrl)
            } else {
                connectionDetails[baseUrl] = details
            }
            connectionDetailsLiveData.postValue(connectionDetails.toMap())
            Log.d(TAG, "Connection details updated for $baseUrl: state=$state, error=${error?.message}, nextRetry=$nextRetryTime")
        }
    }

    fun getConnectionDetailsLiveData(): LiveData<Map<String, ConnectionDetails>> {
        return connectionDetailsLiveData
    }

    fun getConnectionDetails(): Map<String, ConnectionDetails> {
        return connectionDetails.toMap()
    }

    fun clearConnectionDetails() {
        connectionDetails.clear()
        connectionDetailsLiveData.postValue(emptyMap())
    }

    fun getConnectionForceReconnectVersion(baseUrl: String): Long {
        return connectionForceReconnectVersions[baseUrl] ?: 0L
    }

    fun incrementConnectionForceReconnectVersion(baseUrl: String) {
        connectionForceReconnectVersions.compute(baseUrl) { _, current -> (current ?: 0L) + 1 }
        Log.d(TAG, "Connection force reconnect version incremented for $baseUrl: ${connectionForceReconnectVersions[baseUrl]}")
    }

    companion object {
        const val SHARED_PREFS_ID = "MainPreferences"
        const val SHARED_PREFS_POLL_WORKER_VERSION = "PollWorkerVersion"
        const val SHARED_PREFS_DELETE_WORKER_VERSION = "DeleteWorkerVersion"
        const val SHARED_PREFS_AUTO_RESTART_WORKER_VERSION = "AutoRestartWorkerVersion"
        const val SHARED_PREFS_MUTED_UNTIL_TIMESTAMP = "MutedUntil"
        const val SHARED_PREFS_MIN_PRIORITY = "MinPriority"
        const val SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE = "AutoDownload"
        private const val SHARED_PREFS_SINGLE_TOPIC = "SingleTopicV1"
        const val SHARED_PREFS_AUTO_DELETE_SECONDS = "AutoDelete"
        private const val SHARED_PREFS_TRASH_RETENTION = "TrashRetentionSeconds"
        const val TRASH_RETENTION_DEFAULT = 30L * 24L * 60L * 60L
        const val SHARED_PREFS_CONNECTION_PROTOCOL = "ConnectionProtocol"
        const val SHARED_PREFS_DARK_MODE = "DarkMode"
        const val SHARED_PREFS_DYNAMIC_COLORS = "DynamicColors"
        const val SHARED_PREFS_BROADCAST_ENABLED = "BroadcastEnabled"
        const val SHARED_PREFS_UNIFIEDPUSH_ENABLED = "UnifiedPushEnabled"
        const val SHARED_PREFS_INSISTENT_MAX_PRIORITY_ENABLED = "InsistentMaxPriority"
        const val SHARED_PREFS_RECORD_LOGS_ENABLED = "RecordLogs"
        const val SHARED_PREFS_MESSAGE_BAR_ENABLED = "MessageBarEnabled"
        const val SHARED_PREFS_BATTERY_OPTIMIZATIONS_REMIND_TIME = "BatteryOptimizationsRemindTime" // Timestamp as millis
        const val SHARED_PREFS_WEBSOCKET_REMIND_TIME = "JsonStreamRemindTime" // "Use WebSocket" banner (used to be JSON stream deprecation banner), timestamp as millis
        const val SHARED_PREFS_WEBSOCKET_RECONNECT_REMIND_TIME = "WebSocketReconnectRemindTime" // Timestamp as millis
        const val SHARED_PREFS_UNIFIED_PUSH_BASE_URL = "UnifiedPushBaseURL" // Legacy key required for migration to DefaultBaseURL
        const val SHARED_PREFS_DEFAULT_BASE_URL = "DefaultBaseURL"
        const val SHARED_PREFS_CONNECTION_ALERT_SECONDS = "ConnectionAlertSeconds"
        const val SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL_TIME = "ConnectionAlertSnoozeUntilTime" // Timestamp in millis
        const val SHARED_PREFS_LAST_TOPICS = "LastTopics"

        private const val LAST_TOPICS_COUNT = 3

        const val MIN_PRIORITY_USE_GLOBAL = 0
        const val MIN_PRIORITY_ANY = 1

        const val MUTED_UNTIL_SHOW_ALL = 0L
        const val MUTED_UNTIL_FOREVER = 1L
        const val MUTED_UNTIL_TOMORROW = 2L

        private const val ONE_MB_BYTES = 1024 * 1024L
        const val AUTO_DOWNLOAD_NEVER = 0L // Values must match values.xml
        const val AUTO_DOWNLOAD_ALWAYS = 1L
        const val AUTO_DOWNLOAD_DEFAULT = ONE_MB_BYTES
        const val AUTO_DOWNLOAD_IMAGES = -1001L
        const val AUTO_DOWNLOAD_AUDIO = -1002L
        const val AUTO_DOWNLOAD_VIDEO = -1003L
        const val AUTO_DOWNLOAD_ALL_MEDIA = -1004L

        private const val ONE_HOUR_SECONDS = 60 * 60L
        private const val ONE_DAY_SECONDS = 24 * ONE_HOUR_SECONDS

        const val AUTO_DELETE_USE_GLOBAL = -1L // Values must match values.xml
        const val AUTO_DELETE_NEVER = 0L
        const val AUTO_DELETE_ONE_DAY_SECONDS = ONE_DAY_SECONDS
        const val AUTO_DELETE_THREE_DAYS_SECONDS = 3 * ONE_DAY_SECONDS
        const val AUTO_DELETE_ONE_WEEK_SECONDS = 7 * ONE_DAY_SECONDS
        const val AUTO_DELETE_ONE_MONTH_SECONDS = 30 * ONE_DAY_SECONDS
        const val AUTO_DELETE_THREE_MONTHS_SECONDS = 90 * ONE_DAY_SECONDS
        const val AUTO_DELETE_DEFAULT_SECONDS = AUTO_DELETE_ONE_MONTH_SECONDS

        const val INSISTENT_MAX_PRIORITY_USE_GLOBAL = -1 // Values must match values.xml
        const val INSISTENT_MAX_PRIORITY_ENABLED = 1 // 0 = Disabled (but not needed in code)

        const val CONNECTION_ALERT_NEVER = 0L
        const val CONNECTION_ALERT_FIVE_MINUTES_SECONDS = 5 * 60L
        const val CONNECTION_ALERT_FIFTEEN_MINUTES_SECONDS = 15 * 60L
        const val CONNECTION_ALERT_ONE_HOUR_SECONDS = ONE_HOUR_SECONDS
        const val CONNECTION_ALERT_THREE_HOURS_SECONDS = 3 * ONE_HOUR_SECONDS
        const val CONNECTION_ALERT_TWELVE_HOURS_SECONDS = 12 * ONE_HOUR_SECONDS
        const val CONNECTION_ALERT_DEFAULT = CONNECTION_ALERT_NEVER

        const val CONNECTION_PROTOCOL_JSONHTTP = "jsonhttp"
        const val CONNECTION_PROTOCOL_WS = "ws"

        const val BATTERY_OPTIMIZATIONS_REMIND_TIME_ALWAYS = 1L
        const val BATTERY_OPTIMIZATIONS_REMIND_TIME_NEVER = Long.MAX_VALUE

        const val WEBSOCKET_REMIND_TIME_ALWAYS = 1L
        const val WEBSOCKET_REMIND_TIME_NEVER = Long.MAX_VALUE

        const val WEBSOCKET_RECONNECT_REMIND_TIME_ALWAYS = 1L
        const val WEBSOCKET_RECONNECT_REMIND_TIME_NEVER = Long.MAX_VALUE

        private const val TAG = "NtfyRepository"
        private var instance: Repository? = null

        fun getInstance(context: Context): Repository {
            val database = Database.getInstance(context.applicationContext)
            val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
            return getInstance(sharedPrefs, database)
        }

        private fun getInstance(sharedPrefs: SharedPreferences, database: Database): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository(sharedPrefs, database)
                instance = newInstance
                newInstance
            }
        }
    }
}

/* https://stackoverflow.com/a/57079290/1440785 */
fun <T, K, R> LiveData<T>.combineWith(
    liveData: LiveData<K>,
    block: (T?, K?) -> R
): LiveData<R> {
    val result = MediatorLiveData<R>()
    result.addSource(this) {
        result.value = block(this.value, liveData.value)
    }
    result.addSource(liveData) {
        result.value = block(this.value, liveData.value)
    }
    return result
}
