plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// O núcleo independente de versão (../../core) é compilado junto com este
// adaptador e vai dentro do mesmo jar. Compilar contra o classpath do 1.7.10
// garante que o núcleo só usa APIs que existem aqui (Java 8, Gson 2.2.4).
sourceSets {
    main {
        java.srcDir("../../core/src/main/java")
    }
}
