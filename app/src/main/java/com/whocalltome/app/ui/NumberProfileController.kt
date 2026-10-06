package com.whocalltome.app.ui

import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.CallerIdentityRepository
import com.whocalltome.app.data.model.ContactLookupResult
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.ProviderLookupStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NumberProfileUiState(
    val e164: String? = null,
    val identity: CallerIdentity? = null,
    val historicalName: String? = null,
    val isLoadingLocal: Boolean = false,
    val isChecking: Boolean = false,
    val hasChecked: Boolean = false,
    val providers: List<ProviderLookupStatus> = emptyList(),
    val localError: String? = null,
    val lookupError: String? = null,
    val isInvalidNumber: Boolean = false,
) {
    val isContact: Boolean get() = identity?.contact is ContactLookupResult.Found
    val hasPartialFailure: Boolean get() =
        providers.any { it.status in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND) } &&
            providers.any { it.status != null && it.status !in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND) }
}

/** Owns one card session independently of the manual-search tab. All state writes use the caller's scope. */
internal class NumberProfileController(
    private val scope: CoroutineScope,
    private val repository: CallerIdentityRepository,
    private val historicalName: suspend (String) -> String?,
    private val recordManualLookup: suspend (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(NumberProfileUiState())
    val state = mutableState.asStateFlow()
    private var session = 0L
    private var localRevision = 0L
    private var localJob: Job? = null
    private var lookupJob: Job? = null
    private var automaticAttempted = false

    fun open(e164: String) {
        if (state.value.e164 == e164) return
        close()
        mutableState.value = NumberProfileUiState(e164 = e164, isLoadingLocal = true)
        refresh()
    }

    fun close() {
        session++
        localRevision++
        localJob?.cancel()
        lookupJob?.cancel()
        automaticAttempted = false
        mutableState.value = NumberProfileUiState()
    }

    fun rejectNumber(rawNumber: String) {
        if (state.value.e164 == rawNumber && state.value.isInvalidNumber) return
        close()
        mutableState.value = NumberProfileUiState(
            e164 = rawNumber,
            isInvalidNumber = true,
            localError = "Некорректный номер. Проверьте номер в сохранённой записи",
        )
    }

    fun refresh(allowAutomatic: Boolean = true) {
        if (state.value.isInvalidNumber) return
        val number = state.value.e164 ?: return
        val currentSession = session
        val revision = ++localRevision
        localJob?.cancel()
        localJob = scope.launch {
            try {
                val identity = withContext(ioDispatcher) { repository.resolveLocal(number) }
                val hint = withContext(ioDispatcher) {
                    try {
                        historicalName(number)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null // A historical hint must not prevent displaying current data.
                    }
                }
                if (currentSession != session || revision != localRevision) return@launch
                mutableState.value = state.value.copy(
                    identity = identity,
                    historicalName = hint?.takeIf(String::isNotBlank),
                    isLoadingLocal = false,
                    localError = null,
                )
                if (allowAutomatic && !automaticAttempted &&
                    identity.contact == ContactLookupResult.NotFound && identity.personalName.isNullOrBlank()
                ) {
                    check(manual = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentSession == session && revision == localRevision) {
                    mutableState.value = state.value.copy(
                        identity = null,
                        isLoadingLocal = false,
                        localError = "Не удалось загрузить данные номера",
                    )
                }
            }
        }
    }

    fun check(force: Boolean = false, manual: Boolean = true) {
        val current = state.value
        val number = current.e164 ?: return
        if (current.isChecking || current.identity == null || current.isLoadingLocal) return
        automaticAttempted = true
        val currentSession = session
        mutableState.value = current.copy(isChecking = true, hasChecked = true, providers = emptyList(), lookupError = null)
        lookupJob = scope.launch {
            try {
                if (manual) {
                    try {
                        withContext(ioDispatcher) { recordManualLookup(number) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A history-write failure must not prevent the requested lookup.
                    }
                }
                repository.resolveUpdates(
                    e164 = number,
                    force = force,
                    allowNetwork = true,
                    allowNetworkForContacts = manual,
                    useAllConfiguredProviders = false,
                ).flowOn(ioDispatcher).collect { update ->
                    if (currentSession == session) {
                        mutableState.value = state.value.copy(providers = update.providers)
                        // Read current contacts again instead of applying a possibly older identity snapshot.
                        refresh(allowAutomatic = false)
                    }
                }
                if (currentSession == session) mutableState.value = state.value.copy(isChecking = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentSession == session) {
                    mutableState.value = state.value.copy(
                        isChecking = false,
                        lookupError = "Не удалось проверить номер. Попробуйте ещё раз",
                    )
                }
            }
        }
    }
}
