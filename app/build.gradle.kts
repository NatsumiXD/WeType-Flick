import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class VerifyApkSize : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        check(apks.isNotEmpty()) { "Release APK not found" }
        for (apk in apks) {
            check(apk.length() < 10_000_000L) { "${apk.name} is ${apk.length()} bytes; must be below 10 MB" }
            logger.lifecycle("APK size verified: ${apk.name} = ${apk.length()} bytes (< 10 MB)")
        }
    }
}



androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val sizeCheck = tasks.register<VerifyApkSize>("verifyReleaseApkSize") {
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
    }
    tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(sizeCheck) }
}

android {
    namespace = "dev.natsumi.wetype.markchanger"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.natsumi.wetype.markchanger"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
