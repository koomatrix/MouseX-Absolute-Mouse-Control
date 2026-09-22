package com.mouseremapper;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.File;

/**
 * Windows 开机自启动管理器。
 *
 * <p>实现方式是把启动命令写入当前用户注册表项
 * {@code HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run}。
 * 选用 HKCU 而非 HKLM 的原因是不需要管理员权限，且只对当前用户生效。
 *
 * <p>注意：写 registry 会失败于非 Windows 平台（JNA 无法加载 advapi32），
 * 因此所有方法都做了异常兜底，读操作失败时统一返回 {@code false}。
 */
public class Autostart {
    /** 当前用户的自启动注册表路径。 */
    private static final String REG_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    /** 注册表中使用的键名，也是卸载时用于定位的标识。 */
    private static final String KEY_NAME = "MouseRemapper";

    /**
     * 注册开机自启动。
     *
     * <p>会依据当前运行形态生成不同的启动命令：
     * 打包成 JAR 时用 {@code javaw}（无控制台窗口）；
     * 从 IDE 或 class 目录运行时退化为 {@code java -cp <目录> ...}。
     */
    public static void enableAutostart() {
        try {
            // Get the absolute path to the current running application (likely a JAR or build folder)
            // 获取当前程序运行位置的绝对路径（通常是 JAR 文件，或 IDE 的 classes 目录）
            String jarPath = new File(Autostart.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
                    .getAbsolutePath();

            String command;
            if (jarPath.endsWith(".jar")) {
                // If running as a packaged JAR, run it with javaw to avoid showing a console window
                // 若以打包 JAR 方式运行，则用 javaw 启动，避免弹出黑色控制台窗口
                command = "javaw -jar \"" + jarPath + "\"";
            } else {
                // Otherwise run the current classpath target
                // 否则以 classpath 方式启动当前目录下的类
                command = "java -cp \"" + jarPath + "\" com.mouseremapper.App";
            }

            // 写入注册表 Run 项，Windows 登录时会自动执行该命令
            Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    REG_PATH,
                    KEY_NAME,
                    command
            );
            System.out.println("Autostart successfully registered in Windows Registry: " + command);
        } catch (Exception e) {
            System.err.println("Failed to register autostart: " + e.getMessage());
        }
    }

    /**
     * 取消开机自启动：删除注册表中的 Run 键值。
     *
     * <p>删除前先判断键值是否存在，避免键不存在时抛异常。
     */
    public static void disableAutostart() {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REG_PATH, KEY_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, REG_PATH, KEY_NAME);
                System.out.println("Autostart unregistered from Windows Registry.");
            }
        } catch (Exception e) {
            System.err.println("Failed to unregister autostart: " + e.getMessage());
        }
    }

    /**
     * 查询当前是否已开启开机自启动。
     *
     * @return 注册表中存在对应键值则返回 {@code true}；查询异常（如非 Windows 平台）返回 {@code false}
     */
    public static boolean isAutostartEnabled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REG_PATH, KEY_NAME);
        } catch (Exception e) {
            return false;
        }
    }
}
