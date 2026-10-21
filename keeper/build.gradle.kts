// keeper：跑在 MacBook 上、负责开关伙伴游戏客户端的守护进程（手册 7.2）。Java 21，单个可运行 jar。
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
    implementation("org.yaml:snakeyaml:2.7")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.blockmate.keeper.Main")
}

// 产物：keeper/build/libs/keeper-all.jar
tasks.shadowJar {
    archiveBaseName.set("keeper")
    archiveClassifier.set("all")
    archiveVersion.set("")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
