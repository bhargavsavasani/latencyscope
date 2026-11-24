import kotlin.metadata.KmClass
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmPackage
import kotlin.metadata.Visibility
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile

/**
 * Extracts the Kotlin-visible public API of a compiled module.
 *
 * ### Why not read the bytecode directly
 * The JVM has no notion of `internal`: Kotlin compiles an internal class to a
 * public one, so a plain bytecode dump would demand that `latencyscope-noop`
 * reimplement `Recorder`, `CsvWriter` and every other implementation detail.
 * The `@Metadata` annotation the compiler attaches to each class *does* carry
 * the real Kotlin visibility, so that is what decides what counts as API here.
 *
 * ### Why not binary-compatibility-validator
 * It only registers its tasks when it sees the `org.jetbrains.kotlin.android`
 * plugin id, which cannot be applied under AGP 9's built-in Kotlin support —
 * see the Phase 0 decision in PLAN.md. This also does something BCV cannot:
 * it compares two *different modules* for parity rather than one module
 * against its own past.
 */
internal object ApiSignatures {

    /**
     * Every public declaration in [jar], as stable one-line strings.
     *
     * Lines are the unit of comparison, so they carry everything a caller can
     * observe — owning class, name, JVM descriptor — and nothing it cannot,
     * such as parameter names or declaration order.
     */
    fun extract(jar: File): Set<String> {
        val perEntry = linkedMapOf<String, Entry>()
        JarFile(jar).use { file ->
            file.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .sortedBy { it.name }
                .forEach { entry ->
                    val bytes = file.getInputStream(entry).use { it.readBytes() }
                    val packageName = entry.name.substringBeforeLast('/', "").replace('/', '.')
                    signaturesOf(bytes, packageName)?.let { perEntry[entry.name] = it }
                }
        }

        val publicClasses = perEntry.values.mapNotNull { it.className }.toSet()
        return perEntry.values
            .filter { it.className == null || isReachable(it.className, publicClasses) }
            .flatMap { it.lines }
            .toSortedSet()
    }

    /** One class file's contribution. [className] is null for a file facade. */
    private data class Entry(val className: String?, val lines: List<String>)

    /**
     * Whether a caller can actually name [name].
     *
     * A nested class records its own visibility, and a `companion object`
     * inside an `internal class` records itself as public — `UrlParts.Companion`
     * is one. What matters is whether every class enclosing it is public too,
     * so this walks outwards until it reaches the package.
     */
    private fun isReachable(name: String, publicClasses: Set<String>): Boolean {
        var current = name
        while (true) {
            val outer = current.substringBeforeLast('.', missingDelimiterValue = "")
            // A segment starting with a capital is a class; a lowercase one is
            // a package, which means we have walked out of the nesting.
            val enclosingIsClass = outer.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
            if (!enclosingIsClass) return true
            if (outer !in publicClasses) return false
            current = outer
        }
    }

    /** The declaring class paired with its API, or null when nothing is public. */
    private fun signaturesOf(classBytes: ByteArray, packageName: String): Entry? {
        val metadata = readMetadata(classBytes) ?: return null
        val parsed = runCatching { KotlinClassMetadata.readStrict(metadata) }.getOrNull() ?: return null

        return when (parsed) {
            is KotlinClassMetadata.Class -> {
                val kmClass = parsed.kmClass
                val lines = classSignatures(kmClass)
                if (lines.isEmpty()) null else Entry(kmClass.name.replace('/', '.'), lines)
            }
            // A `*Kt` facade holding top-level declarations. The facade class is
            // an implementation detail of how Kotlin lowers a file, so only its
            // members count, and it is always reachable.
            is KotlinClassMetadata.FileFacade -> Entry(null, packageSignatures(parsed.kmPackage, packageName))
            is KotlinClassMetadata.MultiFileClassPart ->
                Entry(null, packageSignatures(parsed.kmPackage, packageName))
            // Synthetic classes, multi-file facades and unknown kinds carry no
            // declarations of their own.
            else -> null
        }
    }

