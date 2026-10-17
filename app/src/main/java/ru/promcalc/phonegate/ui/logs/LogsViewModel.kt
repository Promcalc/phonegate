package ru.promcalc.phonegate.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.promcalc.phonegate.data.logs.LogCategory
import ru.promcalc.phonegate.data.logs.LogEntry
import ru.promcalc.phonegate.logRepository

/**
 * Состояние экрана логов: фильтр по категориям, поиск и отфильтрованный список
 * (сначала свежие записи).
 */
class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.logRepository

    private val _selectedCategories = MutableStateFlow<Set<LogCategory>>(emptySet())
    val selectedCategories: StateFlow<Set<LogCategory>> = _selectedCategories.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val entries: StateFlow<List<LogEntry>> = combine(
        repository.entries,
        _selectedCategories,
        _query,
    ) { all, categories, query ->
        all.asReversed().filter { entry ->
            (categories.isEmpty() || entry.category in categories) && matches(entry, query)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggleCategory(category: LogCategory) {
        val current = _selectedCategories.value
        _selectedCategories.value = if (category in current) current - category else current + category
    }

    fun showAllCategories() {
        _selectedCategories.value = emptySet()
    }

    fun clear() {
        repository.clear()
    }

    private fun matches(entry: LogEntry, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return entry.message.contains(needle, ignoreCase = true) ||
            entry.details?.contains(needle, ignoreCase = true) == true ||
            entry.category.title.contains(needle, ignoreCase = true)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
