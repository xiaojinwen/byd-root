package xiaojw.BydRoot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 备份清单与恢复脚本生成（纯 Java，不依赖 Android API，可在 JVM 自测中验证）。
 * 产物设计目标：黑砖后即使 app / 系统都起不来，也能用 PC fastboot 或高通 9008 深刷工具直接回刷。
 */
public final class BackupManifest {

    public static final String TOOL_VERSION = "1.1.0";

    public static final class Part {
        public String name;        // by-name 名称，如 boot_a
        public String dev;         // /dev/block/by-name/boot_a
        public String realDev;     // readlink -f 的真实块设备
        public String block;       // mmcblk0p12
        public String file;        // 备份文件名
        public long size;          // 预估大小（sysfs）
        public long dumped;        // 实际写出字节数
        public long startLba;      // 起始扇区（9008 rawprogram 用）
        public long numLba;        // 扇区数
        public String sha256;      // 本地计算
        public String deviceSha;   // 车机端 sha256sum 结果
        public boolean verified;   // 双侧 SHA256 一致
        public boolean skipped;
        public String note;
    }

    public static final class DeviceInfo {
        public String model = "", brand = "", android = "", serial = "";
        public String outswver = "", kernel = "", wholeDisk = "", byNameDir = "";
        public long sectorSize = 512;
        public long totalSectors = 0;
        public String method = "adb-root-dd";
        public String created = "";
    }

