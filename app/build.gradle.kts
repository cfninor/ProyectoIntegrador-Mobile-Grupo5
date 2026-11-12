import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    jacoco
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

    // Sesión del cliente (SOLVENTAG5-104): el cliente app-movil del user pool de Cognito del
    // ambiente. Sin su id, la app no pide inicio de sesión y el BFF local usa la sesión simulada.
    // Para probar dev contra AWS: -Psolventa.apiBaseUrl=<movil/url de SSM>
    // -Psolventa.cognitoClienteMovilId=<cognito/cliente-movil-id de SSM>.
    val apiBaseUrlDev = providers.gradleProperty("solventa.apiBaseUrl").orNull ?: "http://10.0.2.2:8011"
    val cognitoClienteMovilId = providers.gradleProperty("solventa.cognitoClienteMovilId").orNull ?: ""
    defaultConfig {
        buildConfigField("String", "COGNITO_REGION", "\"us-east-1\"")
        buildConfigField("String", "COGNITO_CLIENTE_MOVIL_ID", "\"$cognitoClienteMovilId\"")
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
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrlDev\"")
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

    testOptions {
        // Robolectric necesita los recursos (layouts, textos) en las pruebas unitarias.
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

// Robolectric carga las clases de la app con su propio cargador; sin esto JaCoCo no cuenta la
// cobertura de las pantallas en las pruebas unitarias.
tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.espresso.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
