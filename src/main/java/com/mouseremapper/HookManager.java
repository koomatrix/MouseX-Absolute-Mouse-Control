package com.mouseremapper;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.HHOOK;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows 全局鼠标钩子的核心管理器（仅支持 Windows）。
 *
 * <p>本类做三件事：
 * <ol>
 *   <li>安装 / 卸载 {@code WH_MOUSE_LL} 低级鼠标钩子，并在专用线程上维持 Win32 消息循环；</li>
 *   <li>在钩子回调中识别是哪个鼠标按钮，按配置决定「拦截原生事件」还是「放行」；</li>
 *   <li>把被拦截的按钮转换键盘按键（含连发与和弦两种触发方式）后回注给系统。</li>
 * </ol>
 *
 * <p>线程模型：钩子回调发生在 {@code MouseHookThread} 线程上，而配置会被 JavaFX 线程修改，
 * 因此对 {@code config} 的读写都用 {@code synchronized(this)} 保护。
 */
public class HookManager {

    /**
     * 单个鼠标按钮的重映射配置。
     *
     * <p>字段全部 public 且可变，一方面是 Gson 序列化的需要，
     * 另一方面 {@link com.mouseremapper.ConfigManager} 会直接按字段拷贝。
     */
    public static class RemapConfig {
        /** 映射到的键盘虚拟键码列表；长度为 1 时是单键，大于 1 时可构成组合键。 */
        public List<Integer> virtualKeys = new ArrayList<>();
        /** 是否启用该按钮的重映射；为 false 时事件原样放行。 */
        public boolean isRemapped = false;
        /** 是否开启连发：按住期间按固定间隔重复触发。 */
        public boolean repeatEnabled = false;
        /** 连发是否为切换模式：点一次开始连发，再点一次停止（否则松手即停）。 */
        public boolean repeatUntilClick = false;
        /** 连发间隔（毫秒），UI 滑杆范围为 10~1000。 */
        public int repeatIntervalMs = 100;
        /** 是否以和弦方式触发：先依次按下所有键，再依次松开；否则每个键按下即松开。 */
        public boolean isChord = false;
    }

    // 手写 MSLLHOOKSTRUCT 结构体定义，确保在所有 JNA 版本下都能公开访问到全部字段
    // Custom MSLLHOOKSTRUCT definition to ensure full public access across all JNA versions
    /**
     * 钩子回调参数 {@code lParam} 指向的结构体，描述本次鼠标事件。
     *
     * <p>之所以不用 JNA 自带的同名类，是因为其字段可见性在不同 JNA 版本间存在差异，
     * 而我们需要读取 {@code mouseData} 来区分侧键（X1/X2）与滚轮方向，只能自行声明。
     */
    public static class MyMSLLHOOKSTRUCT extends Structure {
        /** 事件发生时的屏幕坐标。 */
        public POINT pt;
        /** 鼠标数据：侧键时为高位 XBUTTON 编号，滚轮时为高位有符号滚动量。 */
        public int mouseData;
        /** 事件标志位（如注入标记）。 */
        public int flags;
        /** 事件时间戳（毫秒）。 */
        public int time;
        /** 附加信息，用于区分是否为自身注入的事件。 */
        public ULONG_PTR dwExtraInfo;

        /**
         * 从原生指针构造结构体，并立即按字段顺序读取内存内容。
         *
         * @param p Win32 回调传入的 {@code lParam} 指针
         */
        public MyMSLLHOOKSTRUCT(Pointer p) {
            super(p);
            read();
        }