    public static List<String> parseLs(String out) {
        List<String> names = new ArrayList<String>();
        if (out == null) return names;
        for (String raw : out.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("/") || s.contains(":")) continue;
            if (s.startsWith("total ") || s.indexOf(' ') >= 0) {
                // toybox ls 在个别 adbd 上会输出多列，逐 token 收下
                for (String t : s.split("\\s+")) if (!t.isEmpty()) names.add(t);
            } else {
                names.add(s);
            }
        }
        return names;
    }

    public static long parseLong(String out, long def) {
        if (out == null) return def;
        for (String raw : out.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    /**
     * 解析车机端一次性枚举脚本的输出：
     * name|realDev|block|numSectors|startSector
     */
    public static List<Part> parsePartTable(String out, String byNameDir) {
        List<Part> list = new ArrayList<Part>();
        if (out == null) return list;
        for (String raw : out.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.indexOf('|') < 0) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 3) continue;
            Part p = new Part();
            p.name = f[0].trim();
            if (p.name.isEmpty() || !p.name.matches("[A-Za-z0-9._-]+")) continue;
            p.dev = byNameDir + "/" + p.name;
            p.realDev = f[1].trim().isEmpty() ? p.dev : f[1].trim();
            p.block = f[2].trim();
            p.numLba = f.length > 3 ? parseLong(f[3], 0) : 0;
            p.startLba = f.length > 4 ? parseLong(f[4], 0) : 0;
            p.size = p.numLba * 512;
            p.file = safeName(p.name) + ".img";
            list.add(p);
        }
        return list;
    }

    /** 从 sha256sum / toybox 输出里取第一段 64 位十六进制。 */
    public static String parseSha256(String out) {
        if (out == null) return null;
        for (String raw : out.split("\\r?\\n")) {
            for (String t : raw.trim().split("\\s+")) {
                if (t.length() == 64 && t.matches("[0-9a-fA-F]{64}")) return t.toLowerCase(Locale.US);
            }
        }
        return null;
    }

    public static String firstLine(String out) {
        if (out == null) return "";
        for (String raw : out.split("\\r?\\n")) {
            String s = raw.trim();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** getprop 输出格式为 [key]: [value]。 */
    public static String propOf(String all, String key) {
        if (all == null) return "";
        String prefix = "[" + key + "]: [";
        int i = all.indexOf(prefix);
        if (i < 0) return "";
        int start = i + prefix.length();
        int end = all.indexOf(']', start);
        return end < 0 ? "" : all.substring(start, end).trim();
    }

    public static String readlink(String out, String fallback) {
        String s = firstLine(out);
        return s.startsWith("/") ? s : fallback;
    }

    public static String blockNameOf(String realDev) {
        if (realDev == null) return "";
        int i = realDev.lastIndexOf('/');
        return i < 0 ? realDev : realDev.substring(i + 1);
    }

    /** /dev/block/mmcblk0p12 -> /dev/block/mmcblk0；/dev/block/sda12 -> /dev/block/sda */
    public static String wholeDiskOf(String realDev) {
        if (realDev == null || realDev.isEmpty()) return "";
        if (realDev.matches(".*mmcblk[0-9]+p[0-9]+")) {
            return realDev.replaceAll("p[0-9]+$", "");
        }
        if (realDev.matches(".*sd[a-z]+[0-9]+")) {
            return realDev.replaceAll("[0-9]+$", "");
        }
        return realDev;   // 已是整盘（mmcblk0 / sda）等，原样返回
    }

    public static boolean isUserData(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("userdata") || n.equals("data") || n.startsWith("cache")
                || n.startsWith("metadata") || n.startsWith("scratch");
    }

    public static String safeName(String name) {
        return name == null ? "unknown" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String shaHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String j(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    public static String buildManifest(DeviceInfo d, List<Part> parts, List<String> extraFiles) {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"tool\": \"BydRoot 全分区备份\",\n");
        b.append("  \"version\": ").append(j(TOOL_VERSION)).append(",\n");
        b.append("  \"created\": ").append(j(d.created)).append(",\n");
        b.append("  \"method\": ").append(j(d.method)).append(",\n");
        b.append("  \"device\": {\n");
        b.append("    \"brand\": ").append(j(d.brand)).append(",\n");
        b.append("    \"model\": ").append(j(d.model)).append(",\n");
        b.append("    \"android\": ").append(j(d.android)).append(",\n");
        b.append("    \"serial\": ").append(j(d.serial)).append(",\n");
        b.append("    \"outswver\": ").append(j(d.outswver)).append(",\n");
        b.append("    \"kernel\": ").append(j(d.kernel)).append(",\n");
        b.append("    \"bynameDir\": ").append(j(d.byNameDir)).append(",\n");
        b.append("    \"wholeDisk\": ").append(j(d.wholeDisk)).append(",\n");
        b.append("    \"sectorSize\": ").append(d.sectorSize).append(",\n");
        b.append("    \"totalSectors\": ").append(d.totalSectors).append("\n");
        b.append("  },\n");
        b.append("  \"partitions\": [\n");
        long total = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (!p.skipped) total += p.dumped;
            b.append("    {\"name\": ").append(j(p.name))
                    .append(", \"device\": ").append(j(p.dev))
                    .append(", \"realDevice\": ").append(j(p.realDev))
                    .append(", \"block\": ").append(j(p.block))
                    .append(", \"startLba\": ").append(p.startLba)
                    .append(", \"numLba\": ").append(p.numLba)
                    .append(", \"size\": ").append(p.size)
                    .append(", \"bytes\": ").append(p.dumped)
                    .append(", \"sha256\": ").append(j(p.sha256))
                    .append(", \"deviceSha256\": ").append(j(p.deviceSha))
                    .append(", \"verified\": ").append(p.verified)
                    .append(", \"skipped\": ").append(p.skipped)
                    .append(", \"note\": ").append(j(p.note))
                    .append(", \"file\": ").append(j(p.file)).append("}");
            if (i < parts.size() - 1) b.append(',');
            b.append('\n');
        }
        b.append("  ],\n");
        b.append("  \"totalBytes\": ").append(total).append(",\n");
        b.append("  \"extraFiles\": [");
        for (int i = 0; i < extraFiles.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(j(extraFiles.get(i)));
        }
        b.append("]\n}\n");
        return b.toString();
    }

    /** PC 端一键回刷脚本（Windows）。跳过 userdata 等不建议直接 flash 的分区并注释说明。 */
    public static String buildBat(List<Part> parts, String created) {
        StringBuilder b = new StringBuilder();
        b.append("@echo off\r\n");
        b.append("chcp 65001 >nul\r\n");
        b.append("echo BydRoot 全分区恢复 · 备份时间 ").append(created).append("\r\n");
        b.append("echo 警告：请确认镜像与车机型号完全一致，错误回刷会导致彻底黑砖！\r\n");
        b.append("echo 请先让车机进入 fastboot 模式并用 USB 连接电脑。\r\n");
        b.append("pause\r\n");
        b.append("fastboot getvar product\r\n");
        b.append("if errorlevel 1 goto nofastboot\r\n");
        for (Part p : parts) {
            if (p.file == null) continue;
            if (isUserData(p.name) || p.skipped) {
                b.append("rem [跳过] ").append(p.name)
                        .append(p.skipped ? " 未备份 / 用户数据分区，无需回刷" : " 为用户数据分区，通常无需回刷")
                        .append("\r\n");
                continue;
            }
            b.append("echo 回刷 ").append(p.name).append(" ...\r\n");
            b.append("fastboot flash ").append(p.name).append(" ").append(p.file).append("\r\n");
            b.append("if errorlevel 1 goto failed\r\n");
        }
        b.append("echo 全部分区回刷完成。\r\n");
        b.append("fastboot reboot\r\n");
        b.append("goto end\r\n");
        b.append(":failed\r\necho 回刷失败，请勿断电，检查报错后重试本脚本。\r\npause\r\ngoto end\r\n");
        b.append(":nofastboot\r\necho 未检测到 fastboot 设备；若连 fastboot 都进不去，请用 9008/BROM 深刷工具配合 rawprogram-restore.xml 恢复。\r\npause\r\n");
        b.append(":end\r\n");
        return b.toString();
    }

    public static String buildSh(List<Part> parts, String created) {
        StringBuilder b = new StringBuilder();
        b.append("#!/bin/sh\n# BydRoot 全分区恢复 · ").append(created).append('\n');
        b.append("set -e\nfastboot getvar product\n");
        for (Part p : parts) {
            if (p.skipped || p.file == null || isUserData(p.name)) continue;
            b.append("echo '回刷 ").append(p.name).append("'\n");
            b.append("fastboot flash ").append(p.name).append(' ').append(p.file).append('\n');
        }
        b.append("fastboot reboot\n");
        return b.toString();
    }

    /** 高通 9008 / Firehose 深刷工具（如 LF深刷、QFIL）可用的 program 清单。 */
    public static String buildRawprogram(List<Part> parts, long sectorSize) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" ?>\n<data>\n");
        b.append("  <!-- 由 BydRoot 生成；配合对应机型的 Firehose programmer 使用 -->\n");
        for (Part p : parts) {
            if (p.skipped || p.file == null || p.startLba <= 0 || p.numLba <= 0) continue;
            b.append("  <program SECTOR_SIZE_IN_BYTES=\"").append(sectorSize)
                    .append("\" file_sector_offset=\"0\" filename=\"").append(p.file)
                    .append("\" label=\"").append(p.name)
                    .append("\" num_partition_sectors=\"").append(p.numLba)
                    .append("\" physical_partition_number=\"0\" start_sector=\"")
                    .append(p.startLba).append("\" />\n");
        }
        b.append("</data>\n");
        return b.toString();
    }

    public static String buildReadme(DeviceInfo d, List<Part> parts, long totalBytes) {
        StringBuilder b = new StringBuilder();
        b.append("BydRoot 全分区备份\n");
        b.append("====================\n");
        b.append("备份时间：").append(d.created).append('\n');
        b.append("设备：").append(d.brand).append(' ').append(d.model)
                .append(" / Android ").append(d.android).append('\n');
        b.append("系统版本：").append(d.outswver).append('\n');
        b.append("序列号：").append(d.serial).append('\n');
        b.append("块设备：").append(d.wholeDisk).append("（扇区 ")
                .append(d.sectorSize).append("B，共 ").append(d.totalSectors).append(" 扇区）\n");
        b.append("分区数：").append(parts.size()).append("，总数据量：")
                .append(String.format(Locale.US, "%.2f", totalBytes / 1073741824.0)).append(" GiB\n\n");
        b.append("目录内容\n--------\n");
        b.append("  *.img                  各分区原始镜像（可被 fastboot flash 直接使用）\n");
        b.append("  gpt-primary.bin        磁盘开头 34 扇区（保护 MBR + 主 GPT）\n");
        b.append("  gpt-backup.bin         磁盘末尾 33 扇区（备份 GPT）\n");
        b.append("  manifest.json          分区清单：设备节点、LBA、大小、SHA-256\n");
        b.append("  restore-fastboot.bat   Windows PC 一键回刷脚本\n");
        b.append("  restore-fastboot.sh    Linux/macOS 回刷脚本\n");
        b.append("  rawprogram-restore.xml 高通 9008 / Firehose 深刷用分区表\n\n");
        b.append("恢复方式（按严重程度选择）\n--------------------------\n");
        b.append("1) 还能进 fastboot：车机进 fastboot，PC 连 USB，运行 restore-fastboot.bat\n");
        b.append("2) 连 fastboot 都进不去（黑砖）：进高通 9008 EDL 或 MTK BROM，\n");
        b.append("   用 LF深刷 / QFIL 等工具加载对应机型 Firehose/DA，\n");
        b.append("   导入 rawprogram-restore.xml + 本目录的 *.img 逐分区回写。\n");
        b.append("3) 只坏了一个分区：单独 fastboot flash <分区名> <对应.img> 即可。\n\n");
        b.append("注意\n----\n");
        b.append("- 镜像与机型/版本强绑定，切勿把本备份刷到其它车机。\n");
        b.append("- persist / modem / calibration 等分区含设备唯一数据，仅本机能用。\n");
        b.append("- 请把整个目录复制到电脑和另一块移动硬盘各存一份。\n");
        return b.toString();
    }

    private BackupManifest() { }
}
