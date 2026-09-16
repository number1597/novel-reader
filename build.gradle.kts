plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.novelreader"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 直接复用本机安装的 IntelliJ IDEA 2026.1.3 作为目标平台，无需下载平台制品。
        // 若需改为远程平台，替换为：intellijIdea("2026.1")
        local("D:/java/idea")
    }

    // jsoup 由 IntelliJ 平台自带（D:\java\idea\lib\intellij.libraries.jsoup.jar），
    // 通过平台依赖即可在编译期与运行期使用，无需重复引入。
    testImplementation("junit:junit:4.13.2")
}

java {
    // IntelliJ Platform 2026.1 运行在 Java 21+，插件按 Java 21 编译。
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnit()
    // 让 -DrunRealE2E=true 等开关能从 gradle 命令行透传到测试 JVM。
    listOf("runRealE2E").forEach { key ->
        if (System.getProperty(key) != null) {
            systemProperty(key, System.getProperty(key))
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 261 = 2026.1
            sinceBuild = "261"
        }
        changeNotes = """
            首个版本：
            <ul>
              <li>按自定义 JSON 规则在线解析小说目录与章节正文</li>
              <li>正文可输出到通知，也可输出到右侧专用阅读面板（整章滚动 + 当前段高亮），
                  两种方式可切换</li>
              <li>带阅读历史：自动记录到「章 + 段」，关掉 IDEA 再打开仍在，可管理删除</li>
              <li>章节目录对话框：可搜索章节名或序号，打开时自动定位到当前章</li>
              <li>网络健壮性：失败重试与指数退避、多规则兜底（站点改版自愈）、自定义请求头</li>
              <li>规则文件本地可配置，设置页内置图形化规则编辑器（保存前自动备份）</li>
            </ul>
        """.trimIndent()
    }

    // ---------- 上架 JetBrains Marketplace 用的签名与发布 ----------
    //
    // 凭据一律从环境变量读取，绝不写进仓库：
    //   CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD  → 插件签名
    //   PUBLISH_TOKEN                                           → Marketplace 上传令牌
    //
    // 两个块都只在对应环境变量存在时才配置，因此：
    //   - 本地开发：什么都不设，`buildPlugin` 照常出包，`signPlugin`/`publishPlugin` 不会碰；
    //   - 发布时：   设置好环境变量，`gradlew signPlugin publishPlugin`（sign 会自动先跑）。
    // 用 System.getenv 而不是 providers：前者是纯 Java，不受 Gradle API 版本差异影响。
    val envCertificateChain = System.getenv("CERTIFICATE_CHAIN")
    val envPrivateKey = System.getenv("PRIVATE_KEY")
    val envPrivateKeyPassword = System.getenv("PRIVATE_KEY_PASSWORD")
    val envPublishToken = System.getenv("PUBLISH_TOKEN")

    if (!envCertificateChain.isNullOrBlank() && !envPrivateKey.isNullOrBlank()) {
        signing {
            certificateChain = envCertificateChain
            privateKey = envPrivateKey
            password = envPrivateKeyPassword ?: ""
        }
    }
    if (!envPublishToken.isNullOrBlank()) {
        publishing {
            token = envPublishToken
            // 先发到 default 渠道（不自动推给所有用户），确认无误后再手动提稳定渠道
            channels = listOf("default")
        }
    }
}
