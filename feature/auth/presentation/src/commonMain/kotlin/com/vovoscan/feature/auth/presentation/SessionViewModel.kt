package com.vovoscan.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.feature.auth.domain.AuthRepository
import com.vovoscan.feature.auth.domain.Session
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Root of both apps: signed in or not, and what the role allows to show. */
class SessionViewModel(
    private val auth: AuthRepository,
) : ViewModel() {
    val session: StateFlow<Session?> = auth.session

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
