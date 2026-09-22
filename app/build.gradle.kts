plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

import java.util.Properties

private val localBuildProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

private fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

private val debugPhoneBlockToken =
    localBuildProperties.getProperty("whocalltome.phoneblock.token").orEmpty()

private val debugIpqsKey =
    localBuildProperties.getProperty("whocalltome.ipqs.key").orEmpty()

android {
    namespace = "com.whocalltome.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.whocalltome.app"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "PHONEBLOCK_DEFAULT_TOKEN",
                debugPhoneBlockToken.asBuildConfigString(),
            )
            buildConfigField("String", "IPQS_DEFAULT_KEY", debugIpqsKey.asBuildConfigString())
        }
        release {
            buildConfigField(
                "String",
                "PHONEBLOCK_DEFAULT_TOKEN",
                debugPhoneBlockToken.asBuildConfigString(),
            )
            buildConfigField("String", "IPQS_DEFAULT_KEY", debugIpqsKey.asBuildConfigString())
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")

    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.17")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
}
