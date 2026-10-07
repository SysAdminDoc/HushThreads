/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.misc.usercertificates

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.threads.misc.extension.enableStatus
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.settingsPatch
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PATCH = "Trust user-added certificates"

/** The manifest attribute that names the network security config. */
private const val CONFIG_ATTRIBUTE = "android:networkSecurityConfig"

/** The config this patch writes when Threads names none. */
internal const val NEW_CONFIG = "hushthreads_network_security_config"

/**
 * A config for a Threads that names none: Android's default for an app that targets Android 7 or
 * later, which is the system's certificates alone, plus the user's.
 */
internal val NEW_CONFIG_TEXT = """
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <base-config>
            <trust-anchors>
                <certificates src="system" />
                <certificates src="user" overridePins="true" />
            </trust-anchors>
        </base-config>
    </network-security-config>
""".trimIndent() + "\n"

/**
 * The network security config [manifest]'s application names, as the path a resource patch opens,
 * or null when it names none. Refused when it names something other than an xml resource.
 */
internal fun configPath(manifest: Document): String? {
    val application = manifest.documentElement?.children("application")?.singleOrNull()
        ?: throw PatchException("$PATCH: AndroidManifest.xml has no single application element")
    val config = application.getAttribute(CONFIG_ATTRIBUTE)
    if (config.isEmpty()) return null
    if (!config.startsWith("@xml/")) throw PatchException("$PATCH: the network security config is \"$config\"")
    return "res/xml/${config.removePrefix("@xml/")}.xml"
}

/** Points [manifest]'s application at [NEW_CONFIG]. */
internal fun nameNewConfig(manifest: Document) {
    manifest.documentElement.children("application").single().setAttribute(CONFIG_ATTRIBUTE, "@xml/$NEW_CONFIG")
}

/**
 * Makes [config] trust the certificates the user installed: the base config, and each domain config
 * that sets trust anchors of its own, get a user entry. A domain config without its own takes the
 * base config's. The entry overrides pins, since a pin set would otherwise turn the user's
 * certificate away on the very domains it lists. A user entry already there is made to override
 * pins too. debug-overrides, which only a debuggable build reads, stays as it is. Answers how many
 * trust-anchor sets now hold the user entry. Refused when the file isn't a network security config.
 */
internal fun trustUserCertificates(config: Document): Int {
    val root = config.documentElement
    if (root == null || root.tagName != "network-security-config") {
        throw PatchException("$PATCH: the network security config has no network-security-config element")
    }
    val base = root.children("base-config").firstOrNull()
        ?: config.createElement("base-config").also { root.insertBefore(it, root.firstChild) }
    val anchors = listOf(base.children("trust-anchors").firstOrNull() ?: config.createElement("trust-anchors").also { created ->
        created.appendChild(config.createElement("certificates").apply { setAttribute("src", "system") })
        base.appendChild(created)
    }) + root.descendants("domain-config").flatMap { it.children("trust-anchors") }
    for (set in anchors) {
        val user = set.children("certificates").firstOrNull { it.getAttribute("src") == "user" }
            ?: config.createElement("certificates").also { created ->
                created.setAttribute("src", "user")
                set.appendChild(created)
            }
        user.setAttribute("overridePins", "true")
    }
    return anchors.size
}

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

/** Every element named [tag] under this one, at any depth, in document order. */
private fun Element.descendants(tag: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .flatMap { child -> listOfNotNull(child.takeIf { it.tagName == tag }) + child.descendants(tag) }

/** The resource half: Threads' own config, or a new one when it names none. */
internal val trustUserCertificatesResourcePatch = resourcePatch {
    execute {
        val path = document("AndroidManifest.xml").use { manifest ->
            configPath(manifest).also { if (it == null) nameNewConfig(manifest) }
        }
        if (path == null) get("res/xml/$NEW_CONFIG.xml").writeText(NEW_CONFIG_TEXT)
        else document(path).use { trustUserCertificates(it) }
    }
}

/**
 * Lets Android's certificate checks in Threads accept the certificates you've installed yourself.
 *
 * Threads 448 to 450 name `fb_network_security_config`: the system's certificates for everything,
 * cleartext only off Meta's domains, and a pin set on Meta's domains that a user certificate would
 * fail. Threads also checks Meta's certificates in its own code: Tigon, Meta's network stack, records
 * whether its pinning was verified, and an OkHttp-style pinner throws "Certificate pinning failure!".
 * Neither reads the network security config, so their checks stay as they are.
 */
@Suppress("unused")
val trustUserCertificatesPatch = bytecodePatch(
    name = "Trust user-added certificates",
    description = "Lets Android's certificate checks in Threads accept certificates you've installed on your " +
        "phone yourself, such as one a work or school network needs, or a debugging proxy's. Threads also checks " +
        "Meta's certificates in its own network code, which this patch doesn't change, so a proxy still can't " +
        "read most of Threads' traffic to Meta. Only pick it if you know you need it.",
    default = false,
) {
    category("Fixes")
    dependsOn(settingsPatch, threadsExtensionPatch, trustUserCertificatesResourcePatch)
    compatibleWith(*AppCompatibilities.threads())

    execute {
        enableStatus("trustUserCertificates")
    }
}
