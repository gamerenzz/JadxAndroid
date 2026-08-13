package com.example.jadxandroid

object FilterHelper {

    // 精准第三方黑名单
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

    fun isThirdPartyLibrary(className: String): Boolean {
        return THIRD_PARTY_BLACKLIST.any { className.startsWith(it) }
    }

    /**
     * 核心统一过滤决策函数
     */
    fun shouldKeepClass(
        className: String,
        filterMode: FilterMode,
        appCodeSet: Set<String>
    ): Boolean {
        // 在非 ALL 模式下，过滤 R 资源干扰类
        if (filterMode != FilterMode.ALL && isResourceClass(className)) {
            return false
        }

        return when (filterMode) {
            FilterMode.ALL -> true

            FilterMode.FILTER_THIRDPARTY -> {
                !isThirdPartyLibrary(className)
            }

            FilterMode.APP_ONLY -> {
                if (appCodeSet.isNotEmpty()) {
                    // 匹配应用业务代码根包以及动态引用的框架依赖包
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
