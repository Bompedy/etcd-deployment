plugins {
    kotlin("jvm").version("1.9.0")
    id("com.github.johnrengelman.shadow").version("7.0.0")
}

group = "me.lucas.consensus"
version = "1.0.0"

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.shadowJar {
    archiveFileName.set("${project.projectDir}/output/${project.name}.jar")
    manifest.attributes["Main-Class"] = "me.lucas.consensus.MainKt"
}

tasks.build { dependsOn(tasks.shadowJar) }
