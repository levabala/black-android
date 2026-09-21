plugins {
    id("com.android.application")
}

android {
    namespace = "com.levabala.blackandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.levabala.blackandroid"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
