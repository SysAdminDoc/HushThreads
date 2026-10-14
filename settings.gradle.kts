import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import java.security.MessageDigest

rootProject.name = "hushthreads"

pluginManagement {
    val allowLocalPlugins = providers.gradleProperty("allowMavenLocal")
        .map(String::toBoolean)
        .getOrElse(false)
    repositories {
        // Local plugin artifacts are useful while developing the plugin, but they must never
        // silently override the reviewed repositories used for a release build.
        if (allowLocalPlugins) mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))
            }
        }
        // baksmali and smali come from a source build, resolved as com.github.MorpheApp.smali
        // rather than from Maven Central. The comment used to name iBotPeaches/smali, which the
        // build stopped using when the patches plugin switched forks at 1.3.2.
        //
        // google/smali 3.0.10 shipped officially on 2026-08-20, so "until official releases come
        // out again" is no longer the reason to keep this. The reason is that the pin has to be
        // whatever the patcher was built against: two copies of dexlib2 on the R8 classpath fail
        // buildAndroid with "Classpath type already present".
        maven { url = uri("https://jitpack.io") }
    }
}

// app.morphe:morphe-patches-gradle-plugin 1.3.4 brings kotlin-gradle-plugin 2.4.10 as a
// runtimeElements dependency, and 2.4.10 is inside CVE-2026-53914 (GHSA-r937-wjx7-w2jp), a
// deserialisation flaw in the Kotlin build cache. 2.4.20 is the first release outside it.
// Nothing in this tree requests a Kotlin plugin by id, so pluginManagement's eachPlugin has
// nothing to rewrite: the version has to be forced on the settings classpath itself. Take this
// out when the plugin moves past 2.4.20 on its own; gradle/verification-metadata.xml is what
// says which version actually resolved. It does not reach :patches:patcherProvidedClasspath,
// where the patcher's own kotlin-stdlib and kotlin-reflect stay at 2.4.10.
//
// The same classpath brings Bouncy Castle 1.79, by way of the patcher's 1.77 pin and the
// Android build tools' own 1.79 request: 1.77 is inside all six advisories (CVE-2025-8916
// 1.44 to 1.78, CVE-2026-5588 1.67 to 1.83, CVE-2025-14813, CVE-2026-0636 fixed in 1.84,
// CVE-2026-8763, CVE-2026-13506 fixed in 1.85) and 1.79 is inside all but CVE-2025-8916.
// :patches and :extensions:threads each force their own graphs to
// the reviewed release, but neither reaches this one: a force on a project configuration cannot
// touch the classpath the plugins themselves resolve on, and that classpath is where the
// signing code actually runs. 1.86 is spelled out here rather than read from
// gradle/libs.versions.toml because the version catalog does not exist yet at this point in the
// build; the catalog pins the same value and says why it is that one. Move both together.
//
// The Android Gradle plugin brings jose4j 0.9.5 and jdom2 2.0.6 here, inside GHSA-3677-xxcr-wjqv
// (CVE-2024-29371, fixed in jose4j 0.9.6) and GHSA-2363-cqg2-863c (CVE-2021-33813, fixed in jdom2
// 2.0.6.1). Both fixes are patch releases of the same line. Only those two requests are replaced,
// so a later plugin asking for a newer version gets it, and any other vulnerable one is the build
// classpath inventory's to show and the release receipt's to refuse. Take a force out once the
// plugin asks for a fixed version.
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.4.20")
            }
            if (requested.group == "org.bouncycastle") {
                useVersion("1.86")
            }
            if (requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" && requested.version == "0.9.5") {
                useVersion("0.9.6")
            }
            if (requested.group == "org.jdom" && requested.name == "jdom2" && requested.version == "2.0.6") {
                useVersion("2.0.6.1")
            }
        }
    }
}

val allowMavenLocal = providers.gradleProperty("allowMavenLocal")
    .map(String::toBoolean)
    .getOrElse(false)

