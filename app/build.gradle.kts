import org.gradle.api.InvalidUserDataException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val webAppUrl = providers.gradleProperty("VOLVO_WEB_APP_URL")
    .orElse("https://volvswed.site")
    .get()
    .trimEnd('/')
val apiBaseUrl = providers.gradleProperty("VOLVO_API_BASE_URL")
    .orElse(webAppUrl)
    .get()
    .trimEnd('/')

if (!webAppUrl.startsWith("https://") || !apiBaseUrl.startsWith("https://")) {
    throw InvalidUserDataException("VOLVO_WEB_APP_URL and VOLVO_API_BASE_URL must use HTTPS")
}

android {
    namespace = "club.volvoswed.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "club.volvoswed.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "WEB_APP_URL", quoted(webAppUrl))
        buildConfigField("String", "API_BASE_URL", quoted(apiBaseUrl))
        buildConfigField("String", "LOGIN_REDIRECT_SCHEME", quoted("volvoclub"))
        buildConfigField("String", "LOGIN_REDIRECT_HOST", quoted("auth"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.14.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
