import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// La versión vive en version.properties (raíz del proyecto) y se cambia con scripts/bump-version.sh
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = listOf("VERSION_MAJOR", "VERSION_MINOR", "VERSION_PATCH")
    .joinToString(".") { versionProps.getProperty(it) }
val appVersionCode = versionProps.getProperty("VERSION_CODE").toInt()

android {
    namespace = "com.visor.archivos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.visor.archivos"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // El APK se genera como visor-<versión>-<tipo>.apk
        setProperty("archivesBaseName", "visor-$appVersionName")
    }

    buildTypes {
        release {
            // Sin almacén de claves propio: el APK de distribución se firma con la clave de depuración del Mac.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Imágenes con zoom y carga por teselas (fotos grandes), GIF y SVG
    implementation("me.saket.telephoto:zoomable-image-coil:0.19.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    // Markdown renderizado
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2")

    testImplementation("junit:junit:4.13.2")
}
