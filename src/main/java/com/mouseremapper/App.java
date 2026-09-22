package com.mouseremapper;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.PopupMenu;
import java.awt.MenuItem;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Psapi;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.*;

/**
 * MouseX 的 JavaFX 主界面，同时也是各模块之间的粘合层。
 *
 * <p>本类职责较重，大致分为四块：
 * <ol>
 *   <li><b>界面构建</b>：头部（Logo / 标题 / 档案选择 / 自启动开关）、中部 7 张按钮配置卡片、
 *       底部（状态灯 / 存档读档 / START HOOK）；</li>
 *   <li><b>状态同步</b>：把界面控件的变化写入 {@link HookManager}，或反向用配置刷新界面；</li>
 *   <li><b>前台窗口监控</b>：轮询当前前台进程名，自动切换到对应档案；</li>
 *   <li><b>系统托盘</b>：借助 AWT 托盘实现关闭窗口后后台常驻。</li>
 * </ol>
 *
 * <p>界面结构自上而下为：{@code headerContainer} → {@code scrollPane}（内含 7 张卡片）→ {@code footer}。
 */
public class App extends Application {

    /** 界面下拉框中「按键名称 → Windows 虚拟键码（VK）」的对照表；用 LinkedHashMap 保证显示顺序稳定。 */
    private static final Map<String, Integer> KEY_MAP = new LinkedHashMap<>();
    static {
        KEY_MAP.put("Tab", 9);
        KEY_MAP.put("Enter", 13);
        KEY_MAP.put("Esc", 27);
        KEY_MAP.put("Space", 32);
        // 字母键的 VK 码与其 ASCII 大写值相同
        for (char c = 'A'; c <= 'Z'; c++) {
            KEY_MAP.put(String.valueOf(c), (int) c);
        }
        // 数字键同理
        for (char c = '0'; c <= '9'; c++) {
            KEY_MAP.put(String.valueOf(c), (int) c);
        }
        // 方向键与修饰键的 VK 码无规律，需逐个硬编码
        KEY_MAP.put("Left", 37);
        KEY_MAP.put("Up", 38);
        KEY_MAP.put("Right", 39);
        KEY_MAP.put("Down", 40);
        KEY_MAP.put("Shift", 16);
        KEY_MAP.put("Ctrl", 17);
    }

    /** 7 张卡片对应的鼠标动作名称，下标 0 对应 1 号按钮。 */
    private static final String[] BUTTON_LABELS = {
            "Mouse Button 1 (Left Click)",
            "Mouse Button 2 (Right Click)",
            "Mouse Button 3 (Middle Click / Wheel Click)",
            "Mouse Button 4 (X1 / Back)",
            "Mouse Button 5 (X2 / Forward)",
            "Mouse Action 6 (Wheel Up)",
            "Mouse Action 7 (Wheel Down)"
    };

    /** 鼠标钩子管理器，负责真正的按键拦截与模拟。 */
    private final HookManager hookManager = new HookManager();
    /** 配置读写器，负责 profiles.json 的加载与保存。 */
    private final ConfigManager configManager = new ConfigManager(hookManager);

    /**
     * 单张卡片上全部控件的引用集合。
     *
     * <p>仅作为数据容器使用：构建界面时把创建的控件登记进来，
     * 之后 {@link #updateRemap(int)} 与 {@link #refreshUI()} 才能反向读写这些控件。
     */
    private static class ButtonCardControls {
        /** 是否启用该按钮的重映射。 */
        CheckBox enableCheck;
        /** 是否开启连发。 */
        CheckBox repeatCheck;
        /** 连发是否为点击切换模式。 */
        CheckBox untilClickCheck;
        /** 是否按和弦方式触发组合键。 */
        CheckBox chordCheck;
        /** 连发间隔滑杆。 */
        Slider repeatIntervalSlider;
        /** 显示当前连发间隔的文本标签。 */
        Label repeatIntervalLabel;
        /** 3 个按键槽位的勾选框，勾选表示该槽位参与映射。 */
        CheckBox[] slotChecks = new CheckBox[3];
        /** 3 个按键槽位对应的按键下拉框。 */
        @SuppressWarnings("unchecked")
        ComboBox<String>[] slotCombos = new ComboBox[3];
    }

    /** 7 张卡片的控件集合，下标 0 对应 1 号按钮。 */
    private final ButtonCardControls[] allCards = new ButtonCardControls[7];
    /**
     * 界面刷新重入保护标志。
     *
     * <p>程序化修改控件（勾选、选项、滑杆值）同样会触发控件的监听器，
     * 若不拦截就会把「刷新动作」误当成「用户输入」再写回配置，形成回环。
     * 因此刷新期间置为 true，{@link #updateRemap(int)} 见到即直接返回。
     */
    private boolean updatingUI = false;

