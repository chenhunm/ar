# 第三方组件

本项目的原创源码使用 MIT（见 LICENSE），内置模型 CC0（见 assets/models/LICENSE.txt）。

| 组件 | 固定版本 | 上游 | 许可 |
|---|---|---|---|
| SceneView / ARSceneView | 2.2.1 | https://github.com/SceneView/sceneview/tree/v2.2.1 | Apache-2.0 |
| Google Filament（SceneView 传递依赖） | 见 Gradle 依赖报告 | https://github.com/google/filament | Apache-2.0 |
| Google ARCore SDK（传递依赖） | 见 Gradle 依赖报告 | https://developers.google.com/ar | Apache-2.0 SDK；设备上的 Google Play Services for AR 受其服务条款约束 |
| AndroidX Activity | 1.9.3 | https://developer.android.com/jetpack/androidx/releases/activity | Apache-2.0 |
| AndroidX Lifecycle | 2.8.7 | https://developer.android.com/jetpack/androidx/releases/lifecycle | Apache-2.0 |
| Kotlin | 1.9.25 | https://github.com/JetBrains/kotlin | Apache-2.0 |
| JUnit（仅测试） | 4.13.2 | https://junit.org/junit4/ | EPL-1.0 |
| JSON-java（仅 JVM 测试） | 20240303 | https://github.com/stleary/JSON-java | Public Domain |
| AndroidX Test / UI Automator（仅测试） | 见 build.gradle.kts | https://developer.android.com/training/testing | Apache-2.0 |
| Gradle Wrapper | 8.9 | https://github.com/gradle/gradle | Apache-2.0 |

上游组件的许可、版权和 NOTICE 仍由对应组件所有者保留。APK 中包含 Android Gradle 工具生成的第三方许可/元数据；自用不改变相应再分发义务。未把 SDK 本身、模拟器镜像或下载工具打包进源码交付物。
