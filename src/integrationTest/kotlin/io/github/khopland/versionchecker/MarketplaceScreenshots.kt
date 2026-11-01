package io.github.khopland.versionchecker

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.getHighlights
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Screenshots for the Marketplace page, written to build/marketplace-screenshots by `./gradlew marketplaceScreenshots`.
 * Each scene is a small, realistic project with outdated versions. Versions come from the real registries,
 * so the offered updates change over time.
 */
class MarketplaceScreenshots {
    @Test
    fun maven() = runWithFiles("screenshots-maven", "orders", mavenProject) {
        prepareWindow()
        val service = openDocument("orders-service/pom.xml")
        waitForWarnings(service, 3)
        centerCaretOn("32.1.1-jre")
        hideBalloons()
        showIntentions()
        saveScreenshot("1-maven-alt-enter")
        closePopups()

        val root = openDocument("pom.xml")
        waitForWarnings(root, 4)
        centerCaretOn("{jackson.version}</version>")
        hideBalloons()
        showWarningDescription()
        saveScreenshot("2-maven-property")
        closePopups()

        val api = openDocument("orders-api/pom.xml")
        waitForHighlight(api, "the managed-version warning") { it.message().endsWith(" is already managed") }
        waitForHighlight(api, "the unused-property warning") { it.message().endsWith(" is not used") }
        // On the tag, where only the redundant-override warning is, not the update warning on the number.
        centerCaretOn("<version>1.7.30")
        hideBalloons()
        showWarningDescription()
        saveScreenshot("4-maven-redundant-override")
        closePopups()

        openDocument("orders-service/pom.xml")
        hideBalloons()
        invokeAction("VersionChecker.UpdateAll.Minor")
        waitFor("the update notification", 2.minutes, getter = {
            getNotifications(singleProject()).firstOrNull { it.getTitle().startsWith("Updated ") }
        }, checker = { it != null })
        Thread.sleep(2000)
        saveScreenshot("5-update-all-files")
    }

    @Test
    fun npm() = runWithFiles("screenshots-npm", "storefront", npmProject) {
        prepareWindow()
        val app = openDocument("package.json")
        waitForWarnings(app, 6)
        centerCaretOn("^18.2.0")
        hideBalloons()
        showIntentions()
        saveScreenshot("3-npm-alt-enter")
    }

    @Test
    fun gradle() = runWithFiles("screenshots-gradle", "inventory", gradleProject) {
        prepareWindow()
        val catalog = openDocument("gradle/libs.versions.toml")
        waitForWarnings(catalog, 4)
        centerCaretOn("\"ktor\" }")
        hideBalloons()
        showWarningDescription()
        saveScreenshot("6-gradle-version-catalog")
    }
}

/** Waits until the plugin shows at least [count] update warnings in [document]. */
private fun Driver.waitForWarnings(document: Document, count: Int) {
    waitFor("$count update warnings", 3.minutes, getter = {
        getHighlights(document, singleProject()).count { " → " in it.message() }
    }, checker = { it >= count })
    // Let the editor paint the last highlights.
    Thread.sleep(1500)
}