    /** 全部档案：档案名（小写可执行文件名） → 按钮编号 → 该按钮配置。 */
    private Map<String, Map<Integer, HookManager.RemapConfig>> allProfiles = new HashMap<>();
    /** 当前生效的档案名，默认档案固定为 "Default"。 */
    private String activeProfileName = "Default";
    /** 头部档案下拉框，切换时同步切换当前档案。 */
    private ComboBox<String> profileCombo;

    /** 底部状态圆点：红色表示已停止，绿色表示钩子运行中。 */
    private Circle statusIndicator;
    /** 底部状态文字。 */
    private Label statusText;
    /** 启动/停止钩子的主按钮。 */
    private Button startStopBtn;
    /** 开机自启动勾选框。 */
    private CheckBox autostartCheck;

    /**
     * JavaFX 应用入口：构建整个界面并完成初始化。
     *
     * <p>执行顺序为：加载配置 → 构建头部 → 构建 7 张按钮卡片 → 构建底部控制栏
     * → 创建场景并设置图标 → 建立系统托盘 → 注册关闭事件。
     *
     * @param primaryStage 由 JavaFX 框架注入的主窗口
     */
    @Override
    public void start(Stage primaryStage) {
        // 关闭窗口时不结束 JVM，使程序可以继续驻留系统托盘并维持钩子运行
        Platform.setImplicitExit(false); // Keep running in background

        // 载入已保存的档案；若缺少默认档案则补一个全空的 Default
        allProfiles = configManager.loadProfiles();
        if (!allProfiles.containsKey("Default")) {
            Map<Integer, HookManager.RemapConfig> def = new HashMap<>();
            for (int i = 1; i <= 7; i++) def.put(i, new HookManager.RemapConfig());
            allProfiles.put("Default", def);
        }
        // 把默认档案推送到 HookManager，保证界面与钩子初始状态一致
        applyProfileToHook(activeProfileName);

        // 整个界面的根容器，自上而下依次为：头部、卡片滚动区、底部控制栏
        VBox root = new VBox(20);
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.TOP_CENTER);

        // ---------- 头部区域 ----------
        // --- Header Section ---
        HBox headerContainer = new HBox();
        headerContainer.setAlignment(Pos.CENTER_LEFT);
        headerContainer.setSpacing(20);

        // 左上角品牌区：主标题与副标题两行文字
        VBox titleContainer = new VBox(4);
        titleContainer.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label("MOUSEX");
        titleLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 24px; -fx-font-weight: 900; -fx-letter-spacing: 1px;");
        Label subtitleLabel = new Label("ABSOLUTE MOUSE CONTROL");
        subtitleLabel.setStyle("-fx-text-fill: #A0AEC0; -fx-font-size: 11px; -fx-font-weight: bold;");
        titleContainer.getChildren().addAll(titleLabel, subtitleLabel);

        // 加载头部 Logo；资源缺失时降级为不显示 Logo，避免整个界面构建失败
        ImageView logoView = null;
        try {
            Image logoImage = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/MouseX.png")));
            logoView = new ImageView(logoImage);
            logoView.setFitWidth(36);
            logoView.setFitHeight(36);
            logoView.setPreserveRatio(true);
        } catch (Exception e) {
            System.err.println("Could not load header logo: " + e.getMessage());
        }

