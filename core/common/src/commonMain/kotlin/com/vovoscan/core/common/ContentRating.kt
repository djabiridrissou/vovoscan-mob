package com.vovoscan.core.common

/**
 * How explicit a title is (the server's `contentRating` scale, shared by every source). Safe mode (the account's default) shows
 * `Safe` titles only: a title with no rating is hidden too, the same rule as the server's.
 */
enum class ContentRating(
    val apiValue: String,
) {
    Safe("safe"),
    Suggestive("suggestive"),
    Erotica("erotica"),
    Pornographic("pornographic"),
    ;

    companion object {
        fun fromApi(value: String?): ContentRating? {
            val key = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.apiValue == key }
        }

        /** What safe mode lets through: everything once the account chose to see titles for adults, else `Safe` only. */
        fun shown(
            rating: ContentRating?,
            showAdult: Boolean,
        ): Boolean = showAdult || rating == Safe
    }
}
