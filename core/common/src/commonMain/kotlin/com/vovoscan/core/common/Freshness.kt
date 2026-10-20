package com.vovoscan.core.common

/**
 * État de synchronisation d'un écran hors ligne d'abord : les données locales s'affichent toujours,
 * [Freshness] dit seulement si elles viennent d'être relues auprès du serveur.
 */
enum class Freshness {
    /** Relecture en cours ; le cache reste affiché. */
    Refreshing,

    /** Relues auprès du serveur. */
    Fresh,

    /** Dernière relecture échouée (hors ligne, serveur injoignable) : copie locale affichée. */
    Offline,
}
