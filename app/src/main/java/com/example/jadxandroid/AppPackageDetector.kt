package com.example.jadxandroid

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import jadx.api.JavaClass
import java.io.File

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
        if (gameEngines.any { className == it || className.startsWith("$it.") }) return ClassCategory.GAME_ENGINE
        if (nativeBridges.any { className == it || className.startsWith("$it.") }) return ClassCategory.NATIVE_BRIDGE
        if (corePackages.any { className == it || className.startsWith("$it.") }) return ClassCategory.APP_CORE
        if (modulePackages.any { className == it || className.startsWith("$it.") }) return ClassCategory.APP_MODULE
        return ClassCategory.EXTERNAL_DEP
    }
}

object AppPackageDetector {

    private const val TAG = "AppPackageDetector"

    fun analyzeAppCode(context: Context, file: File, rawClasses: List<JavaClass>): AppCodeAnalysisResult {
        val manifestPkgs = HashSet<String>()
        val buildConfigPkgs = HashSet<String>()

        // 1. 读取 Manifest 组件包名 (权重最高 -> APP_CORE)
        try {
            val flags = PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS

            val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            if (archiveInfo != null) {
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

        // 2. 读取 BuildConfig 包名
        for (cls in rawClasses) {
            val fullName = cls.fullName
            if (fullName.endsWith(".BuildConfig") || fullName == "BuildConfig") {
                val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                    buildConfigPkgs.add(pkg)
                }
            }
        }

        // 组合 Core 候选包
        val coreCandidates = HashSet<String>()
        coreCandidates.addAll(manifestPkgs)
        coreCandidates.addAll(buildConfigPkgs)

        if (coreCandidates.isEmpty()) {
            for (cls in rawClasses) {
                val fullName = cls.fullName
                if (!FilterHelper.isResourceClass(fullName) && !FilterHelper.isThirdPartyLibrary(fullName)) {
                    val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                    if (pkg.isNotEmpty()) coreCandidates.add(pkg)
                }
            }
        }

        val coreRoots = findLongestCommonRoots(coreCandidates)

        // 识别 Module 候选包（如 moe.matsuri.nb4a，包含在 Manifest 但不为主根包）
        val moduleRoots = HashSet<String>()
        for (pkg in manifestPkgs) {
            if (coreRoots.none { root -> pkg == root || pkg.startsWith("$root.") }) {
                moduleRoots.add(pkg)
            }
        }

        // 识别动态引用的 Native / 游戏引擎 / 外部库依赖
        val (nativeBridges, gameEngines, externalDeps) = findReferencedLibraries(rawClasses, coreRoots + moduleRoots)

        return AppCodeAnalysisResult(
            corePackages = coreRoots,
            modulePackages = moduleRoots,
            nativeBridges = nativeBridges,
            gameEngines = gameEngines,
            externalDeps = externalDeps
        )
    }

    private fun findLongestCommonRoots(packages: Set<String>): Set<String> {
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
        rawClasses: List<JavaClass>,
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

        val appClasses = rawClasses.filter { cls ->
            appRoots.any { root -> cls.fullName == root || cls.fullName.startsWith("$root.") }
        }

        for (cls in appClasses) {
            try {
                val code = cls.code
                for (b in nativeBridgeCandidates) {
                    if (code.contains(b)) activeNative.add(b.removeSuffix("."))
                }
                for (e in gameEngineCandidates) {
                    if (code.contains(e)) activeEngine.add(e.removeSuffix("."))
                }
                for (ext in externalDepCandidates) {
                    if (code.contains(ext)) activeExternal.add(ext.removeSuffix("."))
                }
            } catch (e: Exception) {}
        }

        return Triple(activeNative, activeEngine, activeExternal)
    }
}
