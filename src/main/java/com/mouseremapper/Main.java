package com.mouseremapper;

/**
 * 打包产物（shaded fat JAR）的统一启动入口。
 *
 * <p>为什么不直接用 {@link App} 作为入口？因为 JavaFX 的 JVM 启动器有一项硬性检查：
 * 当主类是 {@code javafx.application.Application} 的子类时，JVM 会要求 JavaFX 必须位于
 * module path 上；而我们只把 JavaFX 放在 classpath 里，于是会直接抛出
 * "JavaFX runtime components are missing, and are required to run this application"。
 *
 * <p>用一个不继承 Application 的普通类作为入口即可绕过该检查，
 * 由它在 main 中转发给 {@code App.main}，其余逻辑不变。
 *
 * <p>因此：IDE 里运行、以及开机自启命令，都应指向本类而非 {@code App}。
 */
public class Main {
    /**
     * 转发入口参数给 JavaFX 应用的真正入口。
     *
     * @param args 命令行参数，原样透传给 {@link App#main(String[])}
     */
    public static void main(String[] args) {
        App.main(args);
    }
}
