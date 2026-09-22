# 仓库指南 (Repository Guidelines)

> English version: [AGENTS.md](AGENTS.md)

## 项目结构与模块组织

MouseX 是单模块 Maven 项目（`com.mouseremapper:mousex`），通过 JavaFX 面板实现 Windows 平台专用的鼠标按键重映射。

- `src/main/java/com/mouseremapper/` — 全部应用代码（仅 5 个类，无下级包）
- `src/main/resources/` — `MouseX.png`、`MouseX.ico` 以及 `com/mouseremapper/styles.css`
- `pom.xml` — 依赖（JavaFX 21、JNA、Gson）与构建插件
- `package.xml` — Launch4j 打包 EXE 的配置模板
- `target/` — 构建产物（shaded JAR，约 20 MB）
- `profiles.json` — 用户配置，运行时写入进程工作目录（已 gitignore）
- `readme.md`、`guide.md`、`MouseX.md`、`release-notes/` — 用户与开发者文档

项目没有 `src/test` 目录，当前也没有任何自动化测试。

## 构建、测试与开发命令

```powershell
mvn clean package     # 编译并生成 target/mousex-1.0.0.jar（shaded fat JAR）
mvn javafx:run        # 通过 JavaFX Maven 插件启动面板
launch4jc package.xml # 在 mvn package 之后把 shaded JAR 包装为 MouseX.exe（Windows）
```

shaded JAR 的 manifest 入口是 `com.mouseremapper.Main`，它是对 `App.main` 的薄封装。

在 IDE 中调试时，请运行 **`com.mouseremapper.Main`**，不要运行 `App`。直接运行 `App` 会报 `JavaFX runtime components are missing`，因为 JVM 启动器会拒绝以 `Application` 子类作为主类，除非 JavaFX 位于 module path 上。

## 代码风格与命名约定

- Java 21，4 空格缩进，禁止使用 Tab。
- 类名 `PascalCase`，方法与字段 `camelCase`，常量 `UPPER_SNAKE_CASE`（如 `KEY_MAP`、`BUTTON_LABELS`）。
- 配置类使用 public 可变字段（参见 `HookManager.RemapConfig`），以便 Gson 序列化。
- 界面文案与内联样式字符串写在 `App.java`；可复用样式放入 `styles.css`。
- 项目未配置格式化或 lint 工具，请与周边代码保持一致。

## 提交与 Pull Request 规范

历史记录中 Conventional Commits（`feat(config):`、`docs(readme):`、`clean(config):`）与普通祈使句混用，推荐使用 `type(scope): 摘要` 的祈使句形式。

PR 需说明行为变更、注明对 Windows/macOS 的影响；任何面板或布局改动都应附截图。请关联相关 issue，并在用户可见行为变化时同步更新 `readme.md` / `guide.md`。

## 平台与配置注意事项

MouseX 仅支持 **Windows**：鼠标钩子（`WH_MOUSE_LL`）、`keybd_event`、注册表自启动以及 `EnumWindows` 均依赖 JNA Win32 API。在 macOS 上界面可以启动，但点击 `START HOOK` 会失败。切勿提交 `profiles.json`、`config.json`、`MouseX.xml` 或 `*.exe`。
