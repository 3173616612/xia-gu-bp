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
val releaseSigningValue = { name: String ->
    releaseSigningProperties.getProperty(name)
        ?: error("Missing $name in ${releaseSigningPropertiesFile.path}")
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
        create("release") {
            check(releaseKeystoreFile.isFile) {
                "Release keystore not found at ${releaseKeystoreFile.path}"
            }
            storeFile = releaseKeystoreFile
            storePassword = releaseSigningValue("storePassword")
            keyAlias = releaseSigningValue("keyAlias")
            keyPassword = releaseSigningValue("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
