/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe

import org.w3c.dom.Document
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A fixture build's resources decoded the way Morphe Manager decodes them for a resource patch: the
 * patcher's own ARSCLib coder, so `document(path)` in a test reads the text a patch reads. Each file
 * comes back as a DOM parsed like the patcher's, with `android:` attributes as plain names.
 *
 * The coder is internal to the patcher, so it's reached by its JVM names, pinned to morphe-patcher
 * 1.15.1 as [PatchContexts] is: a bump that renames one fails here, naming it.
 */
internal class FixtureResources private constructor(private val work: File, private val coder: Closeable) : AutoCloseable {
    /** The decoded file at [path], as `res/xml/name.xml` or `AndroidManifest.xml`. */
    fun document(path: String): Document {
        val file = CODER.getMethod("getFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(coder, path, null, false) as File
        if (!file.isFile) throw AssertionError("the decoded build has no $path")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    override fun close() {
        coder.close()
        work.deleteRecursively()
    }

    companion object {
        /** The base APK's entry in a bundle, as [FixtureDex] reads it. */
        private val BASE_NAMES = listOf("base.apk", "com.instagram.barcelona.apk")

        private val CODER = Class.forName("app.morphe.patcher.resource.coder.ArsclibResourceCoder")

        /** Decodes [build]'s base APK into a folder of its own, removed on [close]. */
        fun of(build: File): FixtureResources {
            val work = Files.createTempDirectory("hushthreads-resources").toFile()
            val apk = if (build.extension == "apk") build else File(work, "base.apk").also { out ->
                ZipFile(build).use { zip ->
                    val base = BASE_NAMES.firstNotNullOfOrNull { zip.getEntry(it) }
                        ?: throw AssertionError("${build.name} holds none of ${BASE_NAMES.joinToString()}")
                    zip.getInputStream(base).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            val coder = CODER.getConstructor(File::class.java, File::class.java, Set::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(File(work, "decoded"), apk, emptySet<Any>(), false) as Closeable
            CODER.getMethod("decodeResources").invoke(coder)
            return FixtureResources(work, coder)
        }

        /** [document] as text with no indentation of its own, so two documents compare by content. */
        fun text(document: Document): String {
            val transformer = TransformerFactory.newInstance().newTransformer()
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            return StringWriter().also { transformer.transform(DOMSource(document), StreamResult(it)) }.toString()
        }
    }
}
