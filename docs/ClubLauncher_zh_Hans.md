# 圣悦灵社启动器首版

源码基于 `sdustmc/HMCL`。启动器名称已与原版区分，原版版权声明和许可证保留。

## 已接入的功能

- 没有账户时，启动后默认弹出圣悦灵社皮肤站登录窗口，无需拖入认证链接。
- 首页和账户管理均有“皮肤站登录”入口；已保存的账户继续使用 HMCL 的账户存储和登录流程。
- 首页展示社团公告及服务器列表，支持刷新在线目录。
- 点击服务器的“安装 / 更新整合包”，自动下载游戏、模组加载器及整合包文件。
- 点击“进入服务器”，先检查并安装或更新整合包，成功后再启动游戏并连接服务器；失败时不会启动。
- 更新使用现有文件记录，保留原生文件哈希校验、更新备份、失败回滚行为。

皮肤站使用主页 `X-Authlib-Injector-API-Location` 响应头当前公布的接口：

```text
https://skin.sdustmc.cn/api/yggdrasil/
```

旧公开文档中的 `/api/union/yggdrasil` 在开发时返回 404，故未采用。

## 尚未发布的内容

当前没有社团公告服务或整合包下载源，因此内置配置的公告和服务器列表为空。
下述样例中的 `example.org` 均为占位地址，不能用于真实下载。
真实账号登录仍需使用社团皮肤站账户进行人工验收。

## 本地配置

默认读取启动目录的 `.hmcl/club.json`；文件不存在时读取 JAR 内的 `assets/club.json`。
也可通过 JVM 参数 `-Dhmcl.club.config=C:/path/to/club.json` 指定配置文件。

将 [club/club.example.json](club/club.example.json) 复制到 `.hmcl/club.json`，修改公告和服务器地址。
如果从源码打包，也可以直接编辑 `HMCL/src/main/resources/assets/club.json`，然后重新构建。

公告是纯文本，支持换行。`servers` 中每项包括：

| 字段 | 用途 |
| --- | --- |
| `id` | 稳定标识，使用小写英文字母、数字、下划线和短横线，首字符为字母或数字，最多 48 字符 |
| `name` | 显示名称 |
| `address` | 游戏服务器地址，可包含端口 |
| `manifestUrl` | HMCL 服务端整合包的 `server-manifest.json` 地址 |

本地实例使用 `sdustmc-<id>` 命名。发布后保持 `id` 和整合包 `name` 稳定，改名会新建实例或被视为不同的包。
若同名实例属于其他整合包，启动器会拒绝覆盖。

## 在线公告和服务器目录

将同一格式的 JSON 文件发布到可访问的静态托管地址，然后把本地配置设为：

```json
{
  "catalogUrl": "https://downloads.example.org/club/catalog.json"
}
```

在线目录的 `catalogUrl` 可省略。启动器启动或点击“刷新”时读取该目录；加载失败会显示重试提示，并保留本次运行中已经显示的内容。
线上目录只提供公告与服务器信息，不会修改内置皮肤站认证地址。

## 发布服务端整合包

1. 在 HMCL 中准备好客户端游戏实例和模组。
2. 使用原生“导出整合包”，选择 HMCL 的服务端整合包格式，填写包名、作者、版本和文件下载地址 `fileApi`。
3. 解压导出的包，将 `server-manifest.json` 和 `overrides/` 原样上传至 `fileApi` 指向的目录。
4. 将该 `server-manifest.json` 的完整地址填入社团目录的 `manifestUrl`。它应当对应 `<fileApi>/server-manifest.json`。
5. 发布更新时先上传文件，再发布新清单，并更改清单的 `version`。同一版本号应始终代表同一份内容。

例如托管目录为：

```text
packs/survival/
  server-manifest.json
  overrides/
    mods/...
    config/...
```

清单由原生导出功能生成，包含游戏版本、加载器版本、文件路径和 SHA-1；不要把普通 ZIP 下载链接当作 `manifestUrl`。
首次安装后的文件下载和更新使用清单的 `fileApi`，需要保持该地址可用。
存档和个人文件不要列入发布清单；原生更新会删除从清单移除的已管理文件。
备份保存在对应游戏目录的 `backup/` 下。

## 构建与验证

需要 JDK 17 或更高版本。

```powershell
.\gradlew.bat build :HMCL:checkstyleMain :HMCL:checkstyleTest
```

构建产物位于 `HMCL/build/libs/`，包括 JAR 与 Windows EXE。

新增测试使用本机 HTTP 服务验证在线公告、文件更新、已修改文件保留、旧文件移除、更新失败回滚和版本号记录，不依赖真实社团发布源，也不启动 Minecraft。
