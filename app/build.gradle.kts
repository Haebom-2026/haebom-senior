import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "kr.haebom.elder"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "kr.haebom.elder"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // 단위 테스트에서 android.graphics.Rect 같은 클래스를 만들 수 있게 (값은 0/false)
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        // 앱이 부르는 서버 주소: debug 는 dev, release 는 prod 가 정해지면 넣음 (비어 있으면 서버 연동 꺼짐)
        debug {
            buildConfigField("String", "API_BASE", "\"https://qq829otaql.execute-api.ap-northeast-2.amazonaws.com\"")
        }
        release {
            buildConfigField("String", "API_BASE", "\"\"")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 테스트에서 org.json 실제 구현 사용
}
