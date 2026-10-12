buildscript {
    dependencies {
        // AGP 9 liefert Kotlin eingebaut mit (KGP-Laufzeitabhaengigkeit). Ohne diesen
        // Pin wuerde die von AGP mitgebrachte KGP-Version gelten und nicht zur
        // Compose-Compiler-Plugin-Version aus dem Version-Catalog passen. Gleich `kotlin` in
        // gradle/libs.versions.toml (der buildscript-Block kann den Catalog nicht lesen) —
        // BuildIntegrityTest haelt beide Zahlen gleich.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
