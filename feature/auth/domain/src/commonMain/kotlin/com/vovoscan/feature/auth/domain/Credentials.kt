package com.vovoscan.feature.auth.domain

/** Input rules, checked before any network call; the server has the final say. */
object Credentials {
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val inviteCode = Regex("^VOVO(-[A-Z0-9]{4}){2}$")

    const val MIN_PASSWORD = 12

    fun emailError(value: String): String? = if (email.matches(value.trim())) null else "Enter a valid email."

    fun passwordError(value: String): String? = if (value.length >= MIN_PASSWORD) null else "Use at least $MIN_PASSWORD characters."

    /** Invite codes: "VOVO-7K2D-QX9P" (see `new_invite_code` in the API). */
    fun normalizeInvite(value: String): String = value.trim().uppercase()

    fun inviteError(value: String): String? =
        if (inviteCode.matches(normalizeInvite(value))) null else "Invite codes look like VOVO-7K2D-QX9P."
}
