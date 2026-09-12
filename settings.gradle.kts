plugins {
    // Lets the Java 21 toolchain resolve on machines that only have a newer JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "forecast"

include("sampler")
