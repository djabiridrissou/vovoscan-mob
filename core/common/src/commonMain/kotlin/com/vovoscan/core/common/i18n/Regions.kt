package com.vovoscan.core.common.i18n

/**
 * Regions (ISO 3166-1 alpha-2, upper case): only for formatting dates and numbers and for the minimum age at sign-up,
 * never for what can be read (i18n/SPEC.md §2).
 */
object Regions {
    /** The officially assigned codes. */
    val CODES: List<String> =
        (
            "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW " +
                "BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET " +
                "FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL " +
                "IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA " +
                "MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ " +
                "OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN " +
                "SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG " +
                "VI VN VU WF WS YE YT ZA ZM ZW"
        ).split(' ')

    fun valid(code: String?): Boolean = code != null && code in CODES

    /** The region part of the first device language that has one ("fr-TG" → "TG"), else null. */
    fun fromDevice(languages: List<String>): String? =
        languages.firstNotNullOfOrNull { tag ->
            tag
                .replace('_', '-')
                .split('-')
                .drop(1)
                .firstOrNull { it.length == 2 && it.all(Char::isLetter) }
                ?.uppercase()
                ?.takeIf(::valid)
        }

    private val ages =
        mapOf(15 to "FR", 16 to "DE NL IE LU HU SK RO HR PL", 14 to "IT ES AT BG CY LT", 13 to "US GB BE DK EE FI LV MT PT SE NO CA AU")
            .flatMap { (age, codes) -> codes.split(' ').map { it to age } }
            .toMap()

    /** The age a new account must confirm ("I am {age} or older."): the same table on every client. */
    fun minimumAge(region: String?): Int = ages[region?.uppercase()] ?: 16
}
