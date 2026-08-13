package com.example.jadxandroid

enum class FilterMode(val displayName: String) {
    ALL("全部类 (未过滤)"),
    FILTER_THIRDPARTY("过滤常见第三方库"),
    APP_ONLY("App 自有业务代码 (⭐智能识别)")
}
