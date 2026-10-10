import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val firebaseConfig = Properties().apply {
    rootProject.file("firebase.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun firebaseValue(key: String) = "\"" + firebaseConfig.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "pl.apargb.milkyway"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.apargb.milkyway"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "0.7.15"
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseValue("projectId"))
        buildConfigField("String", "FIREBASE_API_KEY", firebaseValue("apiKey"))
        buildConfigField("String", "FIREBASE_APP_ID", firebaseValue("applicationId"))
        buildConfigField("String", "FIREBASE_DATABASE_URL", firebaseValue("databaseUrl"))
        buildConfigField("String", "FIREBASE_PLANT_ID", firebaseValue("plantId"))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.withType<Test>().configureEach {
    val testHome = gradle.gradleUserHomeDir.resolve("android-test-home")
    systemProperty("user.home", testHome.absolutePath)
    doFirst { testHome.mkdirs() }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    // Forward only network/trust settings for the Android runtime used by JVM tests.
    listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}
