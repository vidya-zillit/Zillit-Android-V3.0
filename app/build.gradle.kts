plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.realm)
    alias(libs.plugins.google.services)
}

// local.properties is read through the providers API (not a raw File read) so the
// configuration cache tracks it as an input — editing a key correctly invalidates
// the cache instead of silently baking a stale URL into BuildConfig.
private val localPropsText = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties")
).asText.orElse("")

private val localProps: Map<String, String> =
    localPropsText.get().lineSequence()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        .toMap()

/** Reads a secret/URL out of local.properties. Missing keys become "" so a fresh
 *  clone still configures; the failure then surfaces at runtime, not at sync. */
fun secret(name: String): String = localProps[name].orEmpty()

/** Declares a String BuildConfig field from a local.properties key. */
fun com.android.build.api.dsl.VariantDimension.urlField(field: String, propKey: String) {
    buildConfigField("String", field, "\"${secret(propKey)}\"")
}

android {
    namespace = "com.zillit.zillitapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zillit.zillitapp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

        // The Maps key reaches the manifest as a placeholder rather than a checked-in
        // string resource, so it stays in local.properties with every other secret.
        manifestPlaceholders["mapsApiKey"] = secret("MAPS_API_KEY")

        // Help links: the documentation site and the tutorial-video bucket. Same for
        // every flavour, which is why they live here rather than per build type.
        urlField("DOCS_URL", "DOCS_BASE_URL")
        urlField("VIDEO_BASE_URL", "VIDEO_BASE_URL")
    }

    flavorDimensions += "version"
    productFlavors {
        // applicationId per flavour is constrained by Firebase: the google-services
        // plugin fails the build unless the id has a matching client in
        // google-services.json, and only these are registered:
        //   develop project -> com.zillit.zillitapp.dev, com.zillit.zillitapp.stg
        //   qa      project -> com.android.zillit.qa,    com.zillit.zillitapp.qa
        //   prod    project -> com.android.zillit,       com.zillit.zillitapp
        //
        // develop uses `.stg`: registered, so Remote Config works, and unused by any v2
        // build, so v3 installs ALONGSIDE the v2 `.dev` app instead of colliding with it
        // (v2 develop is already at versionCode 32494, so a shared id fails as a
        // downgrade). qa and prod reuse v2's ids and therefore REPLACE v2 on a device —
        // correct for those channels, but their versionCode must be raised above v2's
        // before any real release build.
        create("develop") {
            dimension = "version"
            applicationIdSuffix = ".stg"
            versionNameSuffix = "-v3dev"
            urlField("BASE_URL", "STG_BASE_URL")
            urlField("CHAT_BASE_URL", "STG_CHAT_BASE_URL")
            urlField("NOTIFICATION_BASE_URL", "STG_NOTIFICATION_BASE_URL")
            // Home units live on their own service, not the main API host.
            urlField("UNITS_BASE_URL", "STG_UNITS_BASE_URL")
            // Translation proxies OpenAI behind Zillit's own signed endpoint.
            urlField("INTEGRATIONS_BASE_URL", "STG_INTEGRATIONS_BASE_URL")
            urlField("DOC_DISTRIBUTION_BASE_URL", "STG_DOC_DISTRIBUTION_BASE_URL")
            urlField("CALENDAR_BASE_URL", "STG_CALENDAR_BASE_URL")
            urlField("LOCATION_BASE_URL", "STG_LOCATION_BASE_URL")
            urlField("ENCRYPTION_KEY", "STG_ENCRYPTION_KEY")
            urlField("IV_KEY", "STG_IV_ENCRYPTION_KEY")
        }
        create("qa") {
            dimension = "version"
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-v3qa"
            urlField("BASE_URL", "QA_BASE_URL")
            urlField("CHAT_BASE_URL", "QA_CHAT_BASE_URL")
            urlField("NOTIFICATION_BASE_URL", "QA_NOTIFICATION_BASE_URL")
            // Home units live on their own service, not the main API host.
            urlField("UNITS_BASE_URL", "QA_UNITS_BASE_URL")
            // Translation proxies OpenAI behind Zillit's own signed endpoint.
            urlField("INTEGRATIONS_BASE_URL", "QA_INTEGRATIONS_BASE_URL")
            urlField("DOC_DISTRIBUTION_BASE_URL", "QA_DOC_DISTRIBUTION_BASE_URL")
            urlField("CALENDAR_BASE_URL", "QA_CALENDAR_BASE_URL")
            urlField("LOCATION_BASE_URL", "QA_LOCATION_BASE_URL")
            urlField("ENCRYPTION_KEY", "QA_ENCRYPTION_KEY")
            urlField("IV_KEY", "QA_IV_ENCRYPTION_KEY")
        }
        create("prod") {
            dimension = "version"
            urlField("BASE_URL", "PROD_BASE_URL")
            urlField("CHAT_BASE_URL", "PROD_CHAT_BASE_URL")
            urlField("NOTIFICATION_BASE_URL", "PROD_NOTIFICATION_BASE_URL")
            // Home units live on their own service, not the main API host.
            urlField("UNITS_BASE_URL", "PROD_UNITS_BASE_URL")
            // Translation proxies OpenAI behind Zillit's own signed endpoint.
            urlField("INTEGRATIONS_BASE_URL", "PROD_INTEGRATIONS_BASE_URL")
            urlField("DOC_DISTRIBUTION_BASE_URL", "PROD_DOC_DISTRIBUTION_BASE_URL")
            urlField("CALENDAR_BASE_URL", "PROD_CALENDAR_BASE_URL")
            urlField("LOCATION_BASE_URL", "PROD_LOCATION_BASE_URL")
            urlField("ENCRYPTION_KEY", "PROD_ENCRYPTION_KEY")
            urlField("IV_KEY", "PROD_IV_ENCRYPTION_KEY")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // No viewBinding, no dataBinding — this app is Compose-only.
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.emoji.picker)
    implementation(libs.androidx.print)
    // See the guava note in libs.versions.toml — required for CameraX to compile.
    implementation(libs.guava)
    implementation(libs.aws.s3)
    implementation(libs.androidx.work.runtime)

    // The shared location picker.
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.places)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.socketio.client) {
        exclude(group = "org.json", module = "json")
    }

    implementation(libs.realm.base)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    implementation(libs.coil.compose)

    // Firebase Remote Config only. It carries `locale_version`, the counter that decides
    // when the server label dictionaries are stale — see LabelRepository.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    implementation(libs.firebase.messaging)
}
