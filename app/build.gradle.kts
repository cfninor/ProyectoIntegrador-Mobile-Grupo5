plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "co.solventa.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "co.solventa.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Un flavor por ambiente: cada uno apunta a su BFF móvil y se instala con un id distinto,
    // así DEV, QA y PROD conviven en el mismo dispositivo.
    flavorDimensions += "ambiente"
    productFlavors {
        create("dev") {
            dimension = "ambiente"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // 10.0.2.2 es el equipo anfitrión visto desde el emulador; 8011 es el bff-movil
            // del docker-compose del repositorio principal.
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8011\"")
        }
        create("qa") {
            dimension = "ambiente"
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            // Se reemplaza cuando exista el ambiente QA en AWS.
            buildConfigField("String", "API_BASE_URL", "\"https://bff-movil.qa.solventa.invalid\"")
        }
        create("prod") {
            dimension = "ambiente"
            // Se reemplaza cuando exista el ambiente PROD en AWS.
            buildConfigField("String", "API_BASE_URL", "\"https://bff-movil.solventa.invalid\"")
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = true
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

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.material)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
