import java.util.zip.ZipFile
import org.objectweb.asm.*

plugins {
    java
    `maven-publish`
    id("net.minecraftforge.gradle") version "6.0.54"
    id("org.spongepowered.mixin") version "0.7.38"
}

group = property("mod_group_id") as String
version = property("mod_version") as String

base {
    archivesName.set("better-runtime-diagnostics")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

minecraft {
    mappings("official", property("minecraft_version") as String)
    copyIdeResources = true

    runs {
        configureEach {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create(property("mod_id") as String) {
                    source(sourceSets.main.get())
                }
            }
        }
        create("client")
        create("server") { arg("--nogui") }
    }
}

repositories {
    maven("https://maven.minecraftforge.net")
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
    mavenCentral()
}

dependencies {
    minecraft("net.minecraftforge:forge:${property("minecraft_version")}-${property("forge_version")}")
    compileOnly(fg.deobf("curse.maven:architectury-api-419699:5137938"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.processResources {
    val props = mapOf(
        "minecraft_version" to project.property("minecraft_version"),
        "forge_version" to project.property("forge_version"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_version" to project.property("mod_version")
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(props) }
}

tasks.named<Jar>("jar") {
    exclude("better_runtime_test_support.*")
    finalizedBy("reobfJar")
}

val stageRuntimeJar by tasks.registering(Copy::class) {
    dependsOn(tasks.named("reobfJar"))
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "${base.archivesName.get()}-$version.jar" }
}

tasks.named("assemble") { dependsOn(stageRuntimeJar) }

// Observational fixture supplement only; excluded from production and candidate archives.
val testSupport = sourceSets.create("testSupport") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}
configurations[testSupport.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
dependencies {
    add(testSupport.compileOnlyConfigurationName, fg.deobf("curse.maven:untamed-wilds-au-naturel-1399279:7441092"))
    add(testSupport.annotationProcessorConfigurationName, "org.spongepowered:mixin:0.8.5:processor")
}
mixin { add(testSupport, "better_runtime_test_support.refmap.json") }
val testSupportJar by tasks.registering(Jar::class) {
    archiveFileName.set("better-runtime-test-support.jar")
    from(testSupport.output)
    doFirst { manifest.attributes["MixinConfigs"] = "better_runtime_test_support.mixins.json" }
}
reobf { create("testSupportJar") { libraries.from(sourceSets.main.get().output, sourceSets.main.get().compileClasspath, testSupport.compileClasspath) } }
testSupportJar { finalizedBy("reobfTestSupportJar") }
tasks.named("reobfTestSupportJar") { mustRunAfter(stageRuntimeJar) }
val verifyTestSupportIsolation by tasks.registering {
    dependsOn(tasks.named("reobfJar"), tasks.named("reobfTestSupportJar"))
    doLast {
        ZipFile(layout.buildDirectory.file("reobfJar/output.jar").get().asFile).use { jar ->
            require(jar.entries().asSequence().none { it.name.contains("runtimetestsupport") || it.name.contains("better_runtime_test_support") }) { "Diagnostic supplement leaked into production" }
        }
        ZipFile(testSupportJar.get().archiveFile.get().asFile).use { jar ->
            val config=jar.getInputStream(requireNotNull(jar.getEntry("better_runtime_test_support.mixins.json"))).reader().use { com.google.gson.JsonParser.parseReader(it).asJsonObject }
            require(config.getAsJsonArray("mixins").map { it.asString } == listOf("NonfiniteRotationMixin","UntamedSpeciesObservationMixin")) { "Unexpected diagnostic observers" }
            val refmap=jar.getInputStream(requireNotNull(jar.getEntry("better_runtime_test_support.refmap.json"))).reader().use { com.google.gson.JsonParser.parseReader(it).asJsonObject }
            for(mapping in listOf(refmap.getAsJsonObject("mappings"),refmap.getAsJsonObject("data").getAsJsonObject("searge"))) {
                val rotation=mapping.getAsJsonObject("com/bettercontent/runtimetestsupport/diagnostics/NonfiniteRotationMixin")
                require(rotation.get("setYRot(F)V").asString == "Lnet/minecraft/world/entity/Entity;m_146922_(F)V" && rotation.get("setXRot(F)V").asString == "Lnet/minecraft/world/entity/Entity;m_146926_(F)V") { "Rotation observers are unmapped" }
            }
            for(name in listOf("NonfiniteRotationMixin","UntamedSpeciesObservationMixin")) jar.getInputStream(requireNotNull(jar.getEntry("com/bettercontent/runtimetestsupport/diagnostics/$name.class"))).use { input ->
                ClassReader(input).accept(object:ClassVisitor(Opcodes.ASM9){
                    override fun visitMethod(access:Int,method:String?,descriptor:String?,signature:String?,exceptions:Array<out String>?):MethodVisitor=object:MethodVisitor(Opcodes.ASM9){
                        override fun visitAnnotation(descriptor:String,visible:Boolean):AnnotationVisitor? = if(descriptor=="Lorg/spongepowered/asm/mixin/injection/Inject;")object:AnnotationVisitor(Opcodes.ASM9){override fun visit(key:String?,value:Any?){require(!(key=="cancellable"&&value==true)){"Observer can cancel gameplay"}}}else null
                        override fun visitMethodInsn(opcode:Int,owner:String,method:String,descriptor:String,isInterface:Boolean){require(method !in setOf("cancel","setReturnValue","setYRot","setXRot","m_146922_","m_146926_","registerEntityData","getEntityData")){"Observer mutates native outcome/rotation/species"}}
                    }
                },ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            require(jar.entries().asSequence().filter { it.name.endsWith(".class") }.all { it.name.startsWith("com/bettercontent/runtimetestsupport/") }) { "Non-diagnostic class in support" }
        }
    }
}
tasks.named("check") { dependsOn(verifyTestSupportIsolation) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
