package com.vovoscan.feature.sources.presentation

import com.vovoscan.feature.sources.domain.PluginCandidate
import com.vovoscan.feature.sources.domain.SettingField
import com.vovoscan.feature.sources.domain.SettingType
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourceOrigin

/** What a source says about itself, ready to show (detail screen and install preview). */
data class SourceInfoUi(
    val key: String,
    val name: String,
    /** Shown when there is no icon. */
    val initial: String,
    /** https or `data:` URL; null: show [initial]. */
    val iconUrl: String?,
    val version: String,
    /** "Version 1.0.0". */
    val versionLabel: String,
    /** "Official", the repository's name, "Added from a link" or "Added from a file". */
    val originLabel: String,
    val originUrl: String?,
    val thirdParty: Boolean,
    /** The manifest's description. */
    val summary: String,
    /** "Manga · Books". */
    val kindsLabel: String,
    /** "EN · FR". */
    val langsLabel: String,
    /** "Public domain", "Licensed"; null when unknown (internal source). */
    val rightsLabel: String?,
    val rightsNotes: String?,
    /** "Claimed by the author, not checked by vovoscan" for a third-party source. */
    val rightsDisclaimer: String?,
    val hosts: List<String>,
    /** "This source connects your phone to: a.example, b.example"; null when it connects to none (internal source). */
    val hostsLabel: String?,
)

/** A setting in a form: the value shown, its kind (keyboard), and its validation error. */
data class SettingFieldUi(
    val name: String,
    val label: String,
    val value: String,
    val help: String?,
    val required: Boolean,
    val type: SettingType,
    /** Default value, as a hint. */
    val placeholder: String?,
    val error: String?,
)

/** User-facing copy shared by the sources screens. */
object SourceLabels {
    const val THIRD_PARTY_WARNING = "vovoscan doesn't review third-party sources. You're responsible for what you read with them."
    const val RIGHTS_DISCLAIMER = "Claimed by the author, not checked by vovoscan"
    const val REMOVE_MESSAGE = "Titles you follow stay in your library."
    const val OFFICIAL = "Official"
    const val THIRD_PARTY = "Third-party"
    const val INSTALL = "Install"
    const val INSTALLED = "Installed"
    const val UPDATE = "Update"
    const val SETTINGS_NEEDED = "Fill in the required settings to use this source."
    const val KEEP_ONE_VISIBLE = "Keep at least one source visible."
    const val PENDING = "Your changes will sync when you're back online."
    const val TURN_ON = "Turn on"
    const val INTERNAL_SUMMARY = "vovoscan's own manga, webtoons and books, read and voiced in your language."
    const val INTERNAL_ENGINE = "vovoscan's catalog"
    const val ON_DEVICE_ENGINE = "Read on your device"

    fun turnedOn(name: String) = "$name is on. Its titles are in the catalog."

    fun kind(kind: String) =
        when (kind) {
            "comic" -> "Manga"
            "book" -> "Books"
            "audiobook" -> "Audiobooks"
            "video" -> "Videos"
            else -> kind
        }

    fun kinds(kinds: List<String>) = kinds.joinToString(" · ") { kind(it) }

    fun langs(langs: List<String>) = langs.joinToString(" · ") { it.uppercase() }

    fun rights(basis: String?) =
        when (basis) {
            null -> null
            "public-domain" -> "Public domain"
            "licensed" -> "Licensed"
            else -> basis.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }

    fun hosts(hosts: List<String>) =
        hosts.takeIf { it.isNotEmpty() }?.let { "This source connects your phone to: ${it.joinToString(", ")}" }

    fun newHosts(hosts: List<String>) = hosts.takeIf { it.isNotEmpty() }?.let { "This version also connects to: ${it.joinToString(", ")}" }

    fun version(version: String) = "Version $version"

    fun shortVersion(version: String) = "v$version"

    fun chip(thirdParty: Boolean) = if (thirdParty) THIRD_PARTY else OFFICIAL

    fun initial(name: String) =
        name
            .trim()
            .firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            .orEmpty()

    fun origin(
        origin: SourceOrigin,
        repositoryName: String?,
    ) = when (origin) {
        SourceOrigin.Official -> OFFICIAL
        SourceOrigin.Repository -> repositoryName ?: "From a repository"
        SourceOrigin.Link -> "Added from a link"
        SourceOrigin.File -> "Added from a file"
    }

    fun sourceCount(count: Int?) =
        when (count) {
            null -> null
            1 -> "1 source"
            else -> "$count sources"
        }
}

internal fun SourceItem.toInfo() =
    SourceInfoUi(
        key = key,
        name = name,
        initial = SourceLabels.initial(name),
        iconUrl = iconUrl,
        version = version,
        versionLabel = SourceLabels.version(version),
        originLabel = SourceLabels.origin(origin, repositoryName),
        originUrl = originUrl,
        thirdParty = thirdParty,
        summary = summary,
        kindsLabel = SourceLabels.kinds(kinds),
        langsLabel = SourceLabels.langs(langs),
        rightsLabel = SourceLabels.rights(rightsBasis),
        rightsNotes = rightsNotes,
        rightsDisclaimer = SourceLabels.RIGHTS_DISCLAIMER.takeIf { thirdParty && rightsBasis != null },
        hosts = hosts,
        hostsLabel = SourceLabels.hosts(hosts),
    )

internal fun PluginCandidate.toInfo(repositoryName: String?) =
    SourceInfoUi(
        key = info.key,
        name = info.name,
        initial = SourceLabels.initial(info.name),
        iconUrl = iconUrl,
        version = info.version,
        versionLabel = SourceLabels.version(info.version),
        originLabel = SourceLabels.origin(origin, repositoryName),
        originUrl = originUrl,
        thirdParty = true,
        summary = info.summary,
        kindsLabel = SourceLabels.kinds(info.kinds),
        langsLabel = SourceLabels.langs(info.langs),
        rightsLabel = SourceLabels.rights(info.rightsBasis),
        rightsNotes = info.rightsNotes,
        rightsDisclaimer = SourceLabels.RIGHTS_DISCLAIMER,
        hosts = info.hosts,
        hostsLabel = SourceLabels.hosts(info.hosts),
    )

internal fun SettingField.toUi(
    value: String,
    error: String?,
) = SettingFieldUi(name, label, value, help, required, type, defaultValue, error)
