package app.revanced.cli.command

import app.revanced.lib.ApkUtils
import app.revanced.lib.Options
import app.revanced.lib.Options.setOptions
import app.revanced.lib.adb.AdbManager
import app.revanced.lib.signing.SigningOptions
import app.revanced.patcher.PatchBundleLoader
import app.revanced.patcher.PatchSet
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Help.Visibility.ALWAYS
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Logger


@CommandLine.Command(
    name = "patch", description = ["Patch an APK file"]
)
internal object PatchCommand : Runnable {
    private val logger = Logger.getLogger(PatchCommand::class.java.name)

    @Spec
    lateinit var spec: CommandSpec // injected by picocli

    private lateinit var apk: File

    private var integrations = listOf<File>()

    private var patchBundles = emptyList<File>()

    @CommandLine.Option(
        names = ["-i", "--include"], description = ["List of patches to include"]
    )
    private var includedPatches = arrayOf<String>()

    @CommandLine.Option(
        names = ["-e", "--exclude"], description = ["List of patches to exclude"]
    )
    private var excludedPatches = arrayOf<String>()

    @CommandLine.Option(
        names = ["--options"], description = ["Path to patch options JSON file"], showDefaultValue = ALWAYS
    )
    private var optionsFile: File = File("options.json")

    @CommandLine.Option(
        names = ["--exclusive"],
        description = ["Only include patches that are explicitly specified to be included"],
        showDefaultValue = ALWAYS
    )
    private var exclusive = false

    @CommandLine.Option(
        names = ["-f","--force"],
        description = ["Force inclusion of patches that are incompatible with the supplied APK file's version"],
        showDefaultValue = ALWAYS
    )
    private var force: Boolean = false

    @CommandLine.Option(
        names = ["-o", "--out"], description = ["Path to save the patched APK file to"], required = true
    )
    private lateinit var outputFilePath: File

    @CommandLine.Option(
        names = ["-d", "--device-serial"], description = ["ADB device serial to install to"], showDefaultValue = ALWAYS
    )
    private var deviceSerial: String? = null

    @CommandLine.Option(
        names = ["--mount"], description = ["Install by mounting the patched APK file"], showDefaultValue = ALWAYS
    )
    private var mount: Boolean = false

    @CommandLine.Option(
        names = ["--common-name"],
        description = ["The common name of the signer of the patched APK file"],
        showDefaultValue = ALWAYS

    )
    private var commonName = "ReVanced"

    @CommandLine.Option(
        names = ["--keystore"], description = ["Path to the keystore to sign the patched APK file with"]
    )
    private var keystoreFilePath: File? = null

    @CommandLine.Option(
        names = ["--password"], description = ["The password of the keystore to sign the patched APK file with"]
    )
    private var password = "ReVanced"

    @CommandLine.Option(
        names = ["-r", "--resource-cache"],
        description = ["Path to temporary resource cache directory"],
        showDefaultValue = ALWAYS
    )
    private var resourceCachePath = File("revanced-resource-cache")

    private var aaptBinaryPath: File? = null

    @CommandLine.Option(
        names = ["-p", "--purge"],
        description = ["Purge the temporary resource cache directory after patching"],
        showDefaultValue = ALWAYS
    )
    private var purge: Boolean = false

    @CommandLine.Parameters(
        description = ["APK file to be patched"], arity = "1..1"
    )
    @Suppress("unused")
    private fun setApk(apk: File) {
        if (!apk.exists()) throw CommandLine.ParameterException(
            spec.commandLine(),
            "APK file ${apk.name} does not exist"
        )
        this.apk = apk
    }

    @CommandLine.Option(
        names = ["-m", "--merge"], description = ["One or more DEX files or containers to merge into the APK"]
    )
    @Suppress("unused")
    private fun setIntegrations(integrations: Array<File>) {
        integrations.firstOrNull { !it.exists() }?.let {
            throw CommandLine.ParameterException(spec.commandLine(), "Integrations file ${it.name} does not exist")
        }
        this.integrations += integrations
    }

    @CommandLine.Option(
        names = ["-b", "--patch-bundle"], description = ["One or more bundles of patches"], required = true
    )
    @Suppress("unused")
    private fun setPatchBundles(patchBundles: Array<File>) {
        patchBundles.firstOrNull { !it.exists() }?.let {
            throw CommandLine.ParameterException(spec.commandLine(), "Patch bundle ${it.name} does not exist")
        }
        this.patchBundles = patchBundles.toList()
    }

    @CommandLine.Option(
        names = ["--custom-aapt2-binary"], description = ["Path to a custom AAPT binary to compile resources with"]
    )
    @Suppress("unused")
    private fun setAaptBinaryPath(aaptBinaryPath: File) {
        if (!aaptBinaryPath.exists()) throw CommandLine.ParameterException(
            spec.commandLine(),
            "AAPT binary ${aaptBinaryPath.name} does not exist"
        )
        this.aaptBinaryPath = aaptBinaryPath
    }