plugins {
    id("app.morphe.patches") version "1.3.4"
}

settings {
    extensions {
        defaultNamespace = "app.morphe.extension"

        // Must resolve to an absolute path (not relative),
        // otherwise the extensions in subfolders will fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}

if (!allowMavenLocal) {
    val localMavenUrl = file(System.getProperty("user.home"))
        .resolve(".m2/repository")
        .toURI()
        .toString()
        .removeSuffix("/")
    val localRepositories = dependencyResolutionManagement.repositories.filter { repository ->
        repository is MavenArtifactRepository
                && repository.url.toString().removeSuffix("/") == localMavenUrl
    }
    localRepositories.forEach { repository ->
        dependencyResolutionManagement.repositories.remove(repository)
    }
}

include(":patches:stub")

// The classpath the plugins above load from, which builds and signs the bundle: the Morphe
// patches plugin and everything it brings, the Android Gradle plugin, R8 and apksig among them.
// The release SBOM leaves it out on purpose, since it lists what the bundle carries, so it's
// written down here instead: :buildClasspathInventory puts it in build/build-classpath/inventory.json,
// and scripts/build-release-receipt.ps1 asks OSV about it apart from the SBOM. Only this script
// sees this classpath. Each project's own buildscript classpath goes in too, though no project
// declares a plugin version of its own today. A version here is the one Gradle's resolution
// chose. That says nothing about which classes a plugin actually loads from it.
//
// A request for another version than the one chosen is listed with the reasons Gradle gives (the
// forces above, or conflict resolution), so a vulnerable version that was only asked for shows as
// asked for, not as present. A dependency that didn't resolve is listed as such, and the receipt
// refuses the inventory rather than reading it as clean.
val buildClasspathLimit = 4000
val buildClasspathGraphs = sortedMapOf<String, Map<String, Any?>>()

fun buildClasspathPurlSegment(text: String): String = buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xff
        val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '.'.code || c == '-'.code || c == '_'.code || c == '~'.code
        if (plain) append(c.toChar()) else append("%%%02X".format(c))
    }
}

fun buildClasspathGraph(name: String, configuration: Configuration): Map<String, Any?> {
    class Edge(val requested: String, val outcome: String) {
        val requestedBy = sortedSetOf<String>()
        val reasons = sortedSetOf<String>()
    }
    val root = configuration.incoming.resolutionResult.root
    val components = sortedMapOf<String, ModuleComponentIdentifier>()
    val conflicts = sortedMapOf<String, Edge>()
    val unresolved = sortedMapOf<String, Edge>()
    val seen = mutableSetOf<Any>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component.id)) continue
        val id = component.id as? ModuleComponentIdentifier
        val from = if (id == null) name else "${id.group}:${id.module}:${id.version}"
        if (id != null) components[from] = id
        for (dependency in component.dependencies) {
            if (dependency.isConstraint) continue
            val requested = dependency.requested
            if (dependency is UnresolvedDependencyResult) {
                val failure = dependency.failure.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(300)
                unresolved.getOrPut(requested.displayName) { Edge(requested.displayName, failure) }.requestedBy += from
                continue
            }
            val selected = (dependency as ResolvedDependencyResult).selected
            queue.addLast(selected)
            val chosen = selected.id as? ModuleComponentIdentifier ?: continue
            if (requested !is ModuleComponentSelector) continue
            val asked = if (requested.version.isBlank()) "${requested.group}:${requested.module}"
                else "${requested.group}:${requested.module}:${requested.version}"
            val got = "${chosen.group}:${chosen.module}:${chosen.version}"
            if (asked == got || (requested.version.isBlank() && asked == "${chosen.group}:${chosen.module}")) continue
            val conflict = conflicts.getOrPut("$asked $got") { Edge(asked, got) }
            conflict.requestedBy += from
            for (reason in selected.selectionReason.descriptions) conflict.reasons += "${reason.cause}: ${reason.description}"
        }
    }
    if (components.size > buildClasspathLimit) {
        throw GradleException("The $name holds ${components.size} components, more than the inventory's bound of $buildClasspathLimit.")
    }
    fun listed(edges: Map<String, Edge>, outcome: String) = edges.values.map {
        linkedMapOf("requested" to it.requested, outcome to it.outcome,
            "requestedBy" to it.requestedBy.take(20), "requestedByCount" to it.requestedBy.size,
            "reasons" to it.reasons.take(10))
    }
    return linkedMapOf(
        "name" to name,
        "components" to components.values.map {
            linkedMapOf("group" to it.group, "name" to it.module, "version" to it.version,
                "purl" to "pkg:maven/${buildClasspathPurlSegment(it.group)}/${buildClasspathPurlSegment(it.module)}@" +
                    buildClasspathPurlSegment(it.version))
        },
        "conflicts" to listed(conflicts, "resolved"),
        "unresolved" to listed(unresolved, "failure"),
    )
}

