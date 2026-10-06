package com.whocalltome.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whocalltome.app.appContainer
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.ManualLookupEntity
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.model.NumberType
import com.whocalltome.app.data.phone.ImportCallLogResult
import com.whocalltome.app.data.repository.AppCallerIdentityRepository
import com.whocalltome.app.data.settings.SecretStore
import com.whocalltome.app.data.settings.ThemeMode
import com.whocalltome.app.data.model.LookupResult
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.ProviderLookupStatus
import com.whocalltome.app.export.ImportPreview
import com.whocalltome.app.export.PreparedImport
import com.whocalltome.app.export.ImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppViewModel internal constructor(
    application: Application,
    private val repository: AppCallerIdentityRepository,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, application.appContainer.repository)

    private val container = application.appContainer
    private val profileController = NumberProfileController(
        scope = viewModelScope,
        repository = repository,
        historicalName = repository::historicalCallName,
        recordManualLookup = repository::recordManualLookup,
    )
    val numberProfileState = profileController.state
    private val historyRetry = MutableStateFlow(0L)
    val numberCallHistory = observeNumberCallHistory(
        selectedNumbers = numberProfileState.map { if (it.isInvalidNumber) null else it.e164 },
        retry = historyRetry,
        callsForNumber = repository::observeCallsForNumber,
        entries = repository.numberEntries,
        overrides = repository.overrides,
    ).flowOn(Dispatchers.IO).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NumberCallHistoryUiState(),
    )

    fun retryNumberCallHistory() { historyRetry.value++ }

    fun openNumberProfile(rawNumber: String) {
        val e164 = normalizeNumber(rawNumber)
        if (e164 == null) {
            profileController.rejectNumber(rawNumber)
            return
        }
        profileController.open(e164)
    }

    fun refreshNumberProfile() = profileController.refresh()

    fun closeNumberProfile() = profileController.close()

    fun checkNumberProfile(force: Boolean = false) = profileController.check(force = force)

    val recentCalls: StateFlow<List<CallRecordEntity>> = combine(
        repository.recentCalls,
        repository.numberEntries,
        repository.overrides,
        ::applyPersonalCallOverrides,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val personalNumbers: StateFlow<List<PersonalNumberUi>> = combine(
        repository.numberEntries,
        repository.overrides,
    ) { entries, overrides ->
        val entriesByNumber = entries.associateBy(NumberEntryEntity::e164)
        val overridesByNumber = overrides.associateBy(UserOverrideEntity::e164)
        (entriesByNumber.keys + overridesByNumber.keys)
            .map { number ->
                val entry = entriesByNumber[number]
                val override = overridesByNumber[number]
                val identity = repository.resolveLocal(number)
                PersonalNumberUi(
                    e164 = number,
                    personalName = entry?.personalName.orEmpty(),
                    displayName = identity.displayName,
                    numberType = entry?.numberType ?: NumberType.UNSPECIFIED,
                    action = override?.action ?: PersonalAction.DEFAULT,
                    personalSpam = override?.personalSpam == true,
                    updatedAt = maxOf(entry?.updatedAt ?: 0, override?.updatedAt ?: 0),
                )
            }
            .sortedByDescending(PersonalNumberUi::updatedAt)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentLookups: StateFlow<List<ManualLookupEntity>> = repository.manualLookups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lookupState = MutableStateFlow<LookupUiState>(LookupUiState.Idle)
    val lookupState = _lookupState.asStateFlow()
    private var lookupJob: Job? = null

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _pendingUndoDelete = MutableStateFlow<AppCallerIdentityRepository.PersonalNumberSnapshot?>(null)
    val pendingUndoDelete = _pendingUndoDelete.asStateFlow()

    private val _callLogState = MutableStateFlow<CallLogUiState>(CallLogUiState.Idle)
    val callLogState = _callLogState.asStateFlow()

    private val _keyStatus = MutableStateFlow(readKeyStatus())
    val keyStatus = _keyStatus.asStateFlow()

    private val _themeMode = MutableStateFlow(container.preferences.themeMode)
    val themeMode = _themeMode.asStateFlow()

    private val _lookupProvider = MutableStateFlow(container.preferences.lookupProvider)
    val lookupProvider = _lookupProvider.asStateFlow()

    private val _providerCheck = MutableStateFlow<ProviderCheckState>(ProviderCheckState.Idle)
    val providerCheck = _providerCheck.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreviewState?>(null)
    val importPreview = _importPreview.asStateFlow()

    fun lookup(rawNumber: String) {
        val e164 = container.numberNormalizer.normalize(rawNumber)
        if (e164 == null) {
            _lookupState.value = LookupUiState.Error("Введите корректный телефонный номер")
            return
        }
        lookupJob?.cancel()
        _lookupState.value = LookupUiState.Loading
        lookupJob = viewModelScope.launch {
            recordManualLookupSafely(e164)
            try {
                repository.resolveUpdates(
                    e164 = e164,
                    allowNetwork = true,
                    allowNetworkForContacts = true,
                    useAllConfiguredProviders = false,
                ).collect { update ->
                    _lookupState.value = LookupUiState.Ready(update.identity, update.providers, update.isComplete)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _lookupState.value = LookupUiState.Error(error.message ?: "Ошибка поиска")
            }
        }
    }

    fun lookupFresh(rawNumber: String) {
        val e164 = container.numberNormalizer.normalize(rawNumber)
        if (e164 == null) {
            _lookupState.value = LookupUiState.Error("Введите корректный телефонный номер")
            return
        }
        lookupJob?.cancel()
        _lookupState.value = LookupUiState.Loading
        lookupJob = viewModelScope.launch {
            recordManualLookupSafely(e164)
            try {
                val updates = mutableListOf<ProviderLookupStatus>()
                var completed = false
                repository.resolveUpdates(
                    e164 = e164,
                    force = true,
                    allowNetworkForContacts = true,
                    useAllConfiguredProviders = true,
                ).collect { update ->
                    updates.clear()
                    updates += update.providers
                    completed = update.isComplete
                    _lookupState.value = LookupUiState.Ready(update.identity, updates.toList(), completed)
                }
                val succeeded = updates.any { it.status == LookupStatus.FOUND || it.status == LookupStatus.NOT_FOUND }
                val failed = updates.any { it.status != null && it.status !in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND) }
                if (!succeeded && completed) {
                    _message.value = "Не удалось обновить данные источников; показан сохранённый результат"
                } else if (failed) {
                    _message.value = "Не удалось обновить данные части источников; показан сохранённый результат"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _lookupState.value = LookupUiState.Error(error.message ?: "Ошибка поиска")
            }
        }
    }

    private suspend fun recordManualLookupSafely(e164: String) {
        try {
            repository.recordManualLookup(e164)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A history write must not prevent the requested lookup.
        }
    }

    fun clearLookupState() {
        lookupJob?.cancel()
        lookupJob = null
        _lookupState.value = LookupUiState.Idle
    }

    fun clearLookupHistory() {
        viewModelScope.launch {
            repository.clearManualLookupHistory()
            _message.value = "История проверок очищена"
        }
    }

    fun normalizeNumber(rawNumber: String): String? = container.numberNormalizer.normalize(rawNumber)

    fun personalNumber(e164: String): PersonalNumberUi? =
        personalNumbers.value.firstOrNull { it.e164 == e164 }

    fun refreshSystemCallLog(showResult: Boolean = false) {
        viewModelScope.launch {
            _callLogState.value = CallLogUiState.Loading
            runCatching { withContext(Dispatchers.IO) { container.systemCallLogImporter.importRecent() } }
                .onSuccess { result ->
                    _callLogState.value = when (result) {
                        is ImportCallLogResult.Success -> CallLogUiState.Ready(result.count)
                        ImportCallLogResult.PermissionRequired -> CallLogUiState.PermissionRequired
                    }
                    if (showResult) {
                        _message.value = when (result) {
                            is ImportCallLogResult.Success -> "Загружено звонков: ${result.count}"
                            ImportCallLogResult.PermissionRequired ->
                                "Нужен доступ к системному журналу звонков"
                        }
                    }
                }
                .onFailure {
                    _callLogState.value = CallLogUiState.Error
                    _message.value = "Не удалось прочитать журнал звонков"
                }
        }
    }

    fun savePersonal(
        rawNumber: String,
        personalName: String,
        numberType: NumberType,
        onSaved: (() -> Unit)? = null,
        onError: (() -> Unit)? = null,
    ) {
        val e164 = container.numberNormalizer.normalize(rawNumber)
        if (e164 == null) {
            _message.value = "Некорректный номер"
            onError?.invoke()
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.savePersonalNumber(e164, personalName, numberType)
            }.onSuccess {
                _message.value = "Номер сохранён"
                profileController.refresh(allowAutomatic = false)
                onSaved?.invoke()
            }.onFailure {
                _message.value = it.message ?: "Не удалось сохранить номер"
                onError?.invoke()
            }
        }
    }

    fun setAction(e164: String, action: PersonalAction, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            runCatching { repository.setAction(e164, action) }.onSuccess {
                _message.value = when (action) {
                    PersonalAction.BLOCK -> "Блокировка включена"
                    PersonalAction.ALLOW -> "Номер разрешён"
                    PersonalAction.DEFAULT -> "Блокировка выключена"
                }
                onComplete?.invoke(true)
                profileController.refresh(allowAutomatic = false)
            }.onFailure {
                _message.value = "Не удалось изменить блокировку"
                onComplete?.invoke(false)
            }
        }
    }

    fun markSpam(e164: String, isSpam: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            runCatching { repository.markPersonalSpam(e164, isSpam) }.onSuccess {
                _message.value = if (isSpam) "Пометка спама включена" else "Пометка спама снята"
                profileController.refresh(allowAutomatic = false)
                onComplete?.invoke(true)
            }.onFailure {
                _message.value = "Не удалось изменить пометку спама"
                onComplete?.invoke(false)
            }
        }
    }

    fun loadIdentity(e164: String, onLoaded: (CallerIdentity?) -> Unit) {
        viewModelScope.launch {
            onLoaded(withContext(Dispatchers.IO) {
                runCatching { repository.resolveLocal(e164) }.getOrNull()
            })
        }
    }

    fun deletePersonal(e164: String) {
        viewModelScope.launch {
            val snapshot = repository.deletePersonalNumber(e164)
            if (snapshot != null) {
                _pendingUndoDelete.value = snapshot
                _message.value = "Личная запись удалена"
            }
        }
    }

    fun undoDelete() {
        val snapshot = _pendingUndoDelete.value ?: return
        viewModelScope.launch {
            if (repository.restorePersonalNumber(snapshot)) {
                _message.value = "Личная запись восстановлена"
            } else {
                _message.value = "Запись уже была изменена"
            }
            _pendingUndoDelete.value = null
        }
    }

    fun clearUndoDelete() {
        _pendingUndoDelete.value = null
    }

    fun setThemeMode(value: ThemeMode) {
        container.preferences.themeMode = value
        _themeMode.value = value
    }

    fun setLookupProvider(value: String) {
        container.preferences.lookupProvider = value
        _lookupProvider.value = value
        _message.value = "Источник для автоматической проверки изменён"
    }

    fun checkProvider(providerId: String, rawNumber: String) {
        val e164 = container.numberNormalizer.normalize(rawNumber)
        if (e164 == null) {
            _providerCheck.value = ProviderCheckState.Error("Введите корректный номер с кодом страны")
            return
        }
        viewModelScope.launch {
            _providerCheck.value = ProviderCheckState.Loading
            val result = repository.checkProvider(providerId, e164)
            _providerCheck.value = when (result.status) {
                LookupStatus.FOUND, LookupStatus.NOT_FOUND -> ProviderCheckState.Success(result, System.currentTimeMillis())
                LookupStatus.NETWORK_ERROR -> ProviderCheckState.Error(result.message ?: "Нет сети")
                LookupStatus.QUOTA_EXHAUSTED -> ProviderCheckState.Error(result.message ?: "Квота исчерпана")
                LookupStatus.NOT_CONFIGURED -> ProviderCheckState.Error(result.message ?: "Источник не настроен")
                LookupStatus.PROVIDER_ERROR -> ProviderCheckState.Error(result.message ?: "Источник вернул ошибку")
            }
        }
    }

    fun resetProviderCheck() { _providerCheck.value = ProviderCheckState.Idle }

    fun saveApiKeys(ipqs: String, tellows: String, phoneBlock: String) {
        if (ipqs.isNotBlank()) saveApiKey("ipqs", ipqs)
        if (tellows.isNotBlank()) saveApiKey("tellows", tellows)
        if (phoneBlock.isNotBlank()) {
            saveApiKey("phoneblock", phoneBlock)
        }
    }

    fun saveApiKey(which: String, value: String) {
        if (value.isBlank()) return
        val key = secretKey(which)
        container.preferences.setSecretSuppressed(key, false)
        container.secretStore.put(key, value.trim())
        viewModelScope.launch { repository.clearProviderFailure(which) }
        _keyStatus.value = readKeyStatus()
        _providerCheck.value = ProviderCheckState.Idle
        _message.value = "Ключ сохранён в Android Keystore"
    }

    fun clearApiKey(which: String) {
        val key = when (which) {
            "ipqs" -> SecretStore.IPQS_KEY
            "phoneblock" -> SecretStore.PHONEBLOCK_TOKEN
            else -> SecretStore.TELLOWS_KEY
        }
        container.preferences.setSecretSuppressed(key, true)
        container.secretStore.remove(key)
        _keyStatus.value = readKeyStatus()
        _providerCheck.value = ProviderCheckState.Idle
        _message.value = "Ключ удалён"
    }

    fun clearExternalCache() {
        viewModelScope.launch {
            repository.clearExternalCache()
            _message.value = "Внешний кеш очищен"
        }
    }

    fun exportData(onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { container.exporter.exportJson() }
                .onSuccess(onReady)
                .onFailure { _message.value = it.message ?: "Ошибка экспорта" }
        }
    }

    fun prepareImportData(json: String) {
        viewModelScope.launch {
            runCatching {
                val prepared = container.exporter.prepareImport(json)
                val summary = container.exporter.previewImport(prepared)
                _importPreview.value = ImportPreviewState(prepared, summary)
            }.onFailure { _message.value = it.message ?: "Ошибка чтения резервной копии" }
        }
    }

    fun applyImport(replaceConflicts: Boolean, restoreSettings: Boolean) {
        val state = _importPreview.value ?: return
        viewModelScope.launch {
            runCatching {
                container.exporter.applyImport(state.prepared, replaceConflicts, restoreSettings)
            }.onSuccess {
                _importPreview.value = null
                _themeMode.value = container.preferences.themeMode
                _message.value = "Восстановлено: ${state.summary.newCount + state.summary.conflictCount} записей"
            }.onFailure { _message.value = it.message ?: "Ошибка восстановления" }
        }
    }

    fun dismissImportPreview() { _importPreview.value = null }

    fun importData(json: String, onImported: (ImportResult) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { container.exporter.importJson(json) }
                .onSuccess {
                    _message.value = "Импортировано: ${it.entries} записей"
                    onImported(it)
                }
                .onFailure { _message.value = it.message ?: "Ошибка импорта" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun readKeyStatus() = KeyStatus(
        ipqsSaved = container.secretStore.contains(SecretStore.IPQS_KEY),
        tellowsSaved = container.secretStore.contains(SecretStore.TELLOWS_KEY),
        phoneBlockSaved = container.secretStore.contains(SecretStore.PHONEBLOCK_TOKEN),
    )

    private fun secretKey(which: String): String = when (which) {
        "ipqs" -> SecretStore.IPQS_KEY
        "phoneblock" -> SecretStore.PHONEBLOCK_TOKEN
        else -> SecretStore.TELLOWS_KEY
    }
}

data class PersonalNumberUi(
    val e164: String,
    val personalName: String,
    val displayName: String?,
    val numberType: NumberType,
    val action: PersonalAction,
    val personalSpam: Boolean,
    val updatedAt: Long,
)

data class KeyStatus(
    val ipqsSaved: Boolean,
    val tellowsSaved: Boolean,
    val phoneBlockSaved: Boolean,
)

sealed interface ProviderCheckState {
    data object Idle : ProviderCheckState
    data object Loading : ProviderCheckState
    data class Success(val result: LookupResult, val checkedAt: Long) : ProviderCheckState
    data class Error(val message: String) : ProviderCheckState
}

data class ImportPreviewState(
    val prepared: PreparedImport,
    val summary: ImportPreview,
)

sealed interface LookupUiState {
    data object Idle : LookupUiState
    data object Loading : LookupUiState
    data class Ready(
        val identity: CallerIdentity,
        val providers: List<ProviderLookupStatus> = emptyList(),
        val isComplete: Boolean = true,
    ) : LookupUiState
    data class Error(val message: String) : LookupUiState
}

sealed interface CallLogUiState {
    data object Idle : CallLogUiState
    data object Loading : CallLogUiState
    data object PermissionRequired : CallLogUiState
    data class Ready(val importedCount: Int) : CallLogUiState
    data object Error : CallLogUiState
}