    override fun run() {
        val adbManager = deviceSerial?.let { serial -> AdbManager.getAdbManager(serial, mount) }

        // region Load patches

        logger.info("Loading patches")

        val patches = PatchBundleLoader.Jar(*patchBundles.toTypedArray())

        logger.info("Setting patch options")

        optionsFile.let {
            if (it.exists()) patches.setOptions(it)
            else Options.serialize(patches, prettyPrint = true).let(it::writeText)
        }

        // endregion

        Patcher(
            PatcherOptions(
                apk,
                resourceCachePath,
                aaptBinaryPath?.path,
                resourceCachePath.absolutePath,
            )
        ).use { patcher ->
            // region Patch

            val patcherResult = patcher.apply {
                acceptIntegrations(integrations)
                acceptPatches(filterPatchSelection(patches))

                // Execute patches.
                runBlocking {
                    apply(false).collect { patchResult ->
                        patchResult.exception?.let {
                            StringWriter().use { writer ->
                                it.printStackTrace(PrintWriter(writer))
                                logger.severe("${patchResult.patch.name} failed:\n$writer")
                            }
                        } ?: logger.info("${patchResult.patch.name} succeeded")
                    }
                }
            }.get()

            // endregion

            // region Save

            val tempFile = resourceCachePath.resolve(apk.name)
            ApkUtils.copyAligned(apk, tempFile, patcherResult)
            if (!mount) ApkUtils.sign(
                tempFile,
                outputFilePath,
                SigningOptions(
                    commonName,
                    password,
                    keystoreFilePath ?: outputFilePath.absoluteFile.parentFile
                        .resolve("${outputFilePath.nameWithoutExtension}.keystore"),
                )
            )

            // endregion

            // region Install

            adbManager?.install(AdbManager.Apk(outputFilePath, patcher.context.packageMetadata.packageName))

            // endregion
        }

        if (purge) {
            logger.info("Purging temporary files")
            purge(resourceCachePath)
        }
    }


    /**
     * Filter the patches to be added to the patcher. The filter is based on the following:
     * - [includedPatches] (explicitly included)
     * - [excludedPatches] (explicitly excluded)
     * - [exclusive] (only include patches that are explicitly included)
     * - [force] (ignore patches incompatibility to versions)
     * - Package name and version of the input APK file (if [force] is false)
     *
     * @param patches The patches to filter.
     * @return The filtered patches.
     */
    private fun Patcher.filterPatchSelection(patches: PatchSet) = buildList {
        // TODO: Remove this eventually because
        //  patches named "patch-name" and "patch name" will conflict.
        fun String.format() = lowercase().replace(" ", "-")

        val formattedExcludedPatches = excludedPatches.map { it.format() }
        val formattedIncludedPatches = includedPatches.map { it.format() }

        val packageName = context.packageMetadata.packageName
        val packageVersion = context.packageMetadata.packageVersion

        patches.forEach patch@{ patch ->
            val patchName = patch.name!!
            val formattedPatchName = patchName.format()

            val explicitlyExcluded = formattedExcludedPatches.contains(formattedPatchName)
            if (explicitlyExcluded) return@patch logger.info("Excluding $patchName")

            // Make sure the patch is compatible with the supplied APK files package name and version.
            patch.compatiblePackages?.let { packages ->
                packages.singleOrNull { it.name == packageName }?.let { `package` ->
                    val matchesVersion = force || `package`.versions?.let {
                        it.any { version -> version == packageVersion }
                    } ?: true

                    if (!matchesVersion) return@patch logger.warning(
                        "$patchName is incompatible with version $packageVersion. "
                                + "This patch is only compatible with version "
                                + packages.joinToString(";") { pkg ->
                            "${pkg.name}: ${pkg.versions!!.joinToString(", ")}"
                        }
                    )
                } ?: return@patch logger.fine(
                    "$patchName is incompatible with $packageName. "
                        + "This patch is only compatible with "
                        + packages.joinToString(", ") { `package` -> `package`.name })

                return@let
            } ?: logger.fine("$formattedPatchName: No constraint on packages.")

            // If the patch is implicitly used, it will be only included if [exclusive] is false.
            val implicitlyIncluded = !exclusive && patch.use
            // If the patch is explicitly used, it will be included even if [exclusive] is false.
            val explicitlyIncluded = formattedIncludedPatches.contains(formattedPatchName)

            val included = implicitlyIncluded || explicitlyIncluded
            if (!included) return@patch logger.info("$patchName excluded by default") // Case 1.

            logger.fine("Adding $formattedPatchName")

            add(patch)
        }
    }

    private fun purge(resourceCachePath: File) {
        val result = if (resourceCachePath.deleteRecursively()) "Purged resource cache directory"
        else "Failed to purge resource cache directory"
        logger.info(result)
    }
}