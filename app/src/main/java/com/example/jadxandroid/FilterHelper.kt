package com.example.jadxandroid

object FilterHelper {

    // 1. 关键框架、游戏引擎及 Native/Go 桥接层白名单 (必须保留，否则 Native 调用链会挂)
    private val NATIVE_FRAMEWORK_WHITELIST = listOf(
        "go.",                             // Go 语言移动端绑定 (Gomobile)
        "libcore.",                        // SagerNet/V2Ray 内核 Java 桥接层
        "org.libsdl.app.",                 // SDL2 游戏引擎
        "com.unity3d.player.",             // Unity 引擎
        "org.cocos2dx.lib.",               // Cocos2d 引擎
        "com.epicgames.ue4.",              // 虚幻引擎
        "com.godot.game.",                 // Godot 引擎
        "com.github.shadowsocks.plugin.",  // 常用插件层
        "moe.matsuri.nb4a."                // 衍生代理模块
    )

    // 2. 精准第三方黑名单
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

    fun isResourceClass(className: String): Boolean {
        return className.contains("\$R\$") || 
               className.endsWith("\$R") || 
               className.contains(".R\$") || 
               className.endsWith(".R")
    }

    fun isNativeFrameworkClass(className: String): Boolean {
        return NATIVE_FRAMEWORK_WHITELIST.any { className.startsWith(it) }
    }

    fun isThirdPartyLibrary(className: String): Boolean {
        return THIRD_PARTY_BLACKLIST.any { className.startsWith(it) }
    }

    /**
     * 核心统一过滤决策函数，支持接收 AppCodeSet 集合匹配
     */
    fun shouldKeepClass(
        className: String,
        filterMode: FilterMode,
        appCodeSet: Set<String>
    ): Boolean {
        if (filterMode != FilterMode.ALL && isResourceClass(className)) {
            return false
        }

        return when (filterMode) {
            FilterMode.ALL -> true

            FilterMode.FILTER_THIRDPARTY -> {
                !isThirdPartyLibrary(className)
            }

            FilterMode.APP_ONLY -> {
                // 1. 保留 Native/Go 桥接层及框架基类
                if (isNativeFrameworkClass(className)) {
                    return true
                }
                // 2. 匹配业务代码包集合中的任意一个根包
                if (appCodeSet.isNotEmpty()) {
                    appCodeSet.any { rootPkg ->
                        className == rootPkg || className.startsWith("$rootPkg.")
                    }
                } else {
                    !isThirdPartyLibrary(className)
                }
            }
        }
    }
}
