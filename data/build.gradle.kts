plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "chat.neto.nyx.data"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Room exporta el esquema de cada versión aquí, y estos .json se commitean: son la
    // referencia contra la que se validan las migraciones (y contra la que Room valida en el
    // primer arranque tras subir de versión).
    //
    // Están **dentro de los assets de androidTest** a propósito, no en `schemas/`:
    // `MigrationTestHelper` los busca en los assets del APK de test, y la forma normal de
    // conseguirlo —añadir `schemas/` a `sourceSets["androidTest"].assets`— revienta con
    // AGP 9.2.1 (`DefaultAndroidLibrarySourceSet_Decorated cannot be cast to
    // AndroidLibrarySourceSet`, el accessor del DSL de Kotlin quedó desfasado para módulos
    // library). Exportando directamente aquí, los esquemas viajan en el APK de test sin tocar
    // source sets. Si algún día se arregla el accessor, se puede volver a `schemas/`.
    ksp {
        arg("room.schemaLocation", "$projectDir/src/androidTest/assets")
    }

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json real: el de android.jar es un stub que lanza en tests JVM, y MigrationSqlTest
    // necesita leer de verdad el esquema exportado por Room (schemas/*.json).
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
