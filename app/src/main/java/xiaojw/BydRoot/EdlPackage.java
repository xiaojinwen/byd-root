package xiaojw.BydRoot;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 高通 9008 / Firehose 刷机包解析（纯 Java，不依赖 Android 之外的库）。
 *
 * 解析 rawprogram*.xml 中的 {@code <program>} 节点，提取每个分区的写入位置与镜像文件名。
 * 镜像数据流由 EdlClient 在 program 阶段按文件名从刷机包目录读取并下发。
 *
 * 字段与 BackupManifest.buildRawprogram() 生成的格式以及 QFIL / LF深刷 的 rawprogram 兼容。
 */
public final class EdlPackage {

    /** 一个待刷写分区（来自 rawprogram 的 <program> 节点）。 */
    public static final class Program {
        public String label = "unknown";
        public String filename;            // 镜像文件名（刷机包内定位）
        public long startLba;             // 起始扇区（SECTOR_SIZE_IN_BYTES）
        public long numLba;               // 扇区数
        public int physicalPartition = 0; // 物理分区号（通常为 0）
    }

    /** 解析高通 rawprogram XML（流式，支持任意大小）。 */
    public static List<Program> parse(InputStream in) throws IOException {
        List<Program> out = new ArrayList<Program>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, null);
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "program".equals(p.getName())) {
                    Program pr = new Program();
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String name = p.getAttributeName(i);
                        String val = p.getAttributeValue(i);
                        if (name == null) continue;
                        switch (name.toLowerCase()) {
                            case "label":
                                pr.label = val != null ? val : "unknown";
                                break;
                            case "filename":
                                pr.filename = val;
                                break;
                            case "start_sector":
                                pr.startLba = parseLong(val);
                                break;
                            case "num_partition_sectors":
                                pr.numLba = parseLong(val);
                                break;
                            case "physical_partition_number":
                                pr.physicalPartition = (int) parseLong(val);
                                break;
                            default:
                                break;
                        }
                    }
                    // 只保留确有写入位置与长度的分区；file_sector_offset 默认 0，已含进 start_sector。
                    if (pr.startLba > 0 && pr.numLba > 0) out.add(pr);
                }
                ev = p.next();
            }
        } catch (Exception e) {
            throw new IOException("rawprogram 解析失败: " + e.getMessage());
        }
        return out;
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try {
            String t = s.trim();
            if (t.toLowerCase().startsWith("0x")) return Long.parseLong(t.substring(2), 16);
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private EdlPackage() { }
}