private val mavenProject = mapOf(
    // The IDE's vulnerable-dependency warnings would look like the plugin's, and before a Maven reload it marks
    // updated versions as not found.
    ".idea/inspectionProfiles/Project_Default.xml" to """
        <component name="InspectionProjectProfileManager">
          <profile version="1.0">
            <option name="myName" value="Project Default" />
            <inspection_tool class="VulnerableLibrariesLocal" enabled="false" level="WARNING" enabled_by_default="false" />
            <inspection_tool class="MavenModelInspection" enabled="false" level="ERROR" enabled_by_default="false" />
          </profile>
        </component>
    """,
    "pom.xml" to """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example.shop</groupId>
            <artifactId>orders</artifactId>
            <version>1.4.0-SNAPSHOT</version>
            <packaging>pom</packaging>

            <modules>
                <module>orders-api</module>
                <module>orders-service</module>
            </modules>

            <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <jackson.version>2.15.2</jackson.version>
                <slf4j.version>1.7.30</slf4j.version>
            </properties>

            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson</groupId>
                        <artifactId>jackson-bom</artifactId>
                        <version>${'$'}{jackson.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.junit</groupId>
                        <artifactId>junit-bom</artifactId>
                        <version>5.9.3</version>
                        <type>pom</type>
                        <scope>import</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                        <version>${'$'}{slf4j.version}</version>
                    </dependency>
                </dependencies>
            </dependencyManagement>

            <build>
                <pluginManagement>
                    <plugins>
                        <plugin>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.11.0</version>
                        </plugin>
                        <plugin>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>3.0.0</version>
                        </plugin>
                    </plugins>
                </pluginManagement>
            </build>
        </project>
    """,
    "orders-api/pom.xml" to """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>
            <parent>
                <groupId>com.example.shop</groupId>
                <artifactId>orders</artifactId>
                <version>1.4.0-SNAPSHOT</version>
            </parent>
            <artifactId>orders-api</artifactId>

            <properties>
                <commons-lang3.version>3.12.0</commons-lang3.version>
            </properties>

            <dependencies>
                <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-annotations</artifactId>
                </dependency>
                <dependency>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-api</artifactId>
                    <version>1.7.30</version>
                </dependency>
            </dependencies>
        </project>
    """,
    "orders-service/pom.xml" to """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>
            <parent>
                <groupId>com.example.shop</groupId>
                <artifactId>orders</artifactId>
                <version>1.4.0-SNAPSHOT</version>
            </parent>
            <artifactId>orders-service</artifactId>

            <dependencies>
                <dependency>
                    <groupId>com.example.shop</groupId>
                    <artifactId>orders-api</artifactId>
                    <version>${'$'}{project.version}</version>
                </dependency>
                <dependency>
                    <groupId>com.fasterxml.jackson.core</groupId>
                    <artifactId>jackson-databind</artifactId>
                </dependency>
                <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>32.1.1-jre</version>
                </dependency>
                <dependency>
                    <groupId>org.apache.commons</groupId>
                    <artifactId>commons-lang3</artifactId>
                    <version>3.12.0</version>
                </dependency>
                <dependency>
                    <groupId>org.postgresql</groupId>
                    <artifactId>postgresql</artifactId>
                    <version>42.6.0</version>
                </dependency>
                <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <scope>test</scope>
                </dependency>
            </dependencies>
        </project>
    """,
)

// Installed packages at the declared versions, so the IDE does not offer "npm install" over the plugin's fixes.
private val npmInstalled = mapOf(
    "axios" to "1.4.0", "react" to "18.2.0", "react-dom" to "18.2.0", "react-router-dom" to "6.14.0", "zod" to "3.21.4",
    "eslint" to "8.45.0", "typescript" to "5.1.6", "vite" to "4.4.0", "vitest" to "0.34.0",
).map { (name, version) -> "node_modules/$name/package.json" to """{ "name": "$name", "version": "$version" }""" }

private val npmProject = npmInstalled.toMap() + mapOf(
    "package.json" to """
        {
          "name": "storefront",
          "version": "1.0.0",
          "private": true,
          "type": "module",
          "scripts": {
            "dev": "vite",
            "build": "tsc && vite build",
            "test": "vitest"
          },
          "dependencies": {
            "axios": "~1.4.0",
            "react": "^18.2.0",
            "react-dom": "^18.2.0",
            "react-router-dom": "^6.14.0",
            "zod": "3.21.4"
          },
          "devDependencies": {
            "eslint": "^8.45.0",
            "typescript": "~5.1.6",
            "vite": "^4.4.0",
            "vitest": "^0.34.0"
          }
        }
    """,
)

private val gradleProject = mapOf(
    "gradle/libs.versions.toml" to """
        [versions]
        kotlin = "1.9.10"
        ktor = "2.3.4"
        coroutines = "1.7.3"

        [libraries]
        ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
        ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
        kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
        logback = "ch.qos.logback:logback-classic:1.4.11"
        postgresql = { module = "org.postgresql:postgresql", version = "42.6.0" }

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """,
    // No settings script, so the IDE does not import the Gradle build, which the screenshot does not need.
    "build.gradle.kts" to """
        plugins {
            alias(libs.plugins.kotlin.jvm)
            application
        }

        dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.netty)
            implementation(libs.kotlinx.coroutines)
            runtimeOnly(libs.logback)
            runtimeOnly(libs.postgresql)
        }
    """,
)
