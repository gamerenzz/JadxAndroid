package com.example.jadxandroid

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import jadx.api.JavaClass
import java.io.File

/**
 * 抽象的包分析输入模型，实现 JADX 和 CFR 的零差别对齐
 */
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
    val externalDeps: Set<String>
) {
    fun getAllAllowedPackages(): Set<String> {
        val all = HashSet<String>()
        all.addAll(corePackages)
        all.addAll(modulePackages)
        all.addAll(nativeBridges)
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

    /**
     * 针对 JADX 的分析入口
     */
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
                // 强化：提取 <application android:name="...">
                archiveInfo.applicationInfo?.name?.let { appName ->
                    val pkg = if (appName.contains(".")) appName.substringBeforeLast(".") else ""
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
                    val pkg = if (clsName.contains(".")) clsName.substringBeforeLast(".") else ""
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
                val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
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

        return analyze(input)
    }

    /**
     * 针对 CFR 的分析入口（使 CFR 共享完全相同的识别算法）
     */
    fun analyzeCfr(zipEntryNames: List<String>): AppCodeAnalysisResult {
        val classNames = zipEntryNames
            .filter { it.endsWith(".class") }
            .map { it.replace('/', '.').substringBeforeLast(".class") }
            .toSet()

        val candidatePkgs = classNames
            .filter { !FilterHelper.isResourceClass(it) && !FilterHelper.isThirdPartyLibrary(it) }
            .mapNotNull { if (it.contains(".")) it.substringBeforeLast(".") else null }
            .toSet()

        val input = AppPackageAnalysisInput(
            manifestPackages = emptySet(),
            buildConfigPackages = candidatePkgs.filter { it.endsWith(".BuildConfig") || it == "BuildConfig" }.toSet(),
            applicationPackages = emptySet(),
            allClassNames = classNames,
            classCodeProvider = { null }
        )

        return analyze(input)
    }

    /**
     * 核心集中分析逻辑
     */
    fun analyze(input: AppPackageAnalysisInput): AppCodeAnalysisResult {
        // Core 强候选：Application 类 + Manifest 组件 + BuildConfig
        val coreCandidates = HashSet<String>()
        coreCandidates.addAll(input.applicationPackages)
        coreCandidates.addAll(input.manifestPackages)
        coreCandidates.addAll(input.buildConfigPackages)

        if (coreCandidates.isEmpty()) {
            val candidatePkgs = input.allClassNames
                .filter { !FilterHelper.isResourceClass(it) && !FilterHelper.isThirdPartyLibrary(it) }
                .mapNotNull { if (it.contains(".")) it.substringBeforeLast(".") else null }
                .toSet()
            coreCandidates.addAll(candidatePkgs)
        }

        val coreRoots = findValidatedCommonRoots(coreCandidates)

        val moduleRoots = HashSet<String>()
        for (pkg in input.manifestPackages) {
            if (coreRoots.none { root -> pkg == root || pkg.startsWith("$root.") }) {
                moduleRoots.add(pkg)
            }
        }

        val (nativeBridges, gameEngines, externalDeps) = findReferencedLibraries(input, coreRoots + moduleRoots)

        return AppCodeAnalysisResult(
            corePackages = coreRoots,
            modulePackages = moduleRoots,
            nativeBridges = nativeBridges,
            gameEngines = gameEngines,
            externalDeps = externalDeps
        )
    }

    /**
     * 防过宽根包的公共包树前缀计算算法
     */
    private fun findValidatedCommonRoots(packages: Set<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()

        val validPkgs = packages.filter { !FilterHelper.isThirdPartyLibrary(it) }.distinct()
        if (validPkgs.isEmpty()) return emptySet()
        if (validPkgs.size == 1) return validPkgs.toSet()

        val splitPackages = validPkgs.map { it.split(".") }

        var commonPrefix = splitPackages[0]
        for (i in 1 until splitPackages.size) {
            val current = splitPackages[i]
            var j = 0
            while (j < commonPrefix.size && j < current.size && commonPrefix[j] == current[j]) {
                j++
            }
            commonPrefix = commonPrefix.take(j)
        }

        if (commonPrefix.size >= 2) {
            val commonRootStr = commonPrefix.joinToString(".")
            val remainingParts = validPkgs.mapNotNull { pkg ->
                if (pkg.startsWith("$commonRootStr.")) {
                    pkg.removePrefix("$commonRootStr.").split(".").firstOrNull()
                } else null
            }.distinct()

            return if (remainingParts.size == 1) {
                setOf("$commonRootStr.${remainingParts[0]}")
            } else {
                setOf(commonRootStr)
            }
        }

        val sortedPkgs = validPkgs.sortedBy { it.length }
        val rootSet = HashSet<String>()
        for (pkg in sortedPkgs) {
            if (rootSet.none { root -> pkg == root || pkg.startsWith("$root.") }) {
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
