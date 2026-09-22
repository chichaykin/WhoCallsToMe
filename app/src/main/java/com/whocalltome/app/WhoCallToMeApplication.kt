package com.whocalltome.app

import android.app.Application
import com.whocalltome.app.BuildConfig
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.whocalltome.app.data.db.AppDatabase
import com.whocalltome.app.data.phone.ContactLookup
import com.whocalltome.app.data.phone.NumberNormalizer
import com.whocalltome.app.data.phone.SystemCallLogImporter
import com.whocalltome.app.data.remote.HttpClient
import com.whocalltome.app.data.remote.IpqsLookupProvider
import com.whocalltome.app.data.remote.LookupProviderRegistry
import com.whocalltome.app.data.remote.PhoneBlockLookupProvider
import com.whocalltome.app.data.remote.TellowsLookupProvider
import com.whocalltome.app.data.repository.AppCallerIdentityRepository
import com.whocalltome.app.data.settings.AppPreferences
import com.whocalltome.app.data.settings.SecretStore
import com.whocalltome.app.export.UserDataExporter
import com.whocalltome.app.service.CallerNotificationManager
import com.whocalltome.app.worker.CacheCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

class WhoCallToMeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationManager.createChannel()
        scheduleMaintenance()
    }

    private fun scheduleMaintenance() {
        val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CacheCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class AppContainer(application: Application) {
    private val database = AppDatabase.create(application)
    val dao = database.dao()
    val secretStore = SecretStore(application)
    val preferences = AppPreferences(application)
    val numberNormalizer = NumberNormalizer(application)
    private val httpClient = HttpClient()
    private val providerRegistry = LookupProviderRegistry(
        secrets = secretStore,
        preferences = preferences,
        ipqs = IpqsLookupProvider(secretStore, httpClient),
        tellows = TellowsLookupProvider(secretStore, httpClient),
        phoneBlock = PhoneBlockLookupProvider(secretStore, httpClient),
    )
    val repository = AppCallerIdentityRepository(
        dao = dao,
        contactLookup = ContactLookup(application),
        providers = providerRegistry,
    )
    val systemCallLogImporter = SystemCallLogImporter(
        context = application,
        numberNormalizer = numberNormalizer,
        repository = repository,
        dao = dao,
    )
    val exporter = UserDataExporter(dao, preferences)
    val notificationManager = CallerNotificationManager(application)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        seedDefaultSecret(SecretStore.PHONEBLOCK_TOKEN, BuildConfig.PHONEBLOCK_DEFAULT_TOKEN)
        seedDefaultSecret(SecretStore.IPQS_KEY, BuildConfig.IPQS_DEFAULT_KEY)
    }

    private fun seedDefaultSecret(key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && !secretStore.contains(key) && !preferences.isSecretSuppressed(key)) {
            secretStore.put(key, trimmed)
        }
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as WhoCallToMeApplication).container
