plugins {
    `kotlin-dsl`
}

allprojects {
    repositories {
        google()
        jcenter()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://dl.bintray.com/ekito/koin")
        maven(url = "https://kotlin.bintray.com/kotlin-js-wrappers/")
        maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.21")
    }
}
