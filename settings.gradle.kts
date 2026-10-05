pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // libxposed 的 api/service 都在 Maven Central 上，legacy 的 api.xposed.info 不再需要
    repositories { google(); mavenCentral() }
}
rootProject.name = "GoutouWingman"
include(":app")
