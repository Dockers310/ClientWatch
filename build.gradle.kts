plugins { java }

group = "dev.antifreecam"
version = "2.2.11"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Поправь версию под свой Paper (см. https://docs.papermc.io)
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

// Итоговый файл всегда называется просто ClientWatch.jar (без версии и без дублей в имени)
tasks.jar {
    archiveFileName.set("ClientWatch.jar")
}
