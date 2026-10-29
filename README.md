# 幻伴 AR / HuanBan AR

**当前版本 1.0.4：用户默认角色已打包，ZIP/7z直接导入及三模式实际渲染通过云端回归，VRM旧版角色已修正为正面对镜头。现代Blender、Live2D、MD原生工程仍未完整支持；K80与真实ARCore未实机验收。详见[验收报告](验收报告.md)。**

原生 Kotlin 安卓应用。包名 `com.huanban.ar`，Android 11/API 30 及以上，目标 API 35。APK 自带两种原创几何风格角色（青禾：普通人形；团子：Q 版），另按用户指示内置其提供的VRM归档作为AR默认角色；无需首次联网下载模型、注册账号或配置AI即可进入核心模式。界面为简体中文。

**验证结论以 `验收报告.md` 与 `evidence/` 中实际日志为准。构建成功不代表 K80 实机、ARCore 或所有第三方角色均已通过验收。**

## 使用

- **AR 模式**：授予摄像头权限。已确认 ARCore 安装且可用时使用真实平面追踪，移动手机检测平面，点击平面放置或重新放置角色；初始化失败自动转摄像头叠加。叠加模式明确显示“无空间锚定”，单指移动、双指缩放，也可通过按钮调大小和方向。
- **桌宠模式**：先进入系统设置授权“显示在其他应用上层”，再启动桌宠。拖动人物移动；轻触或点底部按钮展开菜单。支持放大、缩小、角色轮换、挥手、表情、隐藏、关闭及触摸穿透。隐藏/穿透后，在通知选择“恢复控制”。屏幕熄灭停止绘制；窗口大小、位置保存。HyperOS 后台策略仍由系统决定。
- **舞蹈模式**：单指调整摄像机、双指缩放；选择模型自带或兼容的内置骨骼动作，播放、暂停、循环、速度和 0.3 秒过渡。内置待机、环顾、挥手、轻舞、踏步、开心及眨眼。支持三种背景，高画质模式启用地面阴影和环境遮蔽。
- **角色管理**：直接选择 GLB/VRM；也可选择包含一个模型及关联资源的 ZIP/7z。支持 FBX/PMX/OBJ 原生转换，以及基础 PMD/XPS 解析；具体限制见下文。GLTF 使用目录导入：选择包含一个 `.gltf` 的目录，保持 `.bin`、PNG/JPEG 和子目录相对路径完整。导入完成后资源复制到应用私有目录，不再依赖原文件或长期文件权限。可改名、预览、调整比例和默认动作。所有角色共用 AR、桌宠、舞蹈加载路径。
- **人物对话**：默认明确标注“本地规则”，支持文字、本地记录及系统语音输入/TTS。语音服务缺失时保留文字操作。可选配置 HTTPS、兼容 OpenAI Chat Completions 的自有服务地址、模型和 Key。未内置付费接口。配置后的对话发送到所选服务器，Key 使用 Android Keystore/AES-GCM 加密。桌宠运行时，回复触发挥手动作。
- **截图与录制**：截图保存 `Pictures/HuanBanAR`；摄像头叠加截图合成相机和三维画面。视频使用系统授权的屏幕录制，保存 `Movies/HuanBanAR`，包含屏幕上可见界面，不包含声音；通知可停止。不是离线无损动画导出器。

## 模型兼容性边界

1. 基础格式：glTF 2.0/GLB、VRM 0/1 的基础 glTF/PBR 显示。纹理支持 PNG、JPEG。
2. VRM：读取 VRM 0/1 humanoid 骨骼映射、可识别的 morph 表情预设。**没有实现 MToon 专用渲染、弹簧骨骼、布料、物理碰撞、VRM LookAt/口型驱动。** 导入后明确提示基础材质回退。
3. 自带动画：保留原有骨骼与 morph 动画。无骨骼模型可显示、缩放、旋转、移动；动作功能按实际存在的动画限制。
4. 内置动作复用：识别 VRM 映射及常见英文/Mixamo 名称，检查四肢层次、矩阵/缩放和皮肤骨骼，再根据静止姿态的世界坐标轴转换为各骨骼局部旋转，并将 T 姿态手臂收回。不是任意骨架均可用的专业动捕重定向器；含不兼容矩阵、非标准层次或局部缩放时不强行套动作，保留基础显示。第三方模型兼容性仍需具体样本验证。
5. 新增格式：FBX、PMX、OBJ 经独立原生服务进程转换为 GLB；基础 PMD 1.0、XPS ASCII/部分二进制解析为带蒙皮网格。PNG/JPEG 漫反射纹理可解析；BMP/TGA/DDS、MMD IK/物理、专有着色器、复杂 XPS 材质和姿态覆盖未实现。当前仅有自建样本的转换和实际像素测试，不代表所有第三方模型兼容。
6. Blender 仅接入 Assimp 的旧版有限读取器，尚无真实 Blender 样本通过验证；现代 `.blend`、MD 原生服装工程、Live2D 尚未实现。网站的格式分类不等于已全部支持。
7. ZIP/7z：无需手动解压，最多三层嵌套、2048 条目、展开总量 64 MiB，拒绝路径穿越和重复文件。当前要求每包只含一个模型；多个模型会明确报错。关联资源需保留相对目录。压缩容器支持不会让不支持的内部格式自动可用。
8. 安全预算：单个模型加关联资源 ≤64 MiB，描述 JSON <8 MiB，节点 ≤2048、层级 <128，每皮肤 ≤256 骨骼，总顶点 ≤75 万，纹理 ≤64 张、单张 ≤4096²、总像素 ≤3200 万。不支持必需的 Draco/Meshopt、WebP/KTX2 等压缩扩展；请先转为未压缩 GLB、PNG/JPEG。禁止外部网络 URI、绝对路径和路径穿越。
9. 内置角色是原创低多边形几何人物，包含真实蒙皮和关节动画，并非动漫美术角色或静态贴图。

