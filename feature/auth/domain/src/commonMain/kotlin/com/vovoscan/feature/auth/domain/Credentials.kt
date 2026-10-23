package com.vovoscan.feature.auth.domain

import com.vovoscan.core.common.i18n.t

/** Input rules, checked before any network call; the server has the final say. */
object Credentials {
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val inviteCode = Regex("^VOVO(-[A-Z0-9]{4}){2}$")

    const val MIN_PASSWORD = 12

    fun emailError(value: String): String? = if (email.matches(value.trim())) null else t("auth.emailInvalid")

    fun passwordError(value: String): String? = if (value.length >= MIN_PASSWORD) null else t("auth.passwordShort", "count" to MIN_PASSWORD)

    /** Invite codes: "VOVO-7K2D-QX9P" (see `new_invite_code` in the API). */
    fun normalizeInvite(value: String): String = value.trim().uppercase()

    fun inviteError(value: String): String? = if (inviteCode.matches(normalizeInvite(value))) null else t("auth.inviteInvalid")
}
