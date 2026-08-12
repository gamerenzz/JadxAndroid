package com.example.jadxandroid

object FilterHelper {

    // 1. 知名游戏引擎与关键框架白名单 (即使 APP_ONLY 模式也必须保留，防止继承链断裂)
    private val GAME_FRAMEWORK_WHITELIST = listOf(
        "org.libsdl.app.",
        "com.unity3d.player.",
        "org.cocos2dx.lib.",
        "com.epicgames.ue4.",
        "com.godot.game."
    )

    // 2. 精准第三方库黑名单 (避免粗暴的前缀误杀)
    private val THIRD_PARTY_BLACKLIST = listOf(
        "android.",
        "androidx.",
        "com.google.android.material.",
        "com.google.android.gms.",
        "com.google.firebase.",
        "com.google.protobuf.",
        "kotlin.",
        "kotlinx.",
        "org.apache.",
        "org.intellij.",
        "org.jetbrains.",
        "com.squareup.",
        "io.reactivex.",
        "com.bumptech.glide.",
        "com.google.gson.",
        "com.alibaba.fastjson.",
        "com.tencent.bugly.",
        "com.tencent.stat.",
        "com.tencent.mm.opensdk."
    )

    /**
     * 判断是否为 R 资源类/内部类 (如 R$drawable, R$string)
     * 这些纯属资源 ID 映射，反编译代码分析时属于强噪音，需要过滤
     */
    fun isResourceClass(className: String): Boolean {
        return className.contains("\$R\$") || 
               className.endsWith("\$R") || 
               className.contains(".R\$") || 
               className.endsWith(".R")
    }

    /**
     * 判断是否属于游戏引擎/关键框架的保留类
     */
    fun isGameFrameworkClass(className: String): Boolean {
        return GAME_FRAMEWORK_WHITELIST.any { className.startsWith(it) }
    }

    /**
     * 判断是否为第三方库
     */
    fun isThirdPartyLibrary(className: String): Boolean {
        return THIRD_PARTY_BLACKLIST.any { className.startsWith(it) }
    }

    /**
     * 核心统一过滤决策函数
     */
    fun shouldKeepClass(
        className: String,
        filterMode: FilterMode,
        appPackageName: String?
    ): Boolean {
        // 在非 ALL 模式下，永远过滤 R 资源无用噪音类
        if (filterMode != FilterMode.ALL && isResourceClass(className)) {
            return false
        }

        return when (filterMode) {
            FilterMode.ALL -> true

            FilterMode.FILTER_THIRDPARTY -> {
                !isThirdPartyLibrary(className)
            }

            FilterMode.APP_ONLY -> {
                // 1. 优先保留游戏引擎/关键框架基类 (如 SDLActivity)
                if (isGameFrameworkClass(className)) {
                    return true
                }
                // 2. 匹配应用主包
                if (!appPackageName.isNullOrEmpty()) {
                    className == appPackageName || className.startsWith("$appPackageName.")
                } else {
                    !isThirdPartyLibrary(className)
                }
            }
        }
    }
}
