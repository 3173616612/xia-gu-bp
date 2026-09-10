import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningPropertiesFile = rootProject.file("../work/signing/keystore.properties")
val releaseKeystoreFile = rootProject.file("../work/signing/xiagu-release.jks")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = releaseKeystoreFile.isFile && releaseSigningPropertiesFile.isFile
val releaseSigningValue = { name: String ->
    releaseSigningProperties.getProperty(name)
        ?: error("Missing $name in ${releaseSigningPropertiesFile.path}")
}

gradle.taskGraph.whenReady {
    val releaseTaskRequested = allTasks.any { task ->
        task.name.contains("release", ignoreCase = true)
    }
    if (releaseTaskRequested) {
        check(hasReleaseSigning) {
            "Release signing files not found at ${releaseSigningPropertiesFile.path} and ${releaseKeystoreFile.path}"
        }
    }
}

android {
    namespace = "com.xiagu.bp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiagu.bp"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.8.0"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningValue("storePassword")
                keyAlias = releaseSigningValue("keyAlias")
                keyPassword = releaseSigningValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