## 自用默认角色

用户提供的原始7z已按分块保存在该用户的私有仓库 `private-model/` 中。Gradle的 `preparePrivateModel` 在预构建时检查每块Git哈希与整包SHA-256，再重组为 `app/src/main/assets/models/user-default.7z`。该生成文件不会在首次启动从网络下载。

启动后保留原作者和授权元数据，将角色加入角色库；没有显式保存AR角色选择时，AR优先使用自用角色。导入角色可用于AR、舞蹈和桌宠。VRM 0.x以独立视图偏移修正默认朝向，用户旋转保持相对角度；不改写蒙皮、网格或动画数据。

该模型不适用本项目的MIT/CC0许可，其原始授权标记仍保留。当前仓库及相应模型构建用于用户指示的私有自用场景。

## 构建与测试

JDK 17、Android SDK platform 35、Build Tools 34、Gradle 8.9、NDK 27.2.12479018、CMake 3.22.1。首次构建还会下载固定 SHA-256 的 Assimp 6.0.2 源码，需访问 Google Maven、Maven Central、Gradle 和 GitHub。

```bash
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
# 可用 Android 测试设备/模拟器连接后：
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash scripts/run_device_tests.sh
```

Windows 使用 `gradlew.bat`。生成 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。调试 APK 已签名，可直接安装。签名密钥不提交到 GitHub。CI 使用自动生成的临时调试密钥，因此不同构建的签名可能不同，不能保证覆盖安装已有 APK。已有本地交付环境保留其调试签名；公开发布需独立保管正式签名。升级必须使用与现有安装相同的签名。

`scripts/run_verification.py` 按顺序执行完整构建并在进程结束后保存完整日志、退出码与耗时；支持本次云环境的临时代理，普通环境自动使用项目 Wrapper。`scripts/generate_models.py`、`generate_ground.py` 可重建原创资源。

`.github/workflows/android.yml` 提供 JDK/SDK 设置、Lint、单元测试、APK 构建、API 30/35 的界面及原生三维模拟器测试和日志/产物上传。此工作流已写入源码；是否在 GitHub 执行以验收报告为准，不能由文件存在推断执行成功。

## 代码结构

- `MainActivity`：首页、角色库、设置、聊天、系统文件选择。
- `StageActivity`：舞蹈/预览/真实 AR/相机叠加、手势、截图与录屏入口。
- `RenderEngine`：共享引擎、最后一个视图退出后释放、已知 SwiftShader 4 驱动故障检测。
- `RenderHost`：主线程 Filament 资源、生命周期、加载代次、防过期回调、动作交叉过渡、帧率控制。
- `ModelCodec`：有界解析、关联资源合并 GLB、引用/层次/几何检查。
- `ModelArchive`：有界 ZIP/7z 解压及关联资源管理。
- `NativeModelCodec` / `model_import.cpp`：独立进程 Assimp 转换，导入/导出文件访问均限制在本次模型目录内。
- `LegacyModelCodec`：基础 PMD/XPS 的几何、蒙皮和 PNG/JPEG 纹理转换。
- `RigMotion`：人形映射、姿态基准处理、骨骼动画及表情轨道。
- `RoleStore`：资源私有复制、原子索引更新、持久化和删除。
- `CameraBackdrop`：Camera2 后备预览、权限/占用处理、前后台释放。
- `PetService`：用户主动启动的 specialUse 前台服务、透明实时三维窗口、通知控制。
- `RecordingService`：用户授权的 mediaProjection 前台服务、H.264/MP4 和 MediaStore 保存。
- `ChatEngine`：明确标记的规则/AI 回复、本地对话、密钥加密、网络超时和响应上限。

单元测试和安卓测试在 `app/src/test`、`app/src/androidTest`。测试文档提供器仅打包在测试 APK，正式应用不导出此提供器。

## 授权

项目源码 MIT。两个人物和地面为本项目原创几何资源，CC0。第三方依赖及许可见 `THIRD_PARTY_NOTICES.md`。用户提供的自用默认模型另行保留其原作者、许可和使用范围元数据，不属于上述原创CC0资源。
