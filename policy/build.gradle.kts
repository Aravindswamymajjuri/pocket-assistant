plugins { id("org.jetbrains.kotlin.jvm") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { testImplementation("junit:junit:4.13.2") }
