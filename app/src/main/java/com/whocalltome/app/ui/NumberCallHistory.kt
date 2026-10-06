package com.whocalltome.app.ui

import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

data class NumberCallHistoryUiState(
    val e164: String? = null,
    val calls: List<CallRecordEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeNumberCallHistory(
    selectedNumbers: Flow<String?>,
    retry: Flow<Long>,
    callsForNumber: (String) -> Flow<List<CallRecordEntity>>,
    entries: Flow<List<NumberEntryEntity>>,
    overrides: Flow<List<UserOverrideEntity>>,
): Flow<NumberCallHistoryUiState> = combine(selectedNumbers.distinctUntilChanged(), retry) { number, attempt ->
    number to attempt
}.flatMapLatest { (number, _) ->
    if (number == null) {
        flowOf(NumberCallHistoryUiState())
    } else {
        flow {
            emitAll(combine(callsForNumber(number), entries, overrides) { calls, names, rules ->
                NumberCallHistoryUiState(number, applyPersonalCallOverrides(calls, names, rules))
            })
        }.onStart {
            emit(NumberCallHistoryUiState(number, isLoading = true))
        }.catch {
            emit(NumberCallHistoryUiState(number, error = "Не удалось загрузить историю звонков"))
        }
    }
}
