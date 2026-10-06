plugins { java }
dependencies {
    implementation(platform("org.junit:junit-bom:5.10.0"))
    implementation(libs.jackson.databind)
    testImplementation("junit:junit:4.12")
}
