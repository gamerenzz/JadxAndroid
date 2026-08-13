package com.example.jadxandroid

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import jadx.api.JavaClass
import java.io.File

object AppPackageDetector {

    private const val TAG = "AppPackageDetector"

    /**
     * 智能识别 App 自有业务包集合 (动态公共包树算法 + 引用闭包分析)
     */
    fun detectAppCodeSet(context: Context, file: File, rawClasses: List<JavaClass>): Set<String> {
        val rawCandidatePackages = HashSet<String>()

        // 策略 1：读取 Manifest 组件真实包名 (Activity, Service, Receiver, Provider)
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
                        rawCandidatePackages.add(pkg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Manifest 异常: ${e.localizedMessage}")
        }

        // 策略 2：扫描所有 *.BuildConfig 所在源码包名
        for (cls in rawClasses) {
            val fullName = cls.fullName
            if (fullName.endsWith(".BuildConfig") || fullName == "BuildConfig") {
                val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                if (pkg.isNotEmpty() && !FilterHelper.isThirdPartyLibrary(pkg)) {
                    rawCandidatePackages.add(pkg)
                }
            }
        }

        // 策略 3：兜底 - 若 Manifest/BuildConfig 均无结果，收集非第三方类的包名
        if (rawCandidatePackages.isEmpty()) {
            for (cls in rawClasses) {
                val fullName = cls.fullName
                if (!FilterHelper.isResourceClass(fullName) && !FilterHelper.isThirdPartyLibrary(fullName)) {
                    val pkg = if (fullName.contains(".")) fullName.substringBeforeLast(".") else ""
                    if (pkg.isNotEmpty()) rawCandidatePackages.add(pkg)
                }
            }
        }

        // 核心算法 A：动态公共前缀包树算法 (彻底废除 parts.take(3) 硬编码)
        val appRoots = findLongestCommonRoots(rawCandidatePackages)

        // 核心算法 B：引用闭包分析 (仅当 App 业务代码真实引用了 Native/框架包时才保留)
        val dynamicBridges = findReferencedBridges(rawClasses, appRoots)

        val finalCodeSet = HashSet<String>()
        finalCodeSet.addAll(appRoots)
        finalCodeSet.addAll(dynamicBridges)

        Log.i(TAG, "计算出最长公共根包: $appRoots | 动态引用扩展依赖: $dynamicBridges")
        return finalCodeSet
    }

    /**
     * 核心算法：计算最长公共包名树 (不依赖任何写死截取层级)
     * 例 A: [io.nekohasekai.sagernet.ui, io.nekohasekai.sagernet.bg, io.nekohasekai.sagernet.database]
     *      -> 自动计算出 io.nekohasekai.sagernet
     * 例 B: [com.wangwu.jymod52, com.wangwu.jymod52.ui]
     *      -> 自动计算出 com.wangwu.jymod52
     */
    private fun findLongestCommonRoots(packages: Set<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()

        val validPkgs = packages.filter { !FilterHelper.isThirdPartyLibrary(it) }.distinct()
        if (validPkgs.isEmpty()) return emptySet()
        if (validPkgs.size == 1) return validPkgs.toSet()

        // 拆分为层级数组
        val splitPackages = validPkgs.map { it.split(".") }

        // 寻找所有候选包的最长公共前缀数组
        var commonPrefix = splitPackages[0]
        for (i in 1 until splitPackages.size) {
            val current = splitPackages[i]
            var j = 0
            while (j < commonPrefix.size && j < current.size && commonPrefix[j] == current[j]) {
                j++
            }
            commonPrefix = commonPrefix.take(j)
        }

        // 如果提取出的公共根包层级 >= 2 (如 io.nekohasekai 或 com.wangwu)
        if (commonPrefix.size >= 2) {
            val commonRootStr = commonPrefix.joinToString(".")
            
            // 进一步检查：如果所有包在该前缀之后紧跟相同的第三/四段，自动向下延伸合并
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

        // 兜底方案：按包名长度升序去重子包
        val sortedPkgs = validPkgs.sortedBy { it.length }
        val rootSet = HashSet<String>()
        for (pkg in sortedPkgs) {
            if (rootSet.none { root -> pkg == root || pkg.startsWith("$root.") }) {
                rootSet.add(pkg)
            }
        }
        return rootSet
    }

    /**
     * 动态引用闭包分析：检查 App 业务代码源码中是否真实 import/引用了特定 Native/框架包
     */
    private fun findReferencedBridges(rawClasses: List<JavaClass>, appRoots: Set<String>): Set<String> {
        val candidateBridges = listOf(
            "go.",
            "libcore.",
            "org.libsdl.app.",
            "com.unity3d.player.",
            "org.cocos2dx.lib.",
            "com.epicgames.ue4.",
            "com.github.shadowsocks.plugin.",
            "moe.matsuri.nb4a."
        )

        val activeBridges = HashSet<String>()
        val appClasses = rawClasses.filter { cls ->
            appRoots.any { root -> cls.fullName == root || cls.fullName.startsWith("$root.") }
        }

        // 快速扫描应用业务源码内容
        for (cls in appClasses) {
            try {
                val code = cls.code
                for (bridge in candidateBridges) {
                    if (code.contains(bridge)) {
                        activeBridges.add(bridge.removeSuffix("."))
                    }
                }
            } catch (e: Exception) {
                // 忽略个别类源码读取异常
            }
        }
        return activeBridges
    }
}
