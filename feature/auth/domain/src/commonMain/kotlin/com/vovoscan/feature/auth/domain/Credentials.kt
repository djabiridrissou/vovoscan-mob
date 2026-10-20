package com.vovoscan.feature.auth.domain

/** Règles de saisie, vérifiées avant tout appel réseau ; le serveur reste juge. */
object Credentials {
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val inviteCode = Regex("^VOVO(-[A-Z0-9]{4}){2}$")

    const val MIN_PASSWORD = 12

    fun emailError(value: String): String? = if (email.matches(value.trim())) null else "Enter a valid email."

    fun passwordError(value: String): String? = if (value.length >= MIN_PASSWORD) null else "Use at least $MIN_PASSWORD characters."

    /** Codes d'invitation : « VOVO-7K2D-QX9P » (voir `new_invite_code` côté API). */
    fun normalizeInvite(value: String): String = value.trim().uppercase()

    fun inviteError(value: String): String? =
        if (inviteCode.matches(normalizeInvite(value))) null else "Invite codes look like VOVO-7K2D-QX9P."
}
