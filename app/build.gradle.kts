plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
}

// Release signing comes only from the environment (GitHub Actions secrets), never from git.
// Without it, assembleRelease produces an unsigned APK and debug builds are unaffected.
// REQUIRE_RELEASE_SIGNING=true (set by the release workflow) turns missing signing into an error.
// See docs/coprocessor/ci-and-release.md.
val releaseKeystoreFile: File =
    System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { file(it) }
        ?: rootProject.file("keystore.jks")
val releaseSigningEnv =
    listOf("RELEASE_KEYSTORE_PASSWORD", "RELEASE_KEYSTORE_ALIAS", "RELEASE_KEY_PASSWORD")
        .associateWith { System.getenv(it).orEmpty() }
val hasReleaseSigning = releaseKeystoreFile.isFile && releaseSigningEnv.values.all { it.isNotEmpty() }
if (System.getenv("REQUIRE_RELEASE_SIGNING") == "true" && !hasReleaseSigning) {
    throw GradleException(
        "Release signing required but missing: keystore file " +
            (if (releaseKeystoreFile.isFile) "found" else "not found") + ", empty variables: " +
            releaseSigningEnv.filterValues { it.isEmpty() }.keys,
    )
}

// Release APKs contain only arm64-v8a native libs (the Xperia 5 III and every 64-bit ARM phone).
// `-PuniversalRelease=true` builds a release with every ABI the dependencies ship (emulators, x86).
// Debug builds are never filtered, so they still run on x86_64 emulators.
val universalRelease = (project.findProperty("universalRelease") as String?)?.toBoolean() == true

android {
    namespace = "com.ml.shubham0204.facenet_android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ml.shubham0204.facenet_android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningEnv.getValue("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("RELEASE_KEYSTORE_ALIAS")
                keyPassword = releaseSigningEnv.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            if (!universalRelease) {
                ndk { abiFilters += "arm64-v8a" }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    applicationVariants.configureEach {
        kotlin.sourceSets {
            getByName(name) {
                kotlin.srcDir("build/generated/ksp/$name/kotlin")
            }
        }
    }
}

ksp {
    arg("KOIN_CONFIG_CHECK", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.compose.material3.icons.extended)
    implementation(libs.androidx.compose.navigation)
    implementation(libs.androidx.ui.text.google.fonts)

    // ObjectBox - vector database
    debugImplementation("io.objectbox:objectbox-android-objectbrowser:5.4.2")
    releaseImplementation("io.objectbox:objectbox-android:5.4.2")

    // dependency injection
    implementation(libs.koin.android)
    implementation(libs.koin.annotations)
    implementation(libs.koin.androidx.compose)
    ksp(libs.koin.ksp.compiler)

    // ExecuTorch with XNNPACK (CPU) + Vulkan (GPU) delegates. Same Java API as executorch-android;
    // backend AARs cannot be combined (duplicate classes), so QNN would need a custom AAR.
    implementation("org.pytorch:executorch-android-vulkan:1.2.0")

    // LiteRT
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)
    implementation(libs.tensorflow.lite.support)

    // DocumentFile and ExitInterface
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    // Kotlin Coil
    implementation(libs.coil)
    implementation(libs.coil.compose)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // MLKit Face Detection
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Ktor embedded server (coprocessor REST API)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)

    // JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.ktor.server.test.host)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

apply(plugin = "io.objectbox")

tasks.register("downloadModel") {
    val modelUrl = "https://huggingface.co/shubhxm0204/facenet-executorch/resolve/main/vggface2-inception-resnetv1-xnnpack-fp32"
    val targetFile = file("src/main/assets/model.pte")
    outputs.file(targetFile)
    doLast {
        if (!targetFile.exists()) {
            println("Downloading model from $modelUrl...")
            targetFile.parentFile.mkdirs()
            ant.invokeMethod("get", mapOf("src" to modelUrl, "dest" to targetFile))
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadModel")
}
