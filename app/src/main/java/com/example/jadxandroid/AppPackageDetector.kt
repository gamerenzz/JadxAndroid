package com.example.jadxandroid

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import jadx.api.JavaClass
import java.io.File

data class AppPackageAnalysisInput(
    val manifestPackages: Set<String>,
    val buildConfigPackages: Set<String>,
    val applicationPackages: Set<String>,
    val allClassNames: Set<String>,
    val classCodeProvider: (className: String) -> String?
)

data class AppCodeAnalysisResult(
    val corePackages: Set<String>,
    val modulePackages: Set<String>,
    val nativeBridges: Set<String>,
    val gameEngines: Set<String>,
    val externalDeps: Set<String>,
    val rawClassesCount: Int = 0
) {
    fun getAppOwnedPackages(): Set<String> {
        val appOwned = HashSet<String>()
        appOwned.addAll(corePackages)
        appOwned.addAll(modulePackages)
        appOwned.addAll(nativeBridges)
        return appOwned
    }

    fun getAllAllowedPackages(): Set<String> {
        val all = HashSet<String>()
        all.addAll(getAppOwnedPackages())
        all.addAll(gameEngines)
        all.addAll(externalDeps)
        return all
    }

    fun classify(className: String): ClassCategory {
        if (corePackages.any { className == it || className.startsWith("$it.") }) return ClassCategory.APP_CORE
        if (modulePackages.any { className == it || className.startsWith("$it.") }) return ClassCategory.APP_MODULE
        if (nativeBridges.any { className == it || className.startsWith("$it.") }) return ClassCategory.NATIVE_BRIDGE
        if (gameEngines.any { className == it || className.startsWith("$it.") }) return ClassCategory.GAME_ENGINE
        if (externalDeps.any { className == it || className.startsWith("$it.") }) return ClassCategory.EXTERNAL_DEP
        return ClassCategory.UNKNOWN
    }
}

object AppPackageDetector {

    private const val TAG = "AppPackageDetector"
    private val FORBIDDEN_TOP_LEVEL_DOMAINS = setOf("com", "org", "net", "io", "cn", "uk", "de", "zh", "jp", "edu", "gov")

