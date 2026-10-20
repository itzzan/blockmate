// mock-executor：假装自己是装了控制模组的游戏客户端（手册 5.4）。Java 21，单个可运行 jar。
plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.blockmate"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":protocol"))
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.blockmate.mock.Main")
}

// 产物：mock-executor/build/libs/mock-executor-all.jar
tasks.shadowJar {
    archiveBaseName.set("mock-executor")
    archiveClassifier.set("all")
    archiveVersion.set("")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
