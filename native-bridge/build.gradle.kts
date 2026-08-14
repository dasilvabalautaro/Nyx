plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "chat.neto.nyx.nativebridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Fase 0/2: el .aar de go-libp2p (gomobile) se colocará en native-bridge/libs/
    // y se consumirá con: implementation(files("libs/nyx-p2p.aar"))
}

dependencies {
    implementation(project(":core"))

    // go-libp2p compilado con gomobile (Fase 0). Regenerar con:
    //   cd native-bridge/libp2p && gomobile bind -target=android -androidapi 30 \
    //     -javapkg=chat.neto.nyx -o ../libs/nyx-p2p.aar .
    api(files("libs/nyx-p2p.aar"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
