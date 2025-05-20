@file:OptIn(ApolloExperimental::class)

import com.apollographql.apollo.annotations.ApolloExperimental

fun prop(key: String) = project.findProperty(key).toString()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.apollographql.apollo").version("4.2.0")
    id("org.jetbrains.kotlin.plugin.compose").version("2.1.10")
}

android {
    namespace = "com.example.apollokotlinpaginationsample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.apollokotlinpaginationsample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "GITHUB_OAUTH_KEY", "\"${prop("githubOauthKey")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

apollo {
    service("main") {
        packageName.set("com.example.apollokotlinpaginationsample.graphql")

        plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin:1.0.0-alpha.2") {
            argument("packageName", packageName.get())
        }

        introspection {
            endpointUrl.set("https://api.github.com/graphql")
            schemaFile.set(file("src/main/graphql/schema.graphqls"))
            headers.put("Authorization", "Bearer ${prop("githubOauthKey")}")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.apollographql.apollo:apollo-runtime")
    implementation("com.apollographql.cache:normalized-cache-sqlite:1.0.0-alpha.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
