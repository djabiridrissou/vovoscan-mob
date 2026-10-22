package com.vovoscan.feature.sources.presentation

import com.vovoscan.core.common.i18n.t
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
    val THIRD_PARTY_WARNING: String get() = t("sources.thirdPartyWarning")
    val RIGHTS_DISCLAIMER: String get() = t("sources.rightsDisclaimer")
    val REMOVE_MESSAGE: String get() = t("sources.removeMessage")
    val OFFICIAL: String get() = t("sources.official")
    val THIRD_PARTY: String get() = t("sources.thirdParty")
    val INSTALL: String get() = t("sources.install")
    val INSTALLED: String get() = t("sources.installed")
    val UPDATE: String get() = t("sources.update")
    val SETTINGS_NEEDED: String get() = t("sources.settingsNeeded")
    val KEEP_ONE_VISIBLE: String get() = t("sources.keepOneVisible")
    val PENDING: String get() = t("sources.pending")
    val TURN_ON: String get() = t("sources.turnOn")
    val INTERNAL_SUMMARY: String get() = t("sources.internalSummary")
    val INTERNAL_ENGINE: String get() = t("sources.internalEngine")
    val ON_DEVICE_ENGINE: String get() = t("sources.onDeviceEngine")

    fun turnedOn(name: String) = t("sources.turnedOn", "name" to name)

    fun kind(kind: String) =
        when (kind) {
            "comic" -> t("sources.kindComic")
            "book" -> t("sources.kindBook")
            "audiobook" -> t("sources.kindAudiobook")
            "video" -> t("sources.kindVideo")
            else -> kind
        }

    fun kinds(kinds: List<String>) = kinds.joinToString(" · ") { kind(it) }

    fun langs(langs: List<String>) = langs.joinToString(" · ") { it.uppercase() }

    fun rights(basis: String?) =
        when (basis) {
            null -> null
            "public-domain" -> t("sources.rightsPublicDomain")
            "licensed" -> t("sources.rightsLicensed")
            else -> basis.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }

    fun hosts(hosts: List<String>) = hosts.takeIf { it.isNotEmpty() }?.let { t("sources.hosts", "hosts" to it.joinToString(", ")) }

    fun newHosts(hosts: List<String>) = hosts.takeIf { it.isNotEmpty() }?.let { t("sources.newHosts", "hosts" to it.joinToString(", ")) }

    fun version(version: String) = t("sources.version", "version" to version)

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
        SourceOrigin.Repository -> repositoryName ?: t("sources.fromRepository")
        SourceOrigin.Link -> t("sources.fromLink")
        SourceOrigin.File -> t("sources.fromFile")
    }

    fun sourceCount(count: Int?) =
        when (count) {
            null -> null
            else -> t("sources.count", "count" to count)
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
