/*
 * 根构建：只做公共约定，不放业务逻辑。
 *
 * version 统一来自 gradle.properties，各子工程不再单独写死。
 */

allprojects {
    group = "com.mingliqiye.gradle.plugin.fatjar"
    version = providers.gradleProperty("version").get()

    repositories {
        maven {
            url = uri("https://repository.mingliqiye.com/repository/maven-public/")
        }
    }
}
