// Top-level build file. Every plugin is declared here and applied in :app.
// Deliberately NO kapt anywhere — annotation processing is KSP only.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.realm) apply false
    alias(libs.plugins.google.services) apply false
}
