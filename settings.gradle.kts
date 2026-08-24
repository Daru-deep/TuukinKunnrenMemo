pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// プロジェクト名はASCIIにしておく（Gradleのキャッシュ配下のパスやWindowsの
// コンソール文字コードで面倒が起きにくい）。画面に出るアプリ名は
// app/src/main/res/values/strings.xml の「通勤記録」の方。
rootProject.name = "TuukinKunnrenMemo"
include(":app")
