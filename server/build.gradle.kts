// server：后台（手册 5.5），Hub / Brain / Gateway / Memory。Java 21，单个可运行 jar。
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
    implementation("io.javalin:javalin:6.7.0")          // HTTP + WebSocket + 静态网页
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")   // SQLite，自带 FTS5
    implementation("org.yaml:snakeyaml:2.7")            // config.yaml
    implementation("ch.qos.logback:logback-classic:1.6.5")
    testImplementation(project(":mock-executor"))       // 进程内集成测试用假执行器的 Simulator
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.blockmate.server.Main")
}

// 网页（ui/）是纯静态文件，打包时整个拷进 jar 的 web/ 目录，由后台在 / 提供（手册 5.6）
tasks.processResources {
    from(rootProject.file("ui")) {
        into("web")
    }
}

// 产物：server/build/libs/server-all.jar（手册 5.7 拷到 Mac mini 时改名 server.jar）
tasks.shadowJar {
    archiveBaseName.set("server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