fun buildClasspathJson(value: Any?, indent: String = ""): String {
    val inner = "$indent  "
    return when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (ch in value) when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch.code < 0x20 || ch.code > 0x7e -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
            append('"')
        }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") {
            inner + buildClasspathJson(it.key.toString()) + ": " + buildClasspathJson(it.value, inner)
        }
        is Iterable<*> -> if (!value.iterator().hasNext()) "[]" else value.joinToString(",\n", "[\n", "\n$indent]") {
            inner + buildClasspathJson(it, inner)
        }
        else -> throw GradleException("The build classpath inventory can't hold a ${value::class.java.simpleName}.")
    }
}

buildClasspathGraphs["settings classpath"] = buildClasspathGraph("settings classpath", buildscript.configurations.getByName("classpath"))
val buildClasspathSettingsSha256 = MessageDigest.getInstance("SHA-256")
    .digest(settingsDir.resolve("settings.gradle.kts").readBytes()).joinToString("") { "%02x".format(it) }
val buildClasspathGradleVersion = gradle.gradleVersion

gradle.allprojects {
    val graphName = if (path == ":") "root project buildscript classpath" else "$path buildscript classpath"
    // After the project's script has run, when its own plugins block has resolved, and in its own
    // context, which Gradle wants for resolving a project's configuration.
    afterEvaluate {
        val graph = buildClasspathGraph(graphName, buildscript.configurations.getByName("classpath"))
        synchronized(buildClasspathGraphs) { buildClasspathGraphs[graphName] = graph }
    }
}

gradle.rootProject {
    tasks.register("buildClasspathInventory") {
        group = "verification"
        description = "Writes the plugin classpaths this build resolved to build/build-classpath/inventory.json"
        val output = layout.buildDirectory.file("build-classpath/inventory.json")
        val document = provider {
            val graphs = synchronized(buildClasspathGraphs) { buildClasspathGraphs.values.toList() }
            buildClasspathJson(linkedMapOf(
                "schema" to "hushthreads-build-classpath-1",
                "gradle" to buildClasspathGradleVersion,
                "settingsScript" to linkedMapOf("path" to "settings.gradle.kts", "sha256" to buildClasspathSettingsSha256),
                "covers" to ("The classpath settings.gradle.kts resolved for its plugins block, which the build's " +
                    "plugins and tools run from, and every project's buildscript classpath. Each version is the one " +
                    "Gradle's resolution chose. A request for another version is listed under conflicts."),
                "leavesOut" to ("What the release bundle carries (the release SBOM lists that), the patcher-provided " +
                    "classpath, the compile-only, test and lint graphs, and the advisory comparator."),
                "graphs" to graphs,
            )) + "\n"
        }
        inputs.property("inventory", document)
        outputs.file(output)
        doLast {
            val file = output.get().asFile
            file.parentFile.mkdirs()
            file.writeText(document.get(), Charsets.UTF_8)
        }
    }
}
