rootProject.name = "version-checker-gradle-demo"
include("library", "nested:app")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