        /**
         * 显式声明字段顺序。
         *
         * <p>JNA 依据该顺序计算各字段的内存偏移，顺序必须与字段声明顺序严格一致，
         * 否则解析出的坐标、mouseData 等值会全部错位。
         */
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("pt", "mouseData", "flags", "time", "dwExtraInfo");
        }
    }

    // 自定义回调接口，规避不同 JNA 版本间平台类的差异
    // Custom Callback interface to bypass any platform class version differences
    /**
     * 钩子过程（HOOKPROC）的 JNA 映射。
     *
     * <p>必须继承 {@code StdCallCallback}，因为 Windows 钩子回调使用 stdcall 调用约定，
     * 用错约定在 32 位 JVM 上会导致栈失衡崩溃。
     */
    public interface MyHOOKPROC extends com.sun.jna.win32.StdCallLibrary.StdCallCallback {
        /**
         * @param nCode 钩子处理码，小于 0 时表示必须无条件透传给下一个钩子
         * @param wParam 事件类型（如 WM_LBUTTONDOWN）
         * @param lParam 指向 {@link MyMSLLHOOKSTRUCT} 的指针
         * @return 返回 1 拦截事件，返回 {@code CallNextHookEx} 的结果表示放行
         */
        LRESULT callback(int nCode, WPARAM wParam, LPARAM lParam);
    }

    // 自定义 user32 接口，补全 JNA 标准平台类中未覆盖的函数
    // Define custom interface for user32 features not fully in JNA standard platform class
    /**
     * user32.dll 的部分函数映射，在 JNA 自带 {@code User32} 基础上补充了
     * {@code SetWindowsHookEx} 与 {@code keybd_event} 两个声明。
     */
    public interface MyUser32 extends com.sun.jna.platform.win32.User32 {
        /** JNA 动态代理实例，首次访问时加载 user32.dll。 */
        MyUser32 INSTANCE = Native.load("user32", MyUser32.class, W32APIOptions.DEFAULT_OPTIONS);
        
        /**
         * 安装钩子。
         *
         * @param idHook 钩子类型，{@code WH_MOUSE_LL} 表示低级鼠标钩子
         * @param lpfn 回调函数
         * @param hmod 当前模块句柄，低级钩子传 {@code null} 亦可
         * @param dwThreadId 目标线程 ID，0 表示全局钩子
         * @return 钩子句柄，失败返回 null
         */
        HHOOK SetWindowsHookEx(int idHook, MyHOOKPROC lpfn, HMODULE hmod, int dwThreadId);
        /**
         * 模拟一次键盘输入（按下或抬起）。
         *
         * @param bVk 虚拟键码
         * @param bScan 硬件扫描码，通常传 0
         * @param dwFlags 0 表示按下，2（KEYEVENTF_KEYUP）表示抬起
         * @param dwExtraInfo 附加信息，通常传 0
         */
        void keybd_event(byte bVk, byte bScan, int dwFlags, int dwExtraInfo);
    }

    /** 当前生效的按钮配置，键为 1~7 号鼠标按钮；仅反映「当前激活档案」的快照。 */
    private final Map<Integer, RemapConfig> config = new HashMap<>();
    /** 每个按钮的连发开关状态，下标 0 对应 1 号按钮；用原子类型以便跨线程可见。 */
    private final AtomicBoolean[] repeatActive = new AtomicBoolean[7];
    /** 钩子句柄，未安装时为 null。 */
    private HHOOK hHook = null;
    /** 承载钩子与消息循环的线程 ID，停止时需要用它投递 WM_QUIT。 */
    private int hookThreadId = 0;
    /** 钩子是否处于活动状态；volatile 保证连发线程能及时看到停止信号。 */
    private volatile boolean hookActive = false;

    // 持有强引用，防止回调对象被 JVM 垃圾回收（否则原生侧回调会指向已释放的内存）
    // Strong reference to prevent the callback from being garbage collected by JVM
    /**
     * 钩子回调的持有者。
     *
     * <p>即使内部逻辑完全不引用该对象，也必须以字段形式长期持有：
     * 一旦它被 GC 回收，Windows 仍会在鼠标事件发生时调用其函数指针，从而引发崩溃。
     */
    private final MyHOOKPROC mouseHookCallback = new MyHOOKPROC() {
        @Override
        public LRESULT callback(int nCode, WPARAM wParam, LPARAM lParam) {
            // 本方法仅作转发，真正的分发逻辑全部集中在 mouseProc 中
            return mouseProc(nCode, wParam, lParam);
        }
    };

    /**
     * 初始化 1~7 号按钮的默认配置与连发状态标记。
     *
     * <p>预先填满 7 个按钮，可避免后续在钩子回调中读取到 null 配置。
     */
    public HookManager() {
        for (int i = 0; i < 7; i++) {
            repeatActive[i] = new AtomicBoolean(false);
            config.put(i + 1, new RemapConfig());
        }
    }

    /**
     * 更新某个按钮的重映射配置。
     *
     * <p>由 JavaFX 线程在用户修改界面时调用，而钩子线程会并发读取 {@code config}，
     * 因此方法加锁；并对传入的 {@code keys} 做防御性拷贝，
     * 避免调用方后续修改原列表进而影响钩子线程。
     *
     * @param button 按钮编号（1~7）
     * @param keys 映射到的虚拟键码列表
     * @param remap 是否启用重映射
     * @param repeat 是否连发
     * @param untilClick 连发是否为点击切换模式
     * @param repeatIntervalMs 连发间隔毫秒数
     * @param chord 是否按和弦方式触发
     */
    public synchronized void setRemap(int button, List<Integer> keys, boolean remap, boolean repeat, boolean untilClick, int repeatIntervalMs, boolean chord) {
        RemapConfig cfg = config.get(button);
        if (cfg != null) {
            cfg.virtualKeys = new ArrayList<>(keys);
            cfg.isRemapped = remap;
            cfg.repeatEnabled = repeat;
            cfg.repeatUntilClick = untilClick;
            cfg.repeatIntervalMs = repeatIntervalMs;
            cfg.isChord = chord;
        }
    }

    /**
     * 获取当前配置表。
     *
     * <p>注意：返回的是内部 Map 的直接引用而非副本，调用方仍可直接修改其中的
     * {@link RemapConfig} 对象，方法上的锁并不能保护这些后续修改。
     *
     * @return 按钮编号（1~7）到配置对象的映射
     */
    public synchronized Map<Integer, RemapConfig> getConfig() {
        return config;
    }

    /**
     * 查询钩子是否处于活动状态，供 UI 状态灯与窗口监控线程判断。
     *
     * @return 钩子已安装并正在工作时返回 {@code true}
     */
    public synchronized boolean isHookActive() {
        return hookActive;
    }

    /**
     * 安装全局鼠标钩子（异步）。
     *
     * <p>钩子必须在「拥有消息循环的线程」上安装，因此这里新起一条名为
     * {@code MouseHookThread} 的守护线程，在其中完成安装并随后进入消息循环。
     * 安装失败时会回滚 {@code hookActive}，使界面能正确显示为已停止。
     */
    public synchronized void startHook() {
        if (hookActive) return;
        hookActive = true;

        Thread hookThread = new Thread(() -> {
            // 获取当前进程的模块句柄；低级钩子实际上并不要求提供该句柄
            HMODULE hMod = Kernel32.INSTANCE.GetModuleHandle(null);
            synchronized (HookManager.this) {
                // 安装 WH_MOUSE_LL 低级鼠标钩子；dwThreadId 传 0 表示拦截全局所有线程的鼠标事件
                hHook = MyUser32.INSTANCE.SetWindowsHookEx(WinUser.WH_MOUSE_LL, mouseHookCallback, hMod, 0);
                if (hHook == null) {
                    System.err.println("Failed to install mouse hook. Error code: " + Kernel32.INSTANCE.GetLastError());
                    hookActive = false;
                    return;
                }
                // 记录承载钩子的线程 ID，stopHook 需向该线程投递 WM_QUIT 才能唤醒消息循环
                hookThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
                System.out.println("Global mouse hook successfully installed. Thread ID: " + hookThreadId);
            }

            // 保持线程存活所需的 Windows 消息循环（WH_MOUSE_LL 钩子的硬性要求）
            // Keep thread alive with a Windows Message Loop (required for WH_MOUSE_LL hooks)
            WinUser.MSG msg = new WinUser.MSG();
            while (hookActive) {
                // GetMessage 会阻塞等待消息；返回 <= 0 表示收到 WM_QUIT 或出错，此时退出循环
                int result = MyUser32.INSTANCE.GetMessage(msg, null, 0, 0);
                if (result <= 0) {
                    break;
                }
                MyUser32.INSTANCE.TranslateMessage(msg);
                MyUser32.INSTANCE.DispatchMessage(msg);
            }

            // 循环退出后统一卸载钩子，避免在系统中残留全局钩子
            synchronized (HookManager.this) {
                if (hHook != null) {
                    MyUser32.INSTANCE.UnhookWindowsHookEx(hHook);
                    hHook = null;
                    System.out.println("Global mouse hook successfully uninstalled.");
                }
                hookThreadId = 0;
            }
        }, "MouseHookThread");

        // 设为守护线程，程序退出时不会因它而阻塞 JVM 关闭
        hookThread.setDaemon(true);
        hookThread.start();
    }

    /**
     * 卸载全局鼠标钩子，并停止所有连发线程。
     *
     * <p>注意不能直接调用 {@code UnhookWindowsHookEx}：此时钩子线程正阻塞在
     * {@code GetMessage} 上，必须先向该线程投递 {@code WM_QUIT} 将其唤醒，
     * 真正的卸载动作由钩子线程自己在消息循环结束后完成。
     */
    public synchronized void stopHook() {
        if (!hookActive) return;
        hookActive = false;

        // 重置各按钮的连发状态，使所有连发线程因循环条件不满足而自然退出
        // Reset repeat threads
        for (AtomicBoolean active : repeatActive) {
            active.set(false);
        }

        // 向钩子线程投递 WM_QUIT，唤醒其消息循环使其退出
        // Wake up the message pump by posting WM_QUIT to the hook thread
        if (hookThreadId != 0) {
            MyUser32.INSTANCE.PostThreadMessage(hookThreadId, WinUser.WM_QUIT, new WPARAM(0), new LPARAM(0));
        }
    }

    /**
     * 把指定按钮对应的按键序列回注给系统。
     *
     * <p>两种触发语义：
     * <ul>
     *   <li>和弦（{@code isChord}）：先依次按下所有键，再依次松开，
     *       模拟 Ctrl+C 这类真正的组合键；</li>
     *   <li>普通模式：每个键「按下即松开」，等价于依次敲击多个键。</li>
     * </ul>
     *
     * <p>使用 {@code keybd_event} 而非更现代的 {@code SendInput}，
     * 优点是调用简单，缺点是无法携带扫描码、对部分游戏不兼容。
     *
     * @param button 按钮编号（1~7），据此取出要模拟的按键
     */
    private void simulateKey(int button) {
        // 加锁读取配置快照，避免与 JavaFX 线程的写操作竞争
        RemapConfig cfg;
        synchronized (this) {
            cfg = config.get(button);
        }
        // 未配置任何按键时直接返回，防止空列表导致无意义调用
        if (cfg == null || cfg.virtualKeys.isEmpty()) return;

        if (cfg.isChord) {
            // 和弦模式：全部按下，再全部松开
            for (int key : cfg.virtualKeys) {
                MyUser32.INSTANCE.keybd_event((byte) key, (byte) 0, 0, 0);
            }
            for (int key : cfg.virtualKeys) {
                MyUser32.INSTANCE.keybd_event((byte) key, (byte) 0, 2, 0);
            }
        } else {
            // 普通模式：每个键逐个「按下-松开」
            for (int key : cfg.virtualKeys) {
                MyUser32.INSTANCE.keybd_event((byte) key, (byte) 0, 0, 0);
                MyUser32.INSTANCE.keybd_event((byte) key, (byte) 0, 2, 0);
            }
        }
    }

    /**
     * 鼠标钩子回调，整个程序的核心分发逻辑。
     *
     * <p>回调运行在 {@code MouseHookThread} 线程上，且必须尽快返回：
     * 低级鼠标钩子是全局的，处理过慢会让系统整体鼠标操作出现卡顿。
     *
     * <p>返回值的含义：返回 1 表示「事件已被吃掉」，系统不会再传递给任何窗口；
     * 返回 {@code CallNextHookEx} 的结果表示放行给下一个钩子与目标窗口。
     *
     * @param nCode 小于 0 时表示系统要求无条件透传，不得做任何处理
     * @param wParam 鼠标事件类型
     * @param lParam 指向 {@link MyMSLLHOOKSTRUCT} 的指针
     * @return 拦截时返回 1，放行时返回 {@code CallNextHookEx} 的结果
     */
    private LRESULT mouseProc(int nCode, WPARAM wParam, LPARAM lParam) {
        if (nCode >= 0) {
            int w = wParam.intValue();

            // 只处理真正关心的事件，完全跳过 WM_MOUSEMOVE
            // Only process messages we actually care about (skip WM_MOUSEMOVE entirely!)
            if (w == 0x0201 || w == 0x0202 || // WM_LBUTTONDOWN / WM_LBUTTONUP
                w == 0x0204 || w == 0x0205 || // WM_RBUTTONDOWN / WM_RBUTTONUP
                w == 0x0207 || w == 0x0208 || // WM_MBUTTONDOWN / WM_MBUTTONUP
                w == 0x020B || w == 0x020C || // WM_XBUTTONDOWN / WM_XBUTTONUP
                w == 0x020A) {                // WM_MOUSEWHEEL

                // 第一步：把 Win32 消息翻译成 1~7 的按钮编号
                int btn = 0;
                if (w == 0x0201 || w == 0x0202) btn = 1;
                else if (w == 0x0204 || w == 0x0205) btn = 2;
                else if (w == 0x0207 || w == 0x0208) btn = 3;
                else if (w == 0x020B || w == 0x020C) {
                    // 侧键：X1/X2 共用同一组消息，靠 mouseData 高 16 位区分（1 为 X1，2 为 X2）
                    Pointer p = new Pointer(lParam.longValue());
                    MyMSLLHOOKSTRUCT mouseStruct = new MyMSLLHOOKSTRUCT(p);
                    int mouseData = mouseStruct.mouseData;
                    int xbtn = (mouseData >> 16) & 0xFFFF;
                    btn = (xbtn == 1) ? 4 : 5;
                } else if (w == 0x020A) {
                    // 滚轮：mouseData 高 16 位是有符号滚动量，正值向上、负值向下
                    Pointer p = new Pointer(lParam.longValue());
                    MyMSLLHOOKSTRUCT mouseStruct = new MyMSLLHOOKSTRUCT(p);
                    int mouseData = mouseStruct.mouseData;
                    short delta = (short) ((mouseData >> 16) & 0xFFFF);
                    btn = (delta > 0) ? 6 : 7;
                }

                if (btn > 0) {
                // 第二步：取出该按钮的配置；未启用重映射时不做任何处理，直接放行
                RemapConfig cfg;
                synchronized (this) {
                    cfg = config.get(btn);
                }

                if (cfg != null && cfg.isRemapped) {
                    // 滚轮被重映射时，直接模拟按键并吃掉原生滚动，避免目标窗口同时滚动页面
                    if (btn == 6 || btn == 7) {
                        simulateKey(btn);
                        return new LRESULT(1); // Block native scroll event
                    }

                    // 第三步：判断按下还是抬起。
                    // 四类按键的 DOWN 消息码为 0x0201/0x0204/0x0207/0x020B，
                    // 对应的 UP 消息码为 0x0202/0x0205/0x0208/0x020C
                    boolean isDown = (w == 0x0201 || w == 0x0204 || w == 0x0207 || w == 0x020B);
                    boolean isUp = (w == 0x0202 || w == 0x0205 || w == 0x0208 || w == 0x020C);
                    // 数组下标从 0 开始，故此处把 1~7 的按钮号转换为 0~6
                    int btnIdx = btn - 1;

                    if (isDown) {
                        // 第四步：按下时处理。未开启连发则只触发一次，开启则启动连发线程
                        if (cfg.repeatEnabled) {
                            if (cfg.repeatUntilClick) {
                                // 切换模式：每次按下翻转一次状态，实现「点一下开始连发、再点一下停止」
                                // Toggle active state
                                if (repeatActive[btnIdx].get()) {
                                    repeatActive[btnIdx].set(false);
                                } else {
                                    repeatActive[btnIdx].set(true);
                                    // 用 final 局部变量保存，以便 lambda 形式的连发线程捕获
                                    final int buttonToRepeat = btn;
                                    final int interval = cfg.repeatIntervalMs;
                                    // 为本次连发单独起一条守护线程：开关为 true 且钩子仍活动时持续触发按键
                                    Thread repeatThread = new Thread(() -> {
                                        while (repeatActive[btnIdx].get() && hookActive) {
                                            simulateKey(buttonToRepeat);
                                            try {
                                                Thread.sleep(interval);
                                            } catch (InterruptedException e) {
                                                break;
                                            }
                                        }
                                    }, "RepeatThread-" + buttonToRepeat);
                                    repeatThread.setDaemon(true);
                                    repeatThread.start();
                                }
                            } else {
                                // 按住模式：按下即开始持续连发，松手时由下面的 isUp 分支停止
                                // Start repeating while held down
                                repeatActive[btnIdx].set(true);
                                // 同上，用 final 局部变量供 lambda 捕获
                                final int buttonToRepeat = btn;
                                final int interval = cfg.repeatIntervalMs;
                                // 每次按下新建一条线程；按下消息不会连续重发，故不会造成线程堆积
                                Thread repeatThread = new Thread(() -> {
                                    while (repeatActive[btnIdx].get() && hookActive) {
                                        simulateKey(buttonToRepeat);
                                        try {
                                            Thread.sleep(interval);
                                        } catch (InterruptedException e) {
                                            break;
                                        }
                                    }
                                }, "RepeatThread-" + buttonToRepeat);
                                repeatThread.setDaemon(true);
                                repeatThread.start();
                            }
                        } else {
                            // 未开启连发：仅触发一次按键映射
                            simulateKey(btn);
                        }
                    }

                    if (isUp) {
                        // 抬起时：结束连发状态（切换模式除外，它需要等下一次点击才停止）
                        if (cfg.repeatEnabled && !cfg.repeatUntilClick) {
                            repeatActive[btnIdx].set(false);
                        }
                        // 返回 1 屏蔽系统默认的抬起事件
                        // Block default event by returning 1
                        return new LRESULT(1);
                    }

                    // 返回 1 屏蔽系统默认事件，使原鼠标按键不产生任何效果
                    // Block default event by returning 1
                    return new LRESULT(1);
                }
            }
        }
        }
        // 未命中任何重映射：调用链中的下一个钩子，事件按原样继续传递
        return MyUser32.INSTANCE.CallNextHookEx(hHook, nCode, wParam, lParam);
    }
}
