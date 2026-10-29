plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.huanban.ar"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.huanban.ar"
        minSdk = 30
        targetSdk = 35
        versionCode = 10003
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Local delivery retains its existing debug identity. CI generates its own
    // standard ephemeral debug key; private signing material is never committed.
    signingConfigs { getByName("debug") {
        val localKey = rootProject.file("signing/huanban-debug.keystore")
        if (localKey.exists()) { storeFile = localKey; storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    } }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("io.github.sceneview:arsceneview:2.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

val preparePrivateModel by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(System.getenv("HUANBAN_PYTHON") ?: "python3", rootProject.file("scripts/materialize_private_model.py").absolutePath)
    inputs.dir(rootProject.file("private-model"))
    outputs.file(layout.projectDirectory.file("src/main/assets/models/user-default.7z"))
}
tasks.named("preBuild") { dependsOn(preparePrivateModel) }
