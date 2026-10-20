package com.vovoscan.core.common

/** Erreurs vues par les cas d'usage et les écrans, indépendantes du réseau ou de la base. */
sealed interface AppError {
    /** Pas de réseau ou serveur injoignable : le cache local reste affiché. */
    data object Network : AppError

    /** Session absente, expirée ou révoquée : reconnexion nécessaire. */
    data object Unauthorized : AppError

    /** Permission manquante (403) : l'action n'est pas proposée à ce rôle. */
    data object Forbidden : AppError

    /** Trop de demandes (429). */
    data object RateLimited : AppError

    /** Refus métier de l'API : `code` stable (testé, traduit), `message` pour l'humain. */
    data class Rejected(
        val code: String,
        val message: String,
    ) : AppError

    data class Unknown(
        val detail: String,
    ) : AppError
}
