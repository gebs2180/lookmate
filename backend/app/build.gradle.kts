plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.2"))
    implementation(platform("software.amazon.awssdk:bom:2.59.2"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("software.amazon.awssdk:s3")
    // Reads EXIF orientation for storage/PhotoMetadata, which strips location and device metadata from every uploaded photo.
    implementation("com.drewnoakes:metadata-extractor:2.22.0")
    // Error reports to Sentry (docs/hosting.md). Does nothing unless SENTRY_DSN is set.
    implementation("io.sentry:sentry-spring-boot-4-starter:8.64.0")
    implementation("io.sentry:sentry-logback:8.64.0")
    // The API's OpenAPI description, which the web app's types are generated from (docs/api-types.md). The
    // endpoint is off in the running app; OpenApiSpecTest writes web/openapi.json from it.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-minio")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    // ./gradlew test -Dopenapi.update=true rewrites web/openapi.json (OpenApiSpecTest); then npm run api:types in web/.
    systemProperty("openapi.update", System.getProperty("openapi.update") ?: "false")
}
