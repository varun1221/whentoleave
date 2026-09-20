plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    // §9.6, and last on purpose: the jar path is what step 13 deploys and verifies, and
    // this only adds a second way to build the same verified code. It contributes the
    // `nativeCompile` task and feeds Spring's AOT output into it; Dockerfile.native is
    // where it runs, because native-image is not on a normal developer machine.
    id("org.graalvm.buildtools.native") version "0.10.6"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Flyway owns the schema; JPA only validates against it. See application.yml.
    // In-process cache for address search results, so a repeated query is not billed.
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The seed importer reads config/routes.json and data/samples/ by the same relative
// paths the Phase 1 sampler writes them to, so bootRun has to run from the repo root.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "forecast-service"
            // Cloud Run does not say which CPU it will run this on. GraalVM's default
            // targets the build machine's instruction set, which turns an unlucky
            // scheduling onto an older host into SIGILL on the first request; the cost
            // of ruling that out is a few percent of throughput this service never
            // needs, on a workload that is one Postgres query and a JSON response.
            buildArgs.add("-march=compatibility")
        }
    }
    // The test suite wants a live Postgres and is already run on the JVM by CI, where a
    // failure points at the code rather than at the image. Compiling it natively as well
    // would double a 15-minute build to re-answer a question CI has answered.
    testSupport = false
}

tasks.test {
    // Same reason bootRun does it: the seed importer resolves config/routes.json and
    // data/samples/ by the paths the Phase 1 sampler writes them to.
    workingDir = rootProject.projectDir
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
