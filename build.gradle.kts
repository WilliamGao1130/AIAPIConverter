plugins {
    id("java-library")
    id("application")
    id("maven-publish")
}

group = "org.bluepowerrobotics"
// 版本号统一在 gradle.properties 的 version 一行调整；
// 命令行可用 -Pversion=x.y.z 临时覆盖（如发布脚本/CI）。

repositories {
    mavenCentral()
}

java {
    // 兼容 Java 8+，方便以后嵌入 Android/其他 JVM 项目
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

dependencies {
    // 各家官方 SDK（openai-java / anthropic-java 由 Stainless 生成，Kotlin 实现，Java 可正常调用）
    api("com.alibaba:dashscope-sdk-java:2.22.28")
    api("com.openai:openai-java:4.50.0")
    api("com.anthropic:anthropic-java:2.52.0")
    api("com.google.genai:google-genai:1.64.0")

    // JSON 处理（gateway 使用；两个 SDK 也依赖 jackson，统一版本）
    api("com.fasterxml.jackson.core:jackson-databind:2.19.4")

    // 独立运行时不需要具体日志实现，避免 SLF4J 警告
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.bluepowerrobotics.lmau.converter.Main")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.bluepowerrobotics.lmau.converter.Main"
        attributes["Implementation-Title"] = "AI API Converter"
        attributes["Implementation-Version"] = project.version
    }
}

// 一键打包可执行 fat jar（含全部依赖），用于独立运行网关
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.bluepowerrobotics.lmau.converter.Main"
        attributes["Implementation-Title"] = "AI API Converter"
        attributes["Implementation-Version"] = project.version
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/versions/**/module-info.class", "module-info.class")
    }
}

// Maven 发布：与 easytier-android-jni 同款方式，发布到本地/远程目录型 maven 仓库。
// 用法：
//   ./gradlew publish                                  # 默认发布到 build/maven-repo
//   ./gradlew publish -PmavenRepoDir=<目录>             # 发布到指定目录（如 maven 仓库 checkout）
//   ./gradlew publish -PmavenRepoDir=<目录> -Pversion=1.0.1
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = "AIAPIConverter"
            }
        }
        repositories {
            maven {
                val repoDir = providers.gradleProperty("mavenRepoDir").orNull
                url = uri(repoDir ?: layout.buildDirectory.dir("maven-repo").get().asFile)
            }
        }
    }
}
