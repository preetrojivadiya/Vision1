plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.vision1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.vision1"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        mlModelBinding = true
    }

    configurations.all {
        resolutionStrategy {
            force("com.google.protobuf:protobuf-javalite:3.25.5")
        }
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    packaging{
        resources{
            excludes +="META-INF/DEPENDENCIES"
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    //implementation(libs.kotlin.gradle.plugin)
    //implementation(libs.gradle)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.mysql.connector.java)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    implementation(libs.firebase.inappmessaging)
    implementation(libs.google.guava)

    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.metadata)
    implementation(libs.androidx.work.runtime)
    //implementation(libs.pdfbox)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)
    implementation(libs.text.recognition)
    implementation(libs.pdfbox.android)
    //implementation(libs.tensorflow.lite.task.vision)
    //implementation(libs.tensorflow.lite.gpu)


    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)


}



































































































































