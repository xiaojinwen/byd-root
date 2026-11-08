package xiaojw.BydRoot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 自备资源的 config.txt 版本映射与车机版本识别逻辑。 */
public final class DeviceConfig {

    /** 内置版本表；资源目录里没有 config.txt 时用它兜底。 */
    public static final String DEFAULT_CONFIG =
            "13 2511250 B3EDE7F5A948EFD8D3FE764E5418A304\n" +
            "15 2406105 15CF5226BFE406F1A1BE3E5DBEA6D898\n" +
            "17 2511190 0183FD14653BD9BF6C21459C2DFDC61B\n" +
            "18 2511250 d816430e4e8d966186b82afacd9721b9\n" +
            "21 2506090 edc02cb27655ab6817225ec1821f74aa\n";

    public static final String PROP_OUTSWVER = "apps.setting.product.outswver";

    public static final class Entry {
        public final String controller;
        public final String core;
        public final String md5;
        Entry(String controller, String core, String md5) {
            this.controller = controller;
            this.core = core;
            this.md5 = md5;
        }
    }

    /** config.txt 每行：<控制器版本> <核心版本> <boot.img MD5> */
    public static Map<String, Entry> parse(String text) {
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        if (text == null) return map;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p.length < 3) continue;
            map.put(p[0], new Entry(p[0], p[1], p[2]));
        }
        return map;
    }

    /** 从 /etc/system.prop 文本中取指定 key（ADB 读取后按 '=' 解析）。 */
    public static String prop(String systemProp, String key) {
        if (systemProp == null) return null;
        for (String raw : systemProp.split("\\r?\\n")) {
            int i = raw.indexOf('=');
            if (i < 0) continue;
            String k = raw.substring(0, i).trim();
            if (k.equalsIgnoreCase(key)) return raw.substring(i + 1).trim().replace(" ", "");
        }
        return null;
    }

    /** outswver 形如 13.1.22.2511250.1，取第一段前两位数字作为控制器版本。 */
    public static String controllerOf(String outswver) {
        if (outswver == null) return null;
        String first = outswver.split("\\.")[0];
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < first.length() && digits.length() < 2; i++) {
            char c = first.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        return digits.length() == 0 ? null : digits.toString();
    }

    /** outswver 的第 4 段是核心版本。 */
    public static String coreOf(String outswver) {
        if (outswver == null) return null;
        String[] p = outswver.split("\\.");
        return p.length >= 4 ? p[3] : null;
    }

    /** 模块包名第三段自带 MD5，如 Module-13_2511_3e93e99b08eca5176651cc1157e11d37.zip */
    public static String moduleMd5FromName(String name) {
        if (name == null) return null;
        String base = name.toLowerCase(Locale.US).endsWith(".zip")
                ? name.substring(0, name.length() - 4) : name;
        String[] p = base.split("_");
        if (p.length < 3) return null;
        String md5 = p[2];
        return md5.matches("(?i)^[0-9a-f]{32}$") ? md5 : null;
    }

    public static String bootName(String controller) { return "boot-" + controller + ".img"; }

    private DeviceConfig() { }
}
