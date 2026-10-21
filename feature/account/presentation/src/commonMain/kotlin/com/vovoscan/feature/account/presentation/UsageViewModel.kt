package com.vovoscan.feature.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.CivilDate
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.DAY_MILLIS
import com.vovoscan.core.common.DeviceTimeZone
import com.vovoscan.core.common.SystemClock
import com.vovoscan.core.common.UtcOffset
import com.vovoscan.core.common.localDate
import com.vovoscan.core.common.startOfLocalDay
import com.vovoscan.feature.account.domain.Provider
import com.vovoscan.feature.account.domain.Usage
import com.vovoscan.feature.account.domain.UsageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cost periods, in the device's time zone. */
enum class UsagePeriod(
    val label: String,
) {
    Last7Days("Last 7 days"),
    Last30Days("Last 30 days"),
    ThisMonth("This month"),
    ;

    /** `from` (inclusive) of the period ending now; the end stays open. */
    fun fromMillis(
        nowMillis: Long,
        offset: UtcOffset,
    ): Long =
        when (this) {
            Last7Days -> nowMillis - 7 * DAY_MILLIS
            Last30Days -> nowMillis - 30 * DAY_MILLIS
            ThisMonth -> localDate(nowMillis, offset).let { startOfLocalDay(CivilDate(it.year, it.month, 1), offset) }
        }
}

data class PeriodChip(
    val period: UsagePeriod,
    val label: String,
    val selected: Boolean,
)

/** One provider's total over the period. */
data class UsageTotalRow(
    val provider: Provider,
    val providerLabel: String,
    /** "$1.24". */
    val costLabel: String,
    /** "182,400 tokens ✦ 12 requests". */
    val unitsLabel: String,
)

/** One cost-log row (latest first). */
data class UsageEntryRow(
    val id: String,
    val providerLabel: String,
    /** The model or voice engine, when the server names one. */
    val modelLabel: String?,
    /** "12,000 tokens". */
    val amountLabel: String,
    val costLabel: String,
    /** "Oct 20 ✦ 14:05". */
    val dateLabel: String,
    val chapterId: String?,
)

data class UsageUiState(
    val period: UsagePeriod = UsagePeriod.Last30Days,
    val loading: Boolean = true,
    val totals: List<UsageTotalRow> = emptyList(),
    /** "$1.42" over every provider; null before the first answer. */
    val totalCostLabel: String? = null,
    val entries: List<UsageEntryRow> = emptyList(),
    val error: String? = null,
) {
    val periods: List<PeriodChip> get() = UsagePeriod.entries.map { PeriodChip(it, it.label, it == period) }

    val empty: Boolean get() = !loading && error == null && totals.isEmpty() && entries.isEmpty()

    val emptyLabel: String get() = AccountText.USAGE_EMPTY

    val footnote: String get() = AccountText.USAGE_FOOTNOTE
}

sealed interface UsageIntent {
    data class SelectPeriod(
        val period: UsagePeriod,
    ) : UsageIntent

    data object Refresh : UsageIntent
}

/** What the user's own keys were charged (estimates): totals per provider and the latest entries. Online only. */
class UsageViewModel(
    private val repository: UsageRepository,
    private val clock: Clock = SystemClock,
    private val offset: UtcOffset = DeviceTimeZone,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun onIntent(intent: UsageIntent) {
        when (intent) {
            is UsageIntent.SelectPeriod -> {
                if (intent.period == state.value.period && !state.value.loading) return
                mutableState.update { it.copy(period = intent.period) }
                load()
            }
            UsageIntent.Refresh -> load()
        }
    }

    private fun load() {
        val period = state.value.period
        job?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        job =
            viewModelScope.launch {
                val result = repository.usage(period.fromMillis(clock.nowMillis(), offset), null)
                mutableState.update {
                    when (result) {
                        is AppResult.Ok -> show(it, result.value)
                        is AppResult.Err -> it.copy(loading = false, error = AccountText.usageError(result.error))
                    }
                }
            }
    }

    private fun show(
        state: UsageUiState,
        usage: Usage,
    ) = state.copy(
        loading = false,
        totals =
            usage.totals.map { total ->
                UsageTotalRow(
                    total.provider,
                    total.provider.label,
                    AccountText.money(total.costUsd),
                    total.units.entries
                        .sortedBy { it.key.ordinal }
                        .joinToString(" ✦ ") { AccountText.amount(it.key, it.value) },
                )
            },
        totalCostLabel = AccountText.money(usage.totals.sumOf { it.costUsd }),
        entries =
            usage.entries.map { e ->
                UsageEntryRow(
                    e.id,
                    e.provider.label,
                    e.model,
                    AccountText.amount(e.unitType, e.units),
                    AccountText.money(e.costUsd),
                    AccountText.dateTime(e.createdAtMillis, offset),
                    e.chapterId,
                )
            },
    )
}
