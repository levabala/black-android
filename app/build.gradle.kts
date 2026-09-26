plugins {
    id("com.android.application")
}

android {
    namespace = "com.levabala.blackandroid"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.levabala.blackandroid"
        minSdk = 34
        targetSdk = 36
        versionCode = 15
        versionName = "1.0.14"
        val rumClientToken = providers.gradleProperty("DATADOG_RUM_CLIENT_TOKEN")
            .orElse(providers.environmentVariable("DATADOG_RUM_CLIENT_TOKEN"))
            .getOrElse("pub2cd74be5015cad615a140947cdd73a54")
        buildConfigField("String", "DATADOG_RUM_CLIENT_TOKEN",
            "\"${rumClientToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.datadoghq:dd-sdk-android-logs:3.12.1")
    implementation("com.datadoghq:dd-sdk-android-rum:3.12.1")
    testImplementation("junit:junit:4.13.2")
}