    fun analyzeJadx(context: Context, file: File, rawClasses: List<JavaClass>): AppCodeAnalysisResult {
        val manifestPkgs = HashSet<String>()
        val applicationPkgs = HashSet<String>()
        val buildConfigPkgs = HashSet<String>()

        try {
            val flags = PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS

            val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            if (archiveInfo != null) {
                val appPkg = archiveInfo.packageName ?: ""

                archiveInfo.applicationInfo?.name?.let { appName ->
                    val fullClsName = when {
                        appName.startsWith(".") -> "$appPkg$appName"
                        !appName.contains(".") -> "$appPkg.$appName"
                        else -> appName
                    }
                    val pkg = fullClsName.substringBeforeLast(".", "")
                    if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                        applicationPkgs.add(pkg)
                    }
                }

                val components = ArrayList<String>()
                archiveInfo.activities?.forEach { components.add(it.name) }
                archiveInfo.services?.forEach { components.add(it.name) }
                archiveInfo.receivers?.forEach { components.add(it.name) }
                archiveInfo.providers?.forEach { components.add(it.name) }

                for (clsName in components) {
                    val fullClsName = when {
                        clsName.startsWith(".") -> "$appPkg$clsName"
                        !clsName.contains(".") -> "$appPkg.$clsName"
                        else -> clsName
                    }
                    val pkg = fullClsName.substringBeforeLast(".", "")
                    if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                        manifestPkgs.add(pkg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Manifest 异常: ${e.localizedMessage}")
        }

        for (cls in rawClasses) {
            val fullName = cls.fullName
            if (fullName.endsWith(".BuildConfig") || fullName == "BuildConfig") {
                val pkg = fullName.substringBeforeLast(".", "")
                if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                    buildConfigPkgs.add(pkg)
                }
            }
        }

        val allClassNames = rawClasses.map { it.fullName }.toSet()
        val classMap = rawClasses.associateBy { it.fullName }

        val input = AppPackageAnalysisInput(
            manifestPackages = manifestPkgs,
            buildConfigPackages = buildConfigPkgs,
            applicationPackages = applicationPkgs,
            allClassNames = allClassNames,
            classCodeProvider = { className -> classMap[className]?.code }
        )

        return analyze(input, rawClasses.size)
    }

    fun analyzeCfr(zipEntryNames: List<String>): AppCodeAnalysisResult {
        val classNames = zipEntryNames
            .filter { it.endsWith(".class") }
            .map { it.replace('/', '.').substringBeforeLast(".class") }
            .toSet()

        val buildConfigPkgs = classNames
            .filter { it.endsWith(".BuildConfig") || it == "BuildConfig" }
            .mapNotNull { if (it.contains(".")) it.substringBeforeLast(".") else null }
            .filter { !FilterHelper.isThirdPartyLibrary(it) }
            .toSet()

        val input = AppPackageAnalysisInput(
            manifestPackages = emptySet(),
            buildConfigPackages = buildConfigPkgs,
            applicationPackages = emptySet(),
            allClassNames = classNames,
            classCodeProvider = { null }
        )

        return analyze(input, classNames.size)
    }

    private fun analyze(input: AppPackageAnalysisInput, rawClassesCount: Int): AppCodeAnalysisResult {
        val packageScores = HashMap<String, Int>()

        for (pkg in input.applicationPackages) {
            packageScores[pkg] = (packageScores[pkg] ?: 0) + 100
        }

        for (pkg in input.buildConfigPackages) {
            packageScores[pkg] = (packageScores[pkg] ?: 0) + 80
        }

        for (pkg in input.manifestPackages) {
            packageScores[pkg] = (packageScores[pkg] ?: 0) + 50
        }

        for (clsName in input.allClassNames) {
            if (!FilterHelper.isResourceClass(clsName) && !FilterHelper.isThirdPartyLibrary(clsName)) {
                val pkg = clsName.substringBeforeLast(".", "")
                if (pkg.isNotEmpty()) {
                    packageScores[pkg] = (packageScores[pkg] ?: 0) + 1
                }
            }
        }

        val coreCandidates = packageScores.filter { it.value >= 80 }.keys.toHashSet()
        val moduleCandidates = packageScores.filter { it.value in 40..79 }.keys.toHashSet()

        if (coreCandidates.isEmpty() && packageScores.isNotEmpty()) {
            val topPkg = packageScores.maxByOrNull { it.value }?.key
            if (topPkg != null) coreCandidates.add(topPkg)
        }

        val coreRoots = findValidatedCommonRoots(coreCandidates)
        val moduleRoots = findValidatedCommonRoots(moduleCandidates).filter { mod ->
            coreRoots.none { core -> mod == core || mod.startsWith("$core.") }
        }.toSet()

        val (nativeBridges, gameEngines, externalDeps) = findReferencedLibraries(input, coreRoots + moduleRoots)

        return AppCodeAnalysisResult(
            corePackages = coreRoots,
            modulePackages = moduleRoots,
            nativeBridges = nativeBridges,
            gameEngines = gameEngines,
            externalDeps = externalDeps,
            rawClassesCount = rawClassesCount
        )
    }

    /**
     * 精准包树根节点提取（严格遵循“宁可精简，严禁过宽”原则）：
     * 严禁将 com.abc.app 和 com.abc.sdk 这类 2~3 级兄弟包向上过宽过度合并为 com.abc
     */
    private fun findValidatedCommonRoots(packages: Set<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()

        val validPkgs = packages.filter { pkg ->
            !FilterHelper.isThirdPartyLibrary(pkg) && !FORBIDDEN_TOP_LEVEL_DOMAINS.contains(pkg)
        }.distinct()

        if (validPkgs.isEmpty()) return emptySet()
        if (validPkgs.size == 1) return validPkgs.toSet()

        val sortedPkgs = validPkgs.sortedBy { it.length }
        val rootSet = HashSet<String>()

        for (pkg in sortedPkgs) {
            val parts = pkg.split(".")
            // 仅当包名深度 >= 3 (如 io.nekohasekai.sagernet) 时，才允许作为根节点收拢其明确的子包
            if (parts.size >= 3) {
                if (rootSet.none { root -> pkg == root || pkg.startsWith("$root.") }) {
                    rootSet.add(pkg)
                }
            } else {
                // 对于 2 级包 (如 com.wangwu)，保持独立隔离，严禁合并兄弟包
                rootSet.add(pkg)
            }
        }
        return rootSet
    }

    private fun findReferencedLibraries(
        input: AppPackageAnalysisInput,
        appRoots: Set<String>
    ): Triple<Set<String>, Set<String>, Set<String>> {
        val nativeBridgeCandidates = listOf("go.", "libcore.")
        val gameEngineCandidates = listOf(
            "org.libsdl.app.",
            "com.unity3d.player.",
            "org.cocos2dx.lib.",
            "com.epicgames.ue4.",
            "com.godot.game."
        )
        val externalDepCandidates = listOf(
            "com.github.shadowsocks.plugin.",
            "com.jakewharton.processphoenix."
        )

        val activeNative = HashSet<String>()
        val activeEngine = HashSet<String>()
        val activeExternal = HashSet<String>()

        val appClasses = input.allClassNames.filter { clsName ->
            appRoots.any { root -> clsName == root || clsName.startsWith("$root.") }
        }

        for (clsName in appClasses) {
            val code = input.classCodeProvider(clsName) ?: continue
            for (b in nativeBridgeCandidates) {
                if (code.contains(b)) activeNative.add(b.removeSuffix("."))
            }
            for (e in gameEngineCandidates) {
                if (code.contains(e)) activeEngine.add(e.removeSuffix("."))
            }
            for (ext in externalDepCandidates) {
                if (code.contains(ext)) activeExternal.add(ext.removeSuffix("."))
            }
        }

        return Triple(activeNative, activeEngine, activeExternal)
    }
}