        // 弹性空白占位区，把后面的档案选择与自启动开关推到窗口右侧
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ---------- 档案（Profile）管理 ----------
        // Profile Manager
        HBox profileBox = new HBox(8);
        profileBox.setAlignment(Pos.CENTER_LEFT);
        Label profileLbl = new Label("Profile:");
        profileLbl.setStyle("-fx-text-fill: #A0AEC0;");
        profileCombo = new ComboBox<>();
        profileCombo.getItems().addAll(allProfiles.keySet());
        profileCombo.setValue(activeProfileName);
        profileCombo.setOnAction(e -> {
            // 程序化设置选项同样会触发本回调，故用 updatingUI 拦截，避免重复应用档案
            if (updatingUI) return;
            activeProfileName = profileCombo.getValue();
            applyProfileToHook(activeProfileName);
            refreshUI();
        });
        // “+”按钮：扫描当前正在运行的可见窗口，为新进程新建一份档案
        Button addProfileBtn = new Button("+");
        addProfileBtn.getStyleClass().add("secondary-button");
        addProfileBtn.setOnAction(e -> {
            // 借助 TreeSet 去重并排序，保证下拉框中的条目顺序稳定
            Set<String> runningApps = new TreeSet<>();
            // 枚举所有顶层窗口，取出每个窗口所属进程的可执行文件名
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                // 只统计可见窗口，过滤掉大量隐藏的后台窗口
                if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                    IntByReference pid = new IntByReference();
                    User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
                    // 0x0400 = PROCESS_QUERY_INFORMATION，0x0010 = PROCESS_VM_READ；
                    // 读取其他进程的模块路径需要同时具备这两个权限
                    HANDLE process = Kernel32.INSTANCE.OpenProcess(0x0400 | 0x0010, false, pid.getValue());
                    if (process != null) {
                        char[] path = new char[1024];
                        // 读取进程主模块（exe）的完整路径，使用宽字符版本以兼容中文路径
                        int len = Psapi.INSTANCE.GetModuleFileNameExW(process, null, path, path.length);
                        // 句柄用完立即关闭，否则长时间运行会泄漏内核句柄
                        Kernel32.INSTANCE.CloseHandle(process);
                        if (len > 0) {
                            String fullPath = new String(path, 0, len);
                            // 仅保留文件名并统一转小写，作为档案键，与前台窗口检测的结果保持一致
                            int lastSlash = fullPath.lastIndexOf('\\');
                            String exe = lastSlash >= 0 ? fullPath.substring(lastSlash + 1).toLowerCase() : fullPath.toLowerCase();
                            runningApps.add(exe);
                        }
                    }
                }
                // 返回 true 表示继续枚举下一个窗口
                return true;
            }, null);

            // 排除已有档案的进程，只保留可以新建的条目
            List<String> appsList = new ArrayList<>(runningApps);
            appsList.removeAll(allProfiles.keySet());

            if (appsList.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "No new running applications found.");
                alert.showAndWait();
                return;
            }

            // 让用户从运行中的程序里挑选一个，作为新档案的名称
            ChoiceDialog<String> dialog = new ChoiceDialog<>(appsList.get(0), appsList);
            dialog.setTitle("New App Profile");
            dialog.setHeaderText("Select a running application");
            dialog.setContentText("Executable:");
            dialog.showAndWait().ifPresent(name -> {
                String p = name.toLowerCase().trim();
                if (!p.isEmpty() && !allProfiles.containsKey(p)) {
                    // 新档案的 7 个按钮全部使用默认（未启用重映射）配置
                    Map<Integer, HookManager.RemapConfig> def = new HashMap<>();
                    for (int i = 1; i <= 7; i++) def.put(i, new HookManager.RemapConfig());
                    allProfiles.put(p, def);
                    // 切换下拉框属于程序化改动，临时置位以防触发 onAction 回调
                    updatingUI = true;
                    profileCombo.getItems().add(p);
                    profileCombo.setValue(p);
                    updatingUI = false;
                    activeProfileName = p;
                    applyProfileToHook(p);
                    refreshUI();
                    // 新建档案后立即落盘，避免意外退出导致丢失
                    configManager.saveProfiles(allProfiles);
                }
            });
        });
        profileBox.getChildren().addAll(profileLbl, profileCombo, addProfileBtn);

        // ---------- 开机自启动开关 ----------
        // Autostart Checkbox
        autostartCheck = new CheckBox("Autostart on Boot");
        // 初始状态直接读取注册表，保证显示与实际设置一致
        autostartCheck.setSelected(Autostart.isAutostartEnabled());
        autostartCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            // 勾选即写入注册表，取消则删除对应键值
            if (newVal) {
                Autostart.enableAutostart();
            } else {
                Autostart.disableAutostart();
            }
        });

        // 组装头部：Logo 加载失败时省略该控件，其余布局保持不变
        if (logoView != null) {
            headerContainer.getChildren().addAll(logoView, titleContainer, spacer, profileBox, autostartCheck);
        } else {
            headerContainer.getChildren().addAll(titleContainer, spacer, profileBox, autostartCheck);
        }

        // ---------- 可滚动的按钮映射卡片区 ----------
        // --- Scrollable Mappings Grid ---
        VBox cardsContainer = new VBox(16);
        cardsContainer.setPadding(new Insets(4, 12, 12, 4));
        cardsContainer.setAlignment(Pos.TOP_CENTER);

        // 依次创建 7 张卡片；buttonIndex 从 1 开始，用于取按钮名称与配置
        for (int i = 0; i < 7; i++) {
            final int buttonIndex = i + 1;
            VBox card = createButtonCard(buttonIndex);
            cardsContainer.getChildren().add(card);
        }

        // 卡片较多时需要滚动；fitToWidth 让卡片宽度随窗口自适应
        ScrollPane scrollPane = new ScrollPane(cardsContainer);
        scrollPane.setFitToWidth(true);
        // 让滚动区占满头部与底部之间的全部剩余高度
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // ---------- 底部控制栏 ----------
        // --- Footer Control Panel ---
        HBox footer = new HBox(20);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(12, 0, 0, 0));

        // 左侧状态指示：圆点 + 状态文字
        // Status Indicator
        HBox statusBox = new HBox(8);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        statusIndicator = new Circle(6);
        statusIndicator.getStyleClass().add("status-indicator");
        statusText = new Label("Stopped");
        statusText.setStyle("-fx-text-fill: #A0AEC0; -fx-font-weight: bold; -fx-font-size: 13px;");
        statusBox.getChildren().addAll(statusIndicator, statusText);
        // 初始化渲染为“已停止”，后续由 toggleHook 与 refreshUI 更新
        updateStatusVisuals(false);

        // 弹性空白，把按钮组整体推到右侧
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        // 右侧三个操作按钮：读取档案、保存档案、启动/停止钩子
        // Buttons
        startStopBtn = new Button("START HOOK");
        startStopBtn.getStyleClass().add("action-button");
        startStopBtn.setOnAction(e -> toggleHook());

        // 把当前内存中的全部档案覆盖写入 profiles.json
        Button saveBtn = new Button("Save Preset");
        saveBtn.getStyleClass().add("secondary-button");
        saveBtn.setOnAction(e -> configManager.saveProfiles(allProfiles));

        // 从磁盘重新载入档案，覆盖当前内存状态
        Button loadBtn = new Button("Load Preset");
        loadBtn.getStyleClass().add("secondary-button");
        loadBtn.setOnAction(e -> {
            allProfiles = configManager.loadProfiles();
            // 原激活档案可能已不存在（例如手工删除过配置项），此时回退到 Default
            if (!allProfiles.containsKey(activeProfileName)) activeProfileName = "Default";
            updatingUI = true;
            profileCombo.getItems().setAll(allProfiles.keySet());
            profileCombo.setValue(activeProfileName);
            updatingUI = false;
            applyProfileToHook(activeProfileName);
            refreshUI();
        });

        // 左状态、右按钮，中间用弹簧占位
        footer.getChildren().addAll(statusBox, footerSpacer, loadBtn, saveBtn, startStopBtn);

        // 三段式主布局：头部固定、中间可滚动、底部固定
        root.getChildren().addAll(headerContainer, scrollPane, footer);

        // 初始窗口宽度取屏幕可视宽度的 55%，高度固定为 640
        // Start with 50% screen width + 10% increase (55% total)
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double startWidth = screenBounds.getWidth() * 0.55;

        Scene scene = new Scene(root, startWidth, 640);
        // 挂载外部样式表，卡片与按钮的外观全部由 styles.css 定义
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/mouseremapper/styles.css")).toExternalForm());

        // 用已载入的配置刷新界面控件，使其反映当前实际设置
        // Initialize UI settings from the loaded config
        refreshUI();

        // 启动前台窗口轮询线程，用于按活动窗口自动切换档案
        startActiveWindowMonitor();

        primaryStage.setTitle("MouseX");
        try {
            // 设置窗口与任务栏图标；资源缺失时仅告警，不影响功能
            primaryStage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/MouseX.png"))));
        } catch (Exception e) {
            System.err.println("Could not load application stage icon: " + e.getMessage());
        }
        primaryStage.setScene(scene);
        // 限制窗口最小尺寸，防止用户拖得过小导致卡片布局错乱
        primaryStage.setMinWidth(682);
        primaryStage.setMinHeight(500);
        
        // 建立系统托盘图标与右键菜单（关闭窗口后仍可从这里唤回）
        setupSystemTray(primaryStage);
        
        primaryStage.setOnCloseRequest(e -> {
            // 拦截默认关闭行为，改为隐藏窗口，让程序继续驻留托盘并保持钩子运行
            e.consume(); // Prevent default exit
            // 最小化到系统托盘
            primaryStage.hide(); // Minimize to tray
        });
        primaryStage.show();
    }

    /**
     * 获取当前前台窗口所属进程的可执行文件名（小写），用作档案键。
     *
     * <p>调用链为：{@code GetForegroundWindow} 取前台窗口 →
     * {@code GetWindowThreadProcessId} 取进程 ID →
     * {@code OpenProcess} 打开进程 → {@code GetModuleFileNameExW} 读主模块路径。
     *
     * <p>任一环节失败（无前台窗口、权限不足等）都会回退为 {@code "Default"}，
     * 保证调用方总能拿到一个合法的档案名。
     *
     * @return 进程可执行文件名（如 {@code chrome.exe}），失败时返回 {@code "Default"}
     */
    private String getForegroundProcessName() {
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (hwnd == null) return "Default";
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        // 读取其他进程信息需要 PROCESS_QUERY_INFORMATION(0x0400) 与 PROCESS_VM_READ(0x0010) 权限
        HANDLE process = Kernel32.INSTANCE.OpenProcess(0x0400 | 0x0010, false, pid.getValue());
        if (process == null) return "Default";
        char[] path = new char[1024];
        int len = Psapi.INSTANCE.GetModuleFileNameExW(process, null, path, path.length);
        // 拿到路径后立即释放句柄，避免轮询过程中持续泄漏内核句柄
        Kernel32.INSTANCE.CloseHandle(process);
        if (len == 0) return "Default";
        String fullPath = new String(path, 0, len);
        // 只保留文件名并转小写，与新建档案时的命名规则保持一致
        int lastSlash = fullPath.lastIndexOf('\\');
        if (lastSlash >= 0) return fullPath.substring(lastSlash + 1).toLowerCase();
        return fullPath.toLowerCase();
    }

    /**
     * 启动前台窗口监控线程，实现「切换应用即自动切换档案」。
     *
     * <p>采用 500ms 轮询而非系统事件通知，实现简单但有最多半秒的切换延迟。
     *
     * <p>线程安全：{@code allProfiles} 与 {@code activeProfileName} 被本线程与
     * JavaFX 线程共同访问，此处未做同步保护；界面更新一律通过
     * {@link Platform#runLater(Runnable)} 切回 JavaFX 线程执行。
     */
    private void startActiveWindowMonitor() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                    // 仅在钩子处于活动状态时才切换档案
                    if (!hookManager.isHookActive()) continue; // Only switch if hook is active

                    // 前台进程若没有对应档案，则回退到 Default
                    String exe = getForegroundProcessName();
                    String targetProfile = allProfiles.containsKey(exe) ? exe : "Default";

                    // 仅在档案真正发生变化时才更新，避免每轮都触发全量刷新
                    if (!targetProfile.equals(activeProfileName)) {
                        activeProfileName = targetProfile;
                        applyProfileToHook(activeProfileName);
                        
                        // 界面控件只能在 JavaFX 线程上修改，故切回 FX 线程同步下拉框
                        Platform.runLater(() -> {
                            updatingUI = true;
                            profileCombo.setValue(activeProfileName);
                            updatingUI = false;
                            refreshUI();
                        });
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "ActiveWindowMonitor");
        // 守护线程，主窗口退出后不会阻止 JVM 结束
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * 把指定档案的全部按钮配置推送到 {@link HookManager}。
     *
     * <p>注意这是「整份覆盖」语义：HookManager 持有的配置永远等于当前档案的内容，
     * 切换档案等同于把新档案的 7 个按钮配置全部重新写入一遍。
     *
     * @param profileName 档案名；不存在时静默返回
     */
    private void applyProfileToHook(String profileName) {
        Map<Integer, HookManager.RemapConfig> profile = allProfiles.get(profileName);
        if (profile == null) return;
        for (Map.Entry<Integer, HookManager.RemapConfig> entry : profile.entrySet()) {
            HookManager.RemapConfig cfg = entry.getValue();
            hookManager.setRemap(entry.getKey(), cfg.virtualKeys, cfg.isRemapped, cfg.repeatEnabled, cfg.repeatUntilClick, cfg.repeatIntervalMs, cfg.isChord);
        }
    }

    /**
     * 创建系统托盘使用的图标。
     *
     * <p>优先读取项目自带的 PNG；读取失败时用代码绘制一个紫色小方块兜底，
     * 确保托盘图标不会因为资源缺失而变成空白。
     *
     * @return AWT 图像对象，供 {@link TrayIcon} 使用
     */
    private java.awt.Image createTrayIconImage() {
        try {
            return javax.imageio.ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream("/MouseX.png")));
        } catch (Exception e) {
            // 兜底分支：直接绘制 16x16 的紫色方块
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2d = img.createGraphics();
            g2d.setColor(java.awt.Color.decode("#8B5CF6")); // Purple
            g2d.fillRect(0, 0, 16, 16);
            g2d.setColor(java.awt.Color.WHITE);
            g2d.drawRect(0, 0, 15, 15);
            // 释放图形上下文，避免占用本地绘图资源
            g2d.dispose();
            return img;
        }
    }

    /**
     * 建立系统托盘图标与右键菜单（打开主界面 / 切换钩子 / 退出）。
     *
     * <p>托盘基于 AWT 实现（JavaFX 本身不提供托盘支持），因此菜单回调运行在
     * AWT 事件线程上；任何涉及界面的操作都必须用
     * {@link Platform#runLater(Runnable)} 切回 JavaFX 线程，否则会抛异常。
     *
     * @param primaryStage 主窗口，用于从托盘唤回界面
     */
    private void setupSystemTray(Stage primaryStage) {
        if (SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();
            
            PopupMenu popup = new PopupMenu();
            MenuItem openItem = new MenuItem("Open Dashboard");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                primaryStage.show();
                primaryStage.toFront();
            }));
            
            // 直接从托盘切换钩子开关，无需先打开主界面
            MenuItem toggleItem = new MenuItem("Toggle Hook");
            toggleItem.addActionListener(e -> Platform.runLater(this::toggleHook));
            
            // 退出前先卸载钩子，避免在系统中残留全局钩子
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                hookManager.stopHook();
                Platform.exit();
                System.exit(0);
            });
            
            popup.add(openItem);
            popup.add(toggleItem);
            popup.addSeparator();
            popup.add(exitItem);
            
            // 双击托盘图标唤回主界面
            TrayIcon trayIcon = new TrayIcon(createTrayIconImage(), "MouseX", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                primaryStage.show();
                primaryStage.toFront();
            })); // Double click to open
            
            try {
                // 注册图标；个别桌面环境或虚拟机可能拒绝添加托盘图标
                tray.add(trayIcon);
            } catch (java.awt.AWTException e) {
                System.err.println("TrayIcon could not be added.");
            }
        }
    }

    /**
     * 为单个鼠标按钮创建一张配置卡片。
     *
     * <p>卡片内部自上而下包含三部分：标题、3 个按键映射槽位、以及一组控制开关
     * （启用重映射 / 和弦 / 连发 / 连发间隔）。
     *
     * <p>所有控件都注册了监听器，一旦用户改动就调用 {@link #updateRemap(int)}
     * 把最新状态写回配置。为避免用户乱改，部分控件之间建立了联动禁用：
     * 未勾选槽位时下拉框不可用，未开启连发时「连发直到点击」与间隔滑杆不可用。
     *
     * @param buttonIndex 按钮编号（1~7）
     * @return 组装完成的卡片容器
     */
    private VBox createButtonCard(int buttonIndex) {
        VBox card = new VBox(12);
        card.getStyleClass().add("button-card");

        // 卡片标题，取自按钮名称表
        // Card Title
        Label title = new Label(BUTTON_LABELS[buttonIndex - 1]);
        title.getStyleClass().add("card-title");
        card.getChildren().add(title);

        // 登记本卡片的控件引用，供后续读写与刷新使用
        ButtonCardControls controls = new ButtonCardControls();
        allCards[buttonIndex - 1] = controls;

        // 一行内并排 3 个按键槽位，每个槽位由「勾选框 + 按键下拉框」组成
        // Slots for 3 key remappings
        HBox slotsContainer = new HBox(16);
        slotsContainer.setAlignment(Pos.CENTER_LEFT);

        for (int j = 0; j < 3; j++) {
            final int slotIdx = j;
            HBox slotRow = new HBox(6);
            slotRow.setAlignment(Pos.CENTER_LEFT);

            CheckBox slotCheck = new CheckBox("Slot " + (j + 1));
            // 勾选/取消勾选该槽位时，重新汇总本按钮的按键列表
            slotCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateRemap(buttonIndex));

            ComboBox<String> slotCombo = new ComboBox<>();
            slotCombo.getItems().addAll(KEY_MAP.keySet());
            slotCombo.getSelectionModel().select(0);
            // 切换按键选项时同样需要同步到配置
            slotCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateRemap(buttonIndex));
            slotCombo.setPrefWidth(100);

            // 槽位未勾选时禁用下拉框，避免产生「选了键但未启用」的困惑
            // Disable combo box if slot is not checked
            slotCombo.disableProperty().bind(slotCheck.selectedProperty().not());

            slotRow.getChildren().addAll(slotCheck, slotCombo);
            slotsContainer.getChildren().add(slotRow);

            // 保存引用，refreshUI 时需要反向设置这些控件的状态
            controls.slotChecks[j] = slotCheck;
            controls.slotCombos[j] = slotCombo;
        }
        card.getChildren().add(slotsContainer);

        // 控制开关行：启用重映射 / 和弦 / 连发 / 连发直到点击 / 间隔滑杆
        // Control Settings Row (Enable, Repeat, Until Click)
        HBox settingsRow = new HBox(20);
        settingsRow.setAlignment(Pos.CENTER_LEFT);
        settingsRow.setPadding(new Insets(4, 0, 0, 0));

        // 四个开关共用同一套逻辑：状态变化即写回配置
        controls.enableCheck = new CheckBox("Enable remap");
        controls.enableCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateRemap(buttonIndex));

        controls.repeatCheck = new CheckBox("Enable repeat");
        controls.repeatCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateRemap(buttonIndex));

        controls.untilClickCheck = new CheckBox("Repeat until click");
        controls.untilClickCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateRemap(buttonIndex));

        controls.chordCheck = new CheckBox("As Chord");
        controls.chordCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateRemap(buttonIndex));

        controls.repeatIntervalSlider = new Slider(10, 1000, 100);
        controls.repeatIntervalSlider.setPrefWidth(100);
        controls.repeatIntervalLabel = new Label("100ms");
        controls.repeatIntervalLabel.setStyle("-fx-text-fill: #A0AEC0; -fx-font-size: 11px;");
        
        // 拖动滑杆时同步更新旁边的数值文本，并写回配置
        controls.repeatIntervalSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            controls.repeatIntervalLabel.setText(newVal.intValue() + "ms");
            updateRemap(buttonIndex);
        });

        // 滑杆与数值标签作为一个整体，随「启用连发」的勾选状态显示或隐藏
        HBox sliderBox = new HBox(6, controls.repeatIntervalSlider, controls.repeatIntervalLabel);
        sliderBox.setAlignment(Pos.CENTER_LEFT);

        // 滚轮（6/7 号动作）没有「按住抬起」的概念，因此不支持连发相关选项
        if (buttonIndex == 6 || buttonIndex == 7) {
            controls.repeatCheck.setVisible(false);
            controls.untilClickCheck.setVisible(false);
            sliderBox.setVisible(false);
        } else {
            // 未启用连发时，禁用「连发直到点击」选项
            // Disable until click checkbox if repeat is not enabled
            controls.untilClickCheck.disableProperty().bind(controls.repeatCheck.selectedProperty().not());
            // 滑杆仅在启用连发后显示
            sliderBox.visibleProperty().bind(controls.repeatCheck.selectedProperty());
        }

        // 按「先开关后参数」的顺序排列，滑杆置于最右
        settingsRow.getChildren().addAll(controls.enableCheck, controls.chordCheck, controls.repeatCheck, controls.untilClickCheck, sliderBox);
        card.getChildren().add(settingsRow);

        return card;
    }

    /**
     * 把某张卡片上的界面状态写回配置，并立即推送到钩子。
     *
     * <p>这是「界面 → 配置」方向的核心方法，卡片上所有控件的监听器最终都会走到这里。
     * 开头的 {@code updatingUI} 判断用于拦截程序化刷新引发的伪触发，
     * 否则 {@link #refreshUI()} 会与本方法形成相互触发的回环。
     *
     * @param buttonIndex 按钮编号（1~7）
     */
    private void updateRemap(int buttonIndex) {
        // 当前正在程序化刷新界面，控件的变化并非用户操作，直接忽略
        if (updatingUI) return;

        ButtonCardControls controls = allCards[buttonIndex - 1];
        if (controls == null) return;

        // 汇总 3 个槽位中已勾选的按键，列表顺序即最终模拟按键的顺序
        List<Integer> keys = new ArrayList<>();
        for (int j = 0; j < 3; j++) {
            if (controls.slotChecks[j].isSelected()) {
                String selectedKey = controls.slotCombos[j].getValue();
                Integer vkCode = KEY_MAP.get(selectedKey);
                if (vkCode != null) {
                    keys.add(vkCode);
                }
            }
        }

        // 配置写入「当前激活档案」，之后再整体推送给 HookManager
        Map<Integer, HookManager.RemapConfig> currentProfile = allProfiles.get(activeProfileName);
        if (currentProfile == null) return;
        
        // 取出该按钮的配置对象；缺失时补一个默认配置（旧配置文件可能不含全部按钮）
        HookManager.RemapConfig cfg = currentProfile.get(buttonIndex);
        if (cfg == null) {
            cfg = new HookManager.RemapConfig();
            currentProfile.put(buttonIndex, cfg);
        }
        
        // 把界面上的各开关与滑杆值逐项同步到配置对象
        cfg.virtualKeys = new ArrayList<>(keys);
        cfg.isRemapped = controls.enableCheck.isSelected();
        cfg.repeatEnabled = controls.repeatCheck.isSelected();
        cfg.repeatUntilClick = controls.untilClickCheck.isSelected();
        cfg.repeatIntervalMs = (int) controls.repeatIntervalSlider.getValue();
        cfg.isChord = controls.chordCheck.isSelected();

        // 立即生效，无需用户额外点击保存
        applyProfileToHook(activeProfileName);
    }

    /**
     * 用 HookManager 中的当前配置刷新全部卡片控件（配置 → 界面方向）。
     *
     * <p>整个过程被 {@code updatingUI} 包裹：期间控件产生的变化事件会被
     * {@link #updateRemap(int)} 忽略，避免刷新动作被误当作用户输入再写回配置。
     * 使用 try/finally 是为了保证即使中途抛异常也能复位该标志。
     */
    private void refreshUI() {
        // 进入静默刷新模式，拦截控件监听器回调
        updatingUI = true;
        try {
            // 此处读取的是 HookManager 的配置，它始终对应当前激活档案
            Map<Integer, HookManager.RemapConfig> config = hookManager.getConfig();
            for (int i = 0; i < 7; i++) {
                HookManager.RemapConfig cfg = config.get(i + 1);
                ButtonCardControls controls = allCards[i];
                if (cfg == null || controls == null) continue;

                // 开关与滑杆可直接按配置赋值，顺序无关
                controls.enableCheck.setSelected(cfg.isRemapped);
                controls.repeatCheck.setSelected(cfg.repeatEnabled);
                controls.untilClickCheck.setSelected(cfg.repeatUntilClick);
                controls.chordCheck.setSelected(cfg.isChord);
                controls.repeatIntervalSlider.setValue(cfg.repeatIntervalMs);

                // 先清空 3 个槽位，再按配置重新填充
                // Clear slots first
                for (int j = 0; j < 3; j++) {
                    controls.slotChecks[j].setSelected(false);
                    controls.slotCombos[j].getSelectionModel().select(0);
                }

                // 按键列表最多 3 个，超出部分会被忽略（界面只有 3 个槽位）
                // Fill from config
                List<Integer> keys = cfg.virtualKeys;
                for (int j = 0; j < keys.size() && j < 3; j++) {
                    int vk = keys.get(j);
                    // 反查虚拟键码对应的按键名称；未收录的键码会被跳过
                    String keyName = getKeyNameByCode(vk);
                    if (keyName != null) {
                        controls.slotChecks[j].setSelected(true);
                        controls.slotCombos[j].getSelectionModel().select(keyName);
                    }
                }
            }
        } finally {
            // 无论成功与否都必须退出静默模式，否则界面将永久无法响应输入
            updatingUI = false;
        }
    }

    /**
     * 按虚拟键码反查按键名称，用于把配置渲染回下拉框。
     *
     * <p>采用线性遍历，但 KEY_MAP 规模很小（不足 60 项），性能可以忽略。
     *
     * @param code Windows 虚拟键码
     * @return 对应的按键名称；未收录的键码返回 {@code null}
     */
    private String getKeyNameByCode(int code) {
        for (Map.Entry<String, Integer> entry : KEY_MAP.entrySet()) {
            if (entry.getValue() == code) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 切换鼠标钩子的运行状态，并同步更新按钮文字与状态指示。
     *
     * <p>源自主按钮与托盘右键菜单两处，因此必须运行在 JavaFX 线程上
     * （托盘回调已用 {@code Platform.runLater} 包装）。
     */
    private void toggleHook() {
        // 已在运行则执行停止，并把按钮还原为初始外观
        if (hookManager.isHookActive()) {
            hookManager.stopHook();
            startStopBtn.setText("START HOOK");
            // 传入空字符串即撤销内联样式，回退到 CSS 默认样式
            startStopBtn.setStyle(""); // Reverts to CSS default
            updateStatusVisuals(false);
        } else {
            // 启动钩子，并把按钮改为醒目的红色渐变，提示再次点击将停止
            hookManager.startHook();
            startStopBtn.setText("STOP HOOK");
            startStopBtn.setStyle("-fx-background-color: linear-gradient(to right, #EF4444, #DC2626); -fx-effect: dropshadow(three-pass-box, rgba(239, 68, 68, 0.4), 10, 0, 0, 4);");
            updateStatusVisuals(true);
        }
    }

    /**
     * 更新底部状态圆点与文字的样式。
     *
     * <p>颜色直接以内联样式设置：运行中为绿色并带绿色光晕，已停止为红色并带红色光晕。
     *
     * @param running 钩子是否正在运行
     */
    private void updateStatusVisuals(boolean running) {
        if (running) {
            // 绿色：钩子已安装，重映射正在生效
            statusIndicator.setStyle("-fx-fill: #10B981; -fx-effect: dropshadow(three-pass-box, rgba(16, 185, 129, 0.6), 10, 0, 0, 0);");
            statusText.setText("Running");
            statusText.setStyle("-fx-text-fill: #10B981; -fx-font-weight: bold;");
        } else {
            // 红色：钩子未运行，鼠标行为与系统默认一致
            statusIndicator.setStyle("-fx-fill: #EF4444; -fx-effect: dropshadow(three-pass-box, rgba(239, 68, 68, 0.6), 10, 0, 0, 0);");
            statusText.setText("Stopped");
            statusText.setStyle("-fx-text-fill: #EF4444; -fx-font-weight: bold;");
        }
    }

    /**
     * JavaFX 应用的标准入口，内部交由框架完成启动流程。
     *
     * <p>注意：若直接以本类作为主类启动，JVM 会要求 JavaFX 处于 module path 上，
     * 否则会报 "JavaFX runtime components are missing"。打包运行请使用
     * {@link Main}，它不继承 Application，可以绕过该限制。
     *
     * @param args 命令行参数，由 JavaFX 转交给 Application 实例
     */
    public static void main(String[] args) {
        launch(args);
    }
}
