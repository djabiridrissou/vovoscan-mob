package com.vovoscan.core.sources

import kotlin.math.sign

/** `MAJOR.MINOR.PATCH` with an optional `-prerelease` (semver 2.0 precedence; a prerelease comes before its release). */
class Semver private constructor(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>,
) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A release comes after its prereleases.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return compareValues(other.prerelease.size.sign, prerelease.size.sign)
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val diff =
                when {
                    an != null && bn != null -> compareValues(an, bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
            if (diff != 0) return diff
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    override fun equals(other: Any?): Boolean = other is Semver && compareTo(other) == 0

    override fun hashCode(): Int = listOf(major, minor, patch, prerelease).hashCode()

    override fun toString(): String = "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    companion object {
        private val pattern = Regex("^(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")

        fun parse(value: String): Semver? {
            val match = pattern.find(value.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return Semver(major.toLong(), minor.toLong(), patch.toLong(), if (pre.isEmpty()) emptyList() else pre.split('.'))
        }

        /** True when [candidate] is strictly newer than [installed]; false when either is not a version. */
        fun isNewer(
            candidate: String,
            installed: String,
        ): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(installed) ?: return false
            return a > b
        }
    }
}
