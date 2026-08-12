package com.example.jadxandroid

enum class FilterMode(val displayName: String) {
    ALL("全部类 (未过滤)"),
    FILTER_THIRDPARTY("过滤常见第三方库"),
    APP_ONLY("仅 App 主包 (⭐推荐)")
}
