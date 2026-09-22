package com.mouseremapper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置持久化层：负责把「应用档案（Profile）」在内存模型与 JSON 文件之间来回转换。
 *
 * <p>落盘文件固定为工作目录下的 {@code profiles.json}，JSON 结构为：
 * <pre>
 * {
 *   "chrome.exe": { "1": { "keys":[16,65], "remap":true, "repeat":false, ... } },
 *   "Default":    { "1": { ... } }
 * }
 * </pre>
 * 即「档案名（通常是可执行文件名） → 按钮编号 → 该按钮的映射配置」三层嵌套。
 *
 * <p>磁盘格式与内存格式刻意做了区分：{@link JsonConfigEntry} 面向 JSON，
 * 字段名更短且用 String 作为按钮键；{@link HookManager.RemapConfig} 面向运行时，
 * 只保留真正参与判定的字段。{@link #saveProfiles} 与 {@link #parseProfile} 负责两者互转。
 */
public class ConfigManager {

    /** 保留对 HookManager 的引用，供后续扩展使用（当前读写流程未直接依赖它）。 */
    private final HookManager hookManager;
    /** 带缩进格式化的 Gson 实例，保证生成的 JSON 便于人工阅读与版本对比。 */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * @param hookManager 鼠标钩子管理器，用于访问运行时配置模型
     */
    public ConfigManager(HookManager hookManager) {
        this.hookManager = hookManager;
    }

    /**
     * JSON 序列化用的中间数据结构（DTO）。
     *
     * <p>字段全部为 public 且可变，这是 Gson 反射读写所要求的；
     * 字段名刻意取得比运行时模型更短，以减小配置文件体积。
     * 按钮编号在 JSON 中以字符串作为 key，故外层用 {@code Map<String, JsonConfigEntry>} 承接。
     */
    public static class JsonConfigEntry {
        /** 映射到的键盘虚拟键码列表，顺序即按键顺序。 */
        public List<Integer> keys;
        /** 是否启用该按钮的重映射。 */
        public boolean remap;
        /** 是否开启连发（按住期间重复触发）。 */
        public boolean repeat;
        /** 连发是否采用「点击一次开启、再点一次停止」的切换模式。 */
        public boolean untilClick;
        /** 连发间隔毫秒数，默认 100。 */
        public int repeatIntervalMs = 100;
        /** 是否按和弦方式触发（先全部按下再全部松开）。 */
        public boolean isChord = false;
    }

    /**
     * 保存全部档案到 {@code profiles.json}（覆盖写入）。
     *
     * <p>流程：运行时模型 → DTO → JSON 文本。写入前会把整棵结构拷贝一遍，
     * 避免在序列化过程中被其他线程并发修改。
     *
     * @param allProfiles 全部档案，键为档案名（小写可执行文件名），值为「按钮编号 → 映射配置」
     */
    public void saveProfiles(Map<String, Map<Integer, HookManager.RemapConfig>> allProfiles) {
        try {
            Map<String, Map<String, JsonConfigEntry>> jsonProfiles = new HashMap<>();
            
            // 逐层遍历内存模型，转换为便于 Gson 序列化的 DTO 结构
            for (Map.Entry<String, Map<Integer, HookManager.RemapConfig>> profileEntry : allProfiles.entrySet()) {
                Map<String, JsonConfigEntry> jsonConfig = new HashMap<>();
                for (Map.Entry<Integer, HookManager.RemapConfig> entry : profileEntry.getValue().entrySet()) {
                    JsonConfigEntry jsonEntry = new JsonConfigEntry();
                    jsonEntry.keys = new ArrayList<>(entry.getValue().virtualKeys);
                    jsonEntry.remap = entry.getValue().isRemapped;
                    jsonEntry.repeat = entry.getValue().repeatEnabled;
                    jsonEntry.untilClick = entry.getValue().repeatUntilClick;
                    jsonEntry.repeatIntervalMs = entry.getValue().repeatIntervalMs;
                    jsonEntry.isChord = entry.getValue().isChord;
                    // JSON 对象的键必须是字符串，故把按钮编号转成 String
                    jsonConfig.put(String.valueOf(entry.getKey()), jsonEntry);
                }
                jsonProfiles.put(profileEntry.getKey(), jsonConfig);
            }

            try (Writer writer = new FileWriter("profiles.json")) {
                gson.toJson(jsonProfiles, writer);
                System.out.println("Profiles saved successfully.");
            }
        } catch (Exception e) {
            System.err.println("Failed to save profiles: " + e.getMessage());
        }
    }

    /**
     * 从 {@code profiles.json} 加载全部档案；文件不存在时尝试从旧版 {@code config.json} 迁移。
     *
     * <p>任何异常（文件损坏、格式不合法等）都会被吞掉并返回已解析出的部分结果，
     * 由调用方负责补上缺失的 {@code Default} 档案，保证程序仍能启动。
     *
     * @return 全部档案；无配置或解析失败时返回空 Map
     */
    public Map<String, Map<Integer, HookManager.RemapConfig>> loadProfiles() {
        Map<String, Map<Integer, HookManager.RemapConfig>> allProfiles = new HashMap<>();
        
        try {
            java.io.File file = new java.io.File("profiles.json");
            if (!file.exists()) {
                // Try migrating from old config.json
                // 新格式文件不存在时，尝试兼容旧版单档案格式 config.json
                java.io.File oldFile = new java.io.File("config.json");
                if (oldFile.exists()) {
                    try (Reader reader = new FileReader(oldFile)) {
                        Type type = new TypeToken<Map<String, JsonConfigEntry>>() {}.getType();
                        Map<String, JsonConfigEntry> oldConfig = gson.fromJson(reader, type);
                        if (oldConfig != null) {
                            Map<Integer, HookManager.RemapConfig> defaultProfile = parseProfile(oldConfig);
                            allProfiles.put("Default", defaultProfile);
                            System.out.println("Migrated old config.json to Default profile.");
                        }
                    }
                }
                return allProfiles;
            }

            try (Reader reader = new FileReader(file)) {
                // TypeToken 匿名子类用于在运行时保留泛型信息，否则 Gson 无法解析嵌套泛型
                Type type = new TypeToken<Map<String, Map<String, JsonConfigEntry>>>() {}.getType();
                Map<String, Map<String, JsonConfigEntry>> jsonProfiles = gson.fromJson(reader, type);

                if (jsonProfiles != null) {
                    for (Map.Entry<String, Map<String, JsonConfigEntry>> profileEntry : jsonProfiles.entrySet()) {
                        allProfiles.put(profileEntry.getKey(), parseProfile(profileEntry.getValue()));
                    }
                    System.out.println("Profiles loaded successfully.");
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load profiles: " + e.getMessage());
        }
        return allProfiles;
    }

    /**
     * 把单个档案从 JSON DTO 转换为运行时配置模型。
     *
     * <p>先为 1~7 号按钮填充默认配置，再用 JSON 中实际存在的条目覆盖，
     * 这样即使配置文件缺少某些按钮（例如旧版本只保存了部分按钮），也不会出现空指针。
     *
     * @param jsonConfig 单个档案的 JSON 数据，键为按钮编号字符串
     * @return 保证包含 1~7 全部按钮的运行时配置
     */
    private Map<Integer, HookManager.RemapConfig> parseProfile(Map<String, JsonConfigEntry> jsonConfig) {
        Map<Integer, HookManager.RemapConfig> profile = new HashMap<>();
        // 先铺默认值，保证 7 个按钮都有配置对象存在
        for (int i = 1; i <= 7; i++) {
            profile.put(i, new HookManager.RemapConfig());
        }
        for (Map.Entry<String, JsonConfigEntry> entry : jsonConfig.entrySet()) {
            int button = Integer.parseInt(entry.getKey());
            JsonConfigEntry jsonEntry = entry.getValue();
            HookManager.RemapConfig cfg = new HookManager.RemapConfig();
            // keys 可能为 null（例如用户手写配置时遗漏），此处统一兜底为空列表
            cfg.virtualKeys = jsonEntry.keys != null ? new ArrayList<>(jsonEntry.keys) : new ArrayList<>();
            cfg.isRemapped = jsonEntry.remap;
            cfg.repeatEnabled = jsonEntry.repeat;
            cfg.repeatUntilClick = jsonEntry.untilClick;
            // 0 表示配置缺失或非法，回退到默认的 100ms，避免生成 0ms 的高频连发
            cfg.repeatIntervalMs = jsonEntry.repeatIntervalMs == 0 ? 100 : jsonEntry.repeatIntervalMs;
            cfg.isChord = jsonEntry.isChord;
            profile.put(button, cfg);
        }
        return profile;
    }
}
