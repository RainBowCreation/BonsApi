plugins {
    id("java-library")
    id("maven-publish")
    id("com.gradleup.shadow")
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools:r8:8.13.19")
    }
}

dependencies {
    api(project(":bonsai"))
    compileOnly("org.apache.fory:fory-core:0.14.1")
    compileOnly("com.github.ben-manes.caffeine:caffeine:2.9.3")
}

tasks.shadowJar {
    archiveClassifier.set("unoptimized")
    mergeServiceFiles()

    manifest {
        attributes("Multi-Release" to "true")
    }

    into("META-INF/versions/11") { from(project.extensions.getByType(SourceSetContainer::class.java)["java11"].output) }
    into("META-INF/versions/17") { from(project.extensions.getByType(SourceSetContainer::class.java)["java17"].output) }
    into("META-INF/versions/21") { from(project.extensions.getByType(SourceSetContainer::class.java)["java21"].output) }
    into("META-INF/versions/25") { from(project.extensions.getByType(SourceSetContainer::class.java)["java25"].output) }

    dependencies {
        exclude(dependency("org.apache.fory:.*:.*"))
        exclude(dependency("com.github.ben-manes.caffeine:.*:.*"))
    }

    relocate("com.dslplatform", "net.rainbowcreation.bonsai.vendored.dslplatform")
    relocate("com.google.common", "net.rainbowcreation.bonsai.vendored.guava")
    relocate("com.google.errorprone", "net.rainbowcreation.bonsai.vendored.errorprone")
    relocate("com.google.thirdparty", "net.rainbowcreation.bonsai.vendored.thirdparty")
    relocate("org.checkerframework", "net.rainbowcreation.bonsai.vendored.checkerframework")
    relocate("javax.annotation", "net.rainbowcreation.bonsai.vendored.jsr305")
    relocate("dsl_json", "net.rainbowcreation.bonsai.vendored.dsl_json")

    exclude("**/dslplatform/json/processor/**")
    exclude("**/dslplatform/json/jsonb/**")
    exclude("META-INF/services/*ICompilerFactory")
    exclude("META-INF/services/*Processor")
    exclude("META-INF/services/*JsonbProvider")
}

tasks.register<JavaExec>("r8") {
    dependsOn(tasks.shadowJar)

    val inputJar = tasks.shadowJar.get().archiveFile.get().asFile
    val tempFilteredJar = file("${layout.buildDirectory.get()}/tmp/filtered-for-r8.jar")
    val r8Jar = file("${layout.buildDirectory.get()}/libs/temp-r8.jar")

    val finalJar = file("${layout.buildDirectory.get()}/libs/${tasks.shadowJar.get().archiveBaseName.get()}-${project.version}.jar")
    val rulesFile = file("${layout.buildDirectory.get()}/tmp/r8-rules.pro")

    inputs.file(inputJar)
    outputs.file(finalJar)

    mainClass.set("com.android.tools.r8.R8")
    classpath = buildscript.configurations["classpath"]

    doFirst {
        println("Creating filtered JAR for R8 (excluding DSL-JSON)...")
        tempFilteredJar.parentFile.mkdirs()
        ant.withGroovyBuilder {
            "zip"("destfile" to tempFilteredJar, "duplicate" to "preserve") {
                "zipfileset"("src" to inputJar) {
                    "include"("name" to "**/*")
                    "exclude"("name" to "net/rainbowcreation/bonsai/vendored/dslplatform/**")
                    "exclude"("name" to "net/rainbowcreation/bonsai/vendored/dsl_json/**")
                }
            }
        }

        rulesFile.parentFile.mkdirs()
        rulesFile.writeText("""
            -keep class net.rainbowcreation.bonsai.* { *; }
            -keep class net.rainbowcreation.bonsai.api.** { *; }
            -keep class net.rainbowcreation.bonsai.impl.** { *; }
            -keep class net.rainbowcreation.bonsai.connection.** { *; }
            -keep class net.rainbowcreation.bonsai.annotation.** { *; }
            -keep class net.rainbowcreation.bonsai.query.** { *; }
            -keep class net.rainbowcreation.bonsai.util.** { *; }

            -dontoptimize
            -dontobfuscate
            -dontwarn **
            -keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
        """.trimIndent())

        val usageFile = file("${layout.buildDirectory.get()}/outputs/usage.txt")

        args = listOf(
            "--release",
            "--classfile",
            "--no-desugaring",
            "--lib", "${System.getProperty("java.home")}/jmods",
            "--pg-conf", rulesFile.absolutePath,
            "--output", r8Jar.absolutePath,
            "--pg-map-output", usageFile.absolutePath,
            tempFilteredJar.absolutePath
        )
    }

    doLast {
        println("Merging: Relocated User Code (Unoptimized) + DSL-JSON (Unoptimized) + Shrunk Libraries (R8)")

        ant.withGroovyBuilder {
            "zip"("destfile" to finalJar, "duplicate" to "preserve") {
                "zipfileset"("src" to inputJar) {
                    "include"("name" to "net/rainbowcreation/bonsai/**")
                    "exclude"("name" to "net/rainbowcreation/bonsai/vendored/**")
                    "include"("name" to "META-INF/**")
                }
                "zipfileset"("src" to inputJar) {
                    "include"("name" to "net/rainbowcreation/bonsai/vendored/dslplatform/**")
                    "include"("name" to "net/rainbowcreation/bonsai/vendored/dsl_json/**")
                }
                "zipfileset"("src" to r8Jar) {
                    "include"("name" to "net/rainbowcreation/bonsai/vendored/**")
                }
            }
        }

        delete(r8Jar)
        delete(tempFilteredJar)
    }
}

tasks.jar { enabled = false }

tasks.assemble {
    dependsOn("r8")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "net.rainbowcreation.bonsai"
            artifactId = "BonsApi"
            version = "${rootProject.version}-SNAPSHOT"

            artifact(file("${layout.buildDirectory.get()}/libs/bonsapi-${project.version}.jar")) {
                builtBy(tasks.named("r8"))
            }

            pom {
                withXml {
                    val dependenciesNode = asNode().appendNode("dependencies")

                    val foryDep = dependenciesNode.appendNode("dependency")
                    foryDep.appendNode("groupId", "org.apache.fory")
                    foryDep.appendNode("artifactId", "fory-core")
                    foryDep.appendNode("version", "0.14.1")
                    foryDep.appendNode("scope", "runtime")

                    val caffeineDep = dependenciesNode.appendNode("dependency")
                    caffeineDep.appendNode("groupId", "com.github.ben-manes.caffeine")
                    caffeineDep.appendNode("artifactId", "caffeine")
                    caffeineDep.appendNode("version", "2.9.3")
                    caffeineDep.appendNode("scope", "runtime")
                }
            }
        }
    }
    repositories {
        maven {
            url = uri("${rootProject.projectDir}/.repo/releases")
        }
    }
}