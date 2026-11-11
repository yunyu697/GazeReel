import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// ── 版本号：唯一真相源在根目录 version.properties ──────────────────────────
//
// 段位规则由这里守住：minor / patch 一旦超过 10 就直接让构建失败，
// 而不是悄悄发一个 1.11.11-alpha1 出去。
// 合法：11.10.10-alpha11；非法：1.11.11-alpha1
val versionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-alpha(\d+))?$""")
val versionText = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("version")?.trim()
    ?: error("version.properties 里缺少 version")

val versionMatch = versionRegex.matchEntire(versionText)
    ?: error("版本号格式错误：$versionText，应为 <major>.<minor>.<patch>[-alpha<N>]")

val vMajor = versionMatch.groupValues[1].toInt()
val vMinor = versionMatch.groupValues[2].toInt()
val vPatch = versionMatch.groupValues[3].toInt()
val vAlpha = versionMatch.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()

check(vMajor in 1..21) { "major 必须在 1..21（versionCode 是 32 位整数，再大会溢出）：$versionText" }
check(vMinor in 0..10) { "minor 不能超过 10（不能出现 11），请进位到 major：$versionText" }
check(vPatch in 0..10) { "patch 不能超过 10（不能出现 11），请进位到 minor：$versionText" }
check(vAlpha == null || vAlpha in 1..8999) { "alpha 编号必须在 1..8999：$versionText" }

val appVersionName = versionText

// versionCode 分段：major(1e8) | minor(1e6) | patch(1e4) | alpha 1..8999 或正式版 9000。
// 两件事靠这个分段保证：同一次构建单调递增；正式版永远大于它自己的全部 alpha。
val appVersionCode = (
    vMajor.toLong() * 100_000_000L +
        vMinor.toLong() * 1_000_000L +
        vPatch.toLong() * 10_000L +
        (vAlpha ?: 9000).toLong()
    )
    .also { check(it in 1L..Int.MAX_VALUE.toLong()) { "versionCode 溢出：$it" } }
    .toInt()

android {
    namespace = "com.gazereel.vr"
    compileSdk = 35

    // alpha 阶段的固定签名。CI 与本地必须用同一把：否则每次构建签名不同，
    // 覆盖安装会失败，用户只能卸载重装——片源配置、断点、格式记忆全丢。
    // 正式发布前换成真正的 release keystore，并把口令移出仓库。
    val alphaKeystore = rootProject.file("keystore/gazereel-alpha.jks")
    signingConfigs {
        if (alphaKeystore.exists()) {
            getByName("debug") {
                storeFile = alphaKeystore
                storePassword = "gazereel-alpha"
                keyAlias = "gazereel"
                keyPassword = "gazereel-alpha"
            }
        }
    }

    defaultConfig {
        applicationId = "com.gazereel.vr"
        // PRD 3.5：Android 8.0+。minSdk 26 同时保证 Media3 与 Room 的最低要求。
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // PRD 5.3 / 风险登记册：Cardboard SDK 已归档，只以本地 AAR 形式提供（native C API + Java 辅助类）。
    // 未放入 AAR 时，工程使用 SensorHeadsetProvider（纯 Android 传感器）仍可编译运行；
    // 放入 app/libs/cardboard-sdk.aar 后自动加入 SDK 适配层源码，二者实现同一个 VrHeadsetProvider 接口。
    val cardboardAar = file("libs/cardboard-sdk.aar")
    if (cardboardAar.exists()) {
        sourceSets.getByName("main").java.srcDir("src/cardboard/java")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

// ── 产物文件名：带上项目名与版本号 ──────────────────────────────────────
//
// 原来叫 app-debug.apk。装到手机上、或者在手机的「下载」目录里堆几个之后，
// 完全分不清哪个是哪个版本——出问题时也没法让用户报「你装的是哪一版」。
//
// 现在直接产出 GazeReel-1.0.0-alpha2.apk（非 debug 构建再加 -release 之类的后缀）。
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl
                ?: return@forEach
            val suffix = if (variant.name == "debug") "" else "-${variant.name}"
            impl.outputFileName.set("GazeReel-$appVersionName$suffix.apk")
        }
    }
}

dependencies {
    // 本地 AAR / JAR：Cardboard SDK 放这里即可（不存在时 fileTree 为空，不影响构建）。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 解码与缓冲（PRD 5.3 / 6.1）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB2/SMB3 随机读（PRD 6.3）。0.13.0 提供 File.read(buffer, fileOffset, ...) 与 File.getLength()。
    implementation(libs.smbj)

    // 片库索引、观看进度、格式覆盖表（PRD 7）
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}

// CI / 发版脚本读版本号用：./gradlew -q :app:printVersion
tasks.register("printVersion") {
    doLast {
        println(appVersionName)
    }
}
