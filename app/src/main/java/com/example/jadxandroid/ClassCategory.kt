package com.example.jadxandroid

enum class ClassCategory(val code: String, val displayName: String) {
    APP_CORE("APP_CORE", "主业务核心代码"),
    APP_MODULE("APP_MODULE", "应用二次开发/集成模块"),
    NATIVE_BRIDGE("NATIVE_BRIDGE", "Go/C++ 原生内核桥接层"),
    GAME_ENGINE("GAME_ENGINE", "游戏引擎/框架基类"),
    EXTERNAL_DEP("EXTERNAL_DEP", "关联第三方增强依赖库")
}
