plugins {
    application
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.varun.forecast.Main"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The plain jar and the shadow jar would otherwise both write sampler.jar, and
// `gradlew build` runs them in an order that leaves the manifest-less one on disk.
tasks.jar {
    archiveClassifier = "thin"
}

tasks.shadowJar {
    archiveFileName = "sampler.jar"
}
