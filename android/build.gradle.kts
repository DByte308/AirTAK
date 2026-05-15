// MASH Android — build.gradle.kts (root)
// Planning: §11 | Roadmap: Phase 3

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.hilt)                apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.protobuf)            apply false
}
