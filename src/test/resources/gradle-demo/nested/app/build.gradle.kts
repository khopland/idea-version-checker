plugins { java }
val junitVersion: String by project
dependencies {
    implementation(platform("org.junit:junit-bom:5.10.0"))
    implementation(libs.jackson.databind)
    testImplementation("junit:junit:$junitVersion")
}