    private fun classSignatures(kmClass: KmClass): List<String> {
        if (kmClass.visibility != Visibility.PUBLIC && kmClass.visibility != Visibility.PROTECTED) {
            return emptyList()
        }
        val name = kmClass.name.replace('/', '.')
        val out = mutableListOf("class $name")

        // Supertypes are part of the contract: code that passes a report screen
        // to something expecting an Activity breaks if noop changes the parent.
        kmClass.supertypes
            .map { it.classifierName() }
            .filter { it != "kotlin.Any" }
            .sorted()
            .forEach { out += "class $name extends $it" }

        kmClass.kmEnumEntries.forEach { out += "enum $name.${it.name}" }

        kmClass.constructors
            .filter { it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED }
            .forEach { constructor ->
                val descriptor = constructor.signature?.descriptor ?: return@forEach
                out += "constructor $name.<init>$descriptor"
            }

        out += memberSignatures(name, kmClass)
        return out
    }

    /**
     * Top-level declarations, qualified by their package.
     *
     * Without the package two modules could each declare a top-level
     * `installLatencyScope` and the gate would read one as satisfying the
     * other.
     */
    private fun packageSignatures(kmPackage: KmPackage, packageName: String): List<String> =
        memberSignatures(owner = packageName.ifEmpty { null }, container = kmPackage)

    /**
     * Functions and properties, keyed by their JVM signature.
     *
     * A property contributes its accessors rather than its backing field: the
     * field is private, and the getter is what a caller actually binds to.
     */
    private fun memberSignatures(owner: String?, container: KmDeclarationContainer): List<String> {
        val out = mutableListOf<String>()
        val prefix = owner?.let { "$it." } ?: ""

        container.functions
            .filter { it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED }
            .forEach { function ->
                val signature = function.signature ?: return@forEach
                out += "fun $prefix${signature.name}${signature.descriptor}"
            }

        container.properties
            .filter { it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED }
            .forEach { property ->
                property.getterSignature?.let { out += "fun $prefix${it.name}${it.descriptor}" }
                property.setterSignature?.let { out += "fun $prefix${it.name}${it.descriptor}" }
                // A `const val` has no getter; the field itself is the API.
                property.fieldSignature?.takeIf { property.getterSignature == null }?.let {
                    out += "field $prefix${it.name}:${it.descriptor}"
                }
            }

        return out
    }

    private fun KmType.classifierName(): String =
        when (val classifier = this.classifier) {
            is KmClassifier.Class -> classifier.name.replace('/', '.')
            is KmClassifier.TypeAlias -> classifier.name.replace('/', '.')
            is KmClassifier.TypeParameter -> "T${classifier.id}"
        }

    /** Pulls the raw `@Metadata` values off a class file without loading it. */
    private fun readMetadata(classBytes: ByteArray): kotlin.Metadata? {
        var kind: Int? = null
        var metadataVersion: IntArray? = null
        var data1: MutableList<String>? = null
        var data2: MutableList<String>? = null
        var extraString: String? = null
        var packageName: String? = null
        var extraInt: Int? = null

        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    if (descriptor != "Lkotlin/Metadata;") return null
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            when (name) {
                                "k" -> kind = value as? Int
                                "mv" -> metadataVersion = value as? IntArray
                                "xs" -> extraString = value as? String
                                "pn" -> packageName = value as? String
                                "xi" -> extraInt = value as? Int
                            }
                        }

                        override fun visitArray(name: String?): AnnotationVisitor {
                            val sink = mutableListOf<String>()
                            when (name) {
                                "d1" -> data1 = sink
                                "d2" -> data2 = sink
                            }
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(ignored: String?, value: Any?) {
                                    (value as? String)?.let { sink += it }
                                }
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )

        val resolvedKind = kind ?: return null
        return Metadata(
            kind = resolvedKind,
            metadataVersion = metadataVersion ?: intArrayOf(),
            data1 = data1?.toTypedArray() ?: emptyArray(),
            data2 = data2?.toTypedArray() ?: emptyArray(),
            extraString = extraString ?: "",
            packageName = packageName ?: "",
            extraInt = extraInt ?: 0,
        )
    }
}
