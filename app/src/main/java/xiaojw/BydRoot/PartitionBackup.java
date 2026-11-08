package xiaojw.BydRoot;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全分区备份 / 恢复编排。
 *
 * 两条备份通道：
 *  A) ADB + root：su 下 dd 每个 by-name 分区，流式导出到手机 SAF 目录（覆盖最全，含 GPT 与 LBA）
 *  B) fastboot fetch：系统起不来但能进 bootloader 时，用 getvar:partition-size + fetch 回读
 *
 * 产物可被 PC fastboot 脚本、高通 9008 Firehose 工具（LF深刷/QFIL）直接用于黑砖恢复。
 */
public final class PartitionBackup {

    public static final String PREFIX = "backup-";

    /** bootloader 无 by-name 表时，用常见分区名逐个探测 partition-size。 */
    private static final String[] COMMON_PARTS = {
            "boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta", "vbmeta_system",
            "system", "vendor", "product", "odm", "system_ext", "super", "misc", "metadata",
            "persist", "modem", "fsg", "fsc", "sec", "splash", "logo", "frp", "keystore",
            "devinfo", "limits", "mota", "param", "ddr", "tz", "hyp", "rpm", "abl", "xbl",
            "xbl_config", "cmnlib", "keymaster", "bootloader", "userdata", "cache"
    };

    public static final class Options {
        public boolean includeUserData = false;   // 巨型用户数据分区是否一起备份
        public boolean verifySha = true;          // 与车机端 sha256sum 双向比对
        public boolean gpt = true;                // 备份主/备 GPT
    }

    public static final class Result {
        public Uri dir;
        public int partitionCount;
        public int dumpedCount;
        public long totalBytes;
        public String method;
    }

    private final Context ctx;
    private final UiBridge ui;
    private final BackupStore store;

    private final ProgressSink progress = new ProgressSink() {
        @Override public void onProgress(long done, long total) {
            int pct = total > 0 ? (int) (done * 100 / total) : 0;
            ui.setProgress(pct, (done / 1024 / 1024) + "MB / " + (total / 1024 / 1024) + "MB");
        }
        @Override public void onMessage(String msg) { ui.log("info", msg); }
    };

    public PartitionBackup(Context ctx, UiBridge ui, BackupStore store) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
        this.store = store;
    }

    public static String timestamp() {
        // 精确到毫秒：秒级目录名在连续两次备份时会复用同一目录，覆盖上一份镜像。
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    }

    public static long estimateBytes(List<BackupManifest.Part> parts, boolean includeUserData) {
        long n = 0;
        for (BackupManifest.Part p : parts) {
            if (!includeUserData && BackupManifest.isUserData(p.name)) continue;
            n += p.size > 0 ? p.size : 0;
        }
        return n;
    }

    public static String human(long bytes) {
        return String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0);
    }

    // ------------------------------------------------------------------
    // 通道 A：ADB + root + dd
    // ------------------------------------------------------------------

    public Result backupViaAdb(AdbClient adb, Options opt) throws Exception {
        String id = adb.shell("su -c id", 20000);
        if (!id.contains("uid=0")) {
            throw new IOException("全分区备份需要 root：未取得 uid=0（" + id.trim() + "）");
        }
        BackupManifest.DeviceInfo dev = readDeviceInfo(adb);
        dev.method = "adb-root-dd";

        String byName = findByNameDir(adb);
        dev.byNameDir = byName;
        ui.log("ok", "分区表路径：" + byName);

        List<BackupManifest.Part> parts = enumerate(adb, byName);
        if (parts.isEmpty()) throw new IOException("未枚举到任何分区（" + byName + "）");
        String whole = "";
        for (BackupManifest.Part p : parts) {
            if (p.realDev != null && p.realDev.startsWith("/dev/")) {
                whole = BackupManifest.wholeDiskOf(p.realDev);
                if (!whole.isEmpty()) break;
            }
        }
        dev.wholeDisk = whole;
        if (!whole.isEmpty()) {
            String blk = BackupManifest.blockNameOf(whole);
            dev.totalSectors = BackupManifest.parseLong(
                    adb.shell("su -c 'cat /sys/class/block/" + blk + "/size'", 10000), 0);
        }
        ui.log("info", "枚举到 " + parts.size() + " 个分区，整盘 " + whole
                + "，总扇区 " + dev.totalSectors);

        long estimate = estimateBytes(parts, opt.includeUserData);
        if (!ui.awaitConfirm("全分区备份",
                "将备份 " + parts.size() + " 个分区，预计 " + human(estimate)
                        + "。\n\n请确保手机剩余空间充足、OTG 供电稳定。\n"
                        + "备份过程中不要断开 USB、不要锁屏或切走应用。\n"
                        + (opt.includeUserData ? "" : "（userdata/data/cache 等大分区按设置跳过）\n")
                        + "\n确认开始？")) {
            throw new IOException("用户取消备份");
        }

        Result r = new Result();
        r.dir = store.createDir(store.rootDocument(), PREFIX + timestamp());
        r.method = dev.method;

        if (opt.gpt && !whole.isEmpty() && dev.totalSectors > 100) {
            backupGpt(adb, whole, dev.totalSectors, r.dir);
        } else {
            ui.log("warn", "未定位整盘设备，跳过 GPT 表备份（分区镜像仍可用于 fastboot 恢复）");
        }

        int index = 0;
        for (BackupManifest.Part p : parts) {
            if (ui.isCancelled()) throw new IOException("用户已停止");
            index++;
            if (!opt.includeUserData && BackupManifest.isUserData(p.name)) {
                p.skipped = true;
                p.note = "用户数据分区，按设置跳过";
                ui.log("info", "[" + index + "/" + parts.size() + "] 跳过 " + p.name);
                continue;
            }
            ui.log("step", "[" + index + "/" + parts.size() + "] 备份 " + p.name
                    + "（" + (p.size / 1024 / 1024) + "MB）");
            ui.setProgress(0, p.name);
            dumpPartition(adb, p, r.dir, opt);
            r.dumpedCount++;
            r.totalBytes += p.dumped;
        }

        writeReports(dev, parts, r);
        r.partitionCount = parts.size();
        ui.log("ok", "全分区备份完成：" + r.dumpedCount + " 个镜像，共 " + human(r.totalBytes));
        ui.log("ok", "备份目录：" + store.describe() + " / " + PREFIX + "…");
        ui.log("warn", "请务必把整个备份目录复制到电脑/移动硬盘另存一份！");
        return r;
    }

    private BackupManifest.DeviceInfo readDeviceInfo(AdbClient adb) throws Exception {
        BackupManifest.DeviceInfo d = new BackupManifest.DeviceInfo();
        String props = adb.shell("getprop", 25000);
        d.brand = BackupManifest.propOf(props, "ro.product.brand");
        d.model = BackupManifest.propOf(props, "ro.product.model");
        d.android = BackupManifest.propOf(props, "ro.build.version.release");
        d.serial = BackupManifest.propOf(props, "ro.serialno");
        d.kernel = BackupManifest.propOf(props, "ro.kernel.version");
        d.outswver = DeviceConfig.prop(adb.shell("cat /etc/system.prop", 25000),
                DeviceConfig.PROP_OUTSWVER);
        if (d.outswver == null) d.outswver = "";
        d.created = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        return d;
    }

    private String findByNameDir(AdbClient adb) throws Exception {
        String[] fixed = {
                "/dev/block/by-name",
                "/dev/block/bootdevice/by-name",
                "/dev/block/bootdevice/by-name/",
                "/dev/block/platform/bootdevice/by-name"
        };
        for (String p : fixed) {
            if (!BackupManifest.parseLs(adb.shell("su -c 'ls -1 " + p + " 2>/dev/null'", 12000))
                    .isEmpty()) return p;
        }
        String wild = BackupManifest.firstLine(adb.shell(
                "su -c 'ls -d /dev/block/platform/*/by-name 2>/dev/null'", 12000));
        if (wild.startsWith("/")) return wild;
        throw new IOException("找不到 /dev/block/by-name，设备可能使用非标准分区布局");
    }

    /** 一次性枚举：name|realDev|block|numSectors|startSector；失败时退回逐条查询。 */
    private List<BackupManifest.Part> enumerate(AdbClient adb, String byName) throws Exception {
        String script = "su -c 'for p in " + byName + "/*; do "
                + "r=$(readlink -f $p); b=$(basename $r); "
                + "echo \"$(basename $p)|$r|$b|"
                + "$(cat /sys/class/block/$b/size 2>/dev/null)|"
                + "$(cat /sys/class/block/$b/start 2>/dev/null)\"; done'";
        String out = adb.shell(script, 60000);
        List<BackupManifest.Part> parts = BackupManifest.parsePartTable(out, byName);
        if (!parts.isEmpty()) return parts;

        ui.log("warn", "组合枚举无结果，改用逐条查询（较慢）");
        parts = new ArrayList<BackupManifest.Part>();
        for (String name : BackupManifest.parseLs(
                adb.shell("su -c 'ls -1 " + byName + "'", 20000))) {
            if (!name.matches("[A-Za-z0-9._-]+")) continue;
            BackupManifest.Part p = new BackupManifest.Part();
            p.name = name;
            p.dev = byName + "/" + name;
            p.realDev = BackupManifest.readlink(
                    adb.shell("su -c 'readlink -f " + p.dev + "'", 10000), p.dev);
            p.block = BackupManifest.blockNameOf(p.realDev);
            p.numLba = BackupManifest.parseLong(adb.shell(
                    "su -c 'cat /sys/class/block/" + p.block + "/size'", 10000), 0);
            p.startLba = BackupManifest.parseLong(adb.shell(
                    "su -c 'cat /sys/class/block/" + p.block + "/start'", 10000), 0);
            p.size = p.numLba * 512;
            p.file = BackupManifest.safeName(name) + ".img";
            parts.add(p);
        }
        return parts;
    }

    private void backupGpt(AdbClient adb, String whole, long sectors, Uri dir)
            throws Exception {
        ui.log("step", "备份 GPT 分区表（磁盘开头 34 扇区 + 末尾 33 扇区）");
        Uri primary = store.createFile(dir, "gpt-primary.bin");
        OutputStream out = store.openWrite(primary);
        try {
            long n = adb.shellStream("su -c 'dd if=" + whole
                    + " bs=512 count=34 2>/dev/null'", out, 120000);
            ui.log("info", "主 GPT：" + n + " 字节");
        } finally { out.close(); }

        long skip = Math.max(0, sectors - 33);
        Uri backup = store.createFile(dir, "gpt-backup.bin");
        out = store.openWrite(backup);
        try {
            long n = adb.shellStream("su -c 'dd if=" + whole + " bs=512 skip=" + skip
                    + " count=33 2>/dev/null'", out, 120000);
            ui.log("info", "备份 GPT：" + n + " 字节（skip=" + skip + "）");
        } finally { out.close(); }
    }

    private void dumpPartition(AdbClient adb, BackupManifest.Part p, Uri dir, Options opt)
            throws Exception {
        Uri file = store.createFile(dir, p.file);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        OutputStream raw = store.openWrite(file);
        long dumped;
        try {
            DigestOutputStream dig = new DigestOutputStream(raw, md);
            dumped = adb.shellStream("su -c 'dd if=" + p.dev
                    + " bs=1048576 2>/dev/null'", dig, 180000);
            dig.flush();
            p.sha256 = BackupManifest.shaHex(md.digest());
        } finally {
            raw.close();
        }
        p.dumped = dumped;

        if (p.size > 0 && dumped != p.size) {
            p.note = "字节数不符（预期 " + p.size + "，实际 " + dumped + "）";
            throw new IOException("分区 " + p.name + " " + p.note
                    + "；adbd 可能改写了二进制流，请改用 fastboot fetch 通道或换一台手机");
        }
        if (opt.verifySha && p.size > 0 && p.size <= 4L * 1024 * 1024 * 1024) {
            String remote = BackupManifest.parseSha256(adb.shell(
                    "su -c 'sha256sum " + p.dev + "'", 300000));
            p.deviceSha = remote == null ? "" : remote;
            p.verified = remote != null && remote.equalsIgnoreCase(p.sha256);
            if (remote == null) p.note = "车机无 sha256sum，仅做本地校验";
            else if (!p.verified) {
                p.note = "SHA-256 双向校验不一致";
                throw new IOException("分区 " + p.name + " SHA-256 校验失败（本地 "
                        + p.sha256 + " / 车机 " + remote + "），备份不可用于恢复");
            }
        } else {
            p.note = opt.verifySha ? "分区过大，跳过车机端二次校验" : "未开启双向校验";
        }
        ui.log("ok", p.name + " → " + p.file + " · " + dumped + "B · sha256=" + p.sha256);
        ui.setProgress(100, p.name + " 完成");
    }

    private BackupManifest.Part makePart(String name, long size, Options opt) {
        BackupManifest.Part p = new BackupManifest.Part();
        p.name = name;
        p.dev = "fastboot:" + name;
        p.realDev = "";
        p.block = "";
        p.size = size;
        p.numLba = size / 512;
        p.file = BackupManifest.safeName(name) + ".img";
        if (!opt.includeUserData && BackupManifest.isUserData(name)) {
            p.skipped = true;
            p.note = "用户数据分区，按设置跳过";
        }
        return p;
    }

    // ------------------------------------------------------------------
    // 通道 B：fastboot fetch（系统起不来，只剩 bootloader）
    // ------------------------------------------------------------------

    public Result backupViaFastboot(FastbootClient fb, Options opt) throws Exception {
        long maxFetch = fb.getVarLong("max-fetch-size", 0);
        if (maxFetch <= 0) {
            throw new IOException("当前 bootloader 不支持 fetch（getvar:max-fetch-size 为空）。"
                    + "请在系统还能开机时用 ADB 通道备份，或用 9008/BROM 深刷工具读取。");
        }
        ui.log("info", "bootloader max-fetch-size = " + maxFetch + "B，开始探测分区…");

        BackupManifest.DeviceInfo dev = new BackupManifest.DeviceInfo();
        dev.method = "fastboot-fetch";
        dev.created = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        dev.model = fb.tryGetVar("product");
        if (dev.model == null) dev.model = "";
        dev.serial = fb.tryGetVar("serialno");
        if (dev.serial == null) dev.serial = "";
        dev.android = "";

        List<BackupManifest.Part> parts = new ArrayList<BackupManifest.Part>();
        // 优先 getvar:all 一次性拿全部分区大小，避免逐个探测上百个名字
        java.util.Map<String, String> all = new java.util.LinkedHashMap<String, String>();
        try { all = fb.getAllVars(); }
        catch (Exception e) { ui.log("warn", "getvar:all 不可用，改逐个探测：" + e.getMessage()); }
        for (java.util.Map.Entry<String, String> e : all.entrySet()) {
            if (!e.getKey().startsWith("partition-size:")) continue;
            String name = e.getKey().substring("partition-size:".length()).trim();
            long size = FastbootClient.parseVarLong(e.getValue(), 0);
            if (size > 0 && name.matches("[A-Za-z0-9._-]+")) {
                parts.add(makePart(name, size, opt));
            }
        }
        if (parts.isEmpty()) {
            ui.log("warn", "bootloader 未在 getvar:all 上报分区，改用常见分区名逐个探测");
            List<String> candidates = new ArrayList<String>(Arrays.asList(COMMON_PARTS));
            for (String base : new ArrayList<String>(candidates)) {
                candidates.add(base + "_a");
                candidates.add(base + "_b");
            }
            for (String name : candidates) {
                long size = fb.getVarLong("partition-size:" + name, 0);
                if (size > 0) parts.add(makePart(name, size, opt));
            }
        }
        if (parts.isEmpty()) throw new IOException("bootloader 未上报任何 partition-size，无法枚举分区");

        if (!ui.awaitConfirm("fastboot 分区回读",
                "探测到 " + parts.size() + " 个分区，预计 " + human(estimateBytes(parts, opt.includeUserData))
                        + "。\n该通道仅回读分区内容，不含 GPT 与 LBA 信息。\n确认开始？")) {
            throw new IOException("用户取消备份");
        }

        Result r = new Result();
        r.dir = store.createDir(store.rootDocument(), PREFIX + timestamp());
        r.method = dev.method;
        int index = 0;
        for (BackupManifest.Part p : parts) {
            if (ui.isCancelled()) throw new IOException("用户已停止");
            index++;
            if (p.skipped) { ui.log("info", "[" + index + "/" + parts.size() + "] 跳过 " + p.name); continue; }
            ui.log("step", "[" + index + "/" + parts.size() + "] 回读 " + p.name
                    + "（" + (p.size / 1024 / 1024) + "MB）");
            Uri file = store.createFile(r.dir, p.file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            OutputStream raw = store.openWrite(file);
            long dumped = 0;
            try {
                DigestOutputStream dig = new DigestOutputStream(raw, md);
                long offset = 0;
                while (offset < p.size) {
                    long chunk = Math.min(maxFetch, p.size - offset);
                    fb.fetch(p.name, offset, chunk, dig, progress);
                    offset += chunk;
                    dumped = offset;
                    ui.setProgress((int) (offset * 100 / p.size), p.name + " "
                            + (offset / 1024 / 1024) + "MB");
                }
                dig.flush();
                p.sha256 = BackupManifest.shaHex(md.digest());
            } finally { raw.close(); }
            p.dumped = dumped;
            p.verified = false;
            p.note = "fastboot fetch 回读，未做车机端二次校验";
            r.dumpedCount++;
            r.totalBytes += dumped;
            ui.log("ok", p.name + " → " + p.file + " · " + dumped + "B");
        }
        writeReports(dev, parts, r);
        r.partitionCount = parts.size();
        ui.log("ok", "fastboot 回读完成：" + r.dumpedCount + " 个镜像");
        return r;
    }

    // ------------------------------------------------------------------
    // 恢复：从备份目录逐分区 fastboot flash
    // ------------------------------------------------------------------

    public int restoreViaFastboot(FastbootClient fb, Uri backupDir) throws Exception {
        List<BackupStore.Entry> files = store.list(backupDir);
        List<BackupStore.Entry> imgs = new ArrayList<BackupStore.Entry>();
        BackupStore.Entry manifest = null;
        for (BackupStore.Entry e : files) {
            if (e.directory) continue;
            if (e.name.equalsIgnoreCase("manifest.json")) manifest = e;
            if (e.name.toLowerCase(Locale.US).endsWith(".img")) imgs.add(e);
        }
        // 防止误选资源根目录：那里平铺着 boot-13.img 等刷机资源，若被当成分区镜像
        // 回刷会直接刷坏车机。真正的备份目录必定带 manifest.json 清单。
        if (manifest == null) throw new IOException(
                "所选目录不是 BydRoot 备份目录（缺少 manifest.json）。请进入资源目录下 backup-… 子目录再选一次。");
        java.util.Set<String> allowed = manifestFiles(store.readText(manifest.uri));
        if (allowed.isEmpty()) throw new IOException("manifest.json 中没有可恢复的分区镜像");
        if (imgs.isEmpty()) throw new IOException("备份目录里没有 .img 镜像");
        java.util.Collections.sort(imgs, new java.util.Comparator<BackupStore.Entry>() {
            @Override public int compare(BackupStore.Entry a, BackupStore.Entry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        int done = 0;
        for (BackupStore.Entry e : imgs) {
            if (ui.isCancelled()) throw new IOException("用户已停止");
            if (!allowed.contains(e.name)) {
                ui.log("warn", "跳过不在备份清单里的文件 " + e.name);
                continue;
            }
            String part = e.name.substring(0, e.name.length() - 4);
            if (BackupManifest.isUserData(part)) {
                ui.log("warn", "跳过用户数据分区 " + part + "（如需回刷请手动执行）");
                continue;
            }
            long size = store.sizeOf(e);
            if (size <= 0) throw new IOException("无法取得 " + e.name + " 的文件大小");
            ui.log("step", "回刷 " + part + "（" + (size / 1024 / 1024) + "MB）");
            InputStream in = store.openRead(e.uri);
            try { fb.flash(part, in, size, progress); }
            finally { in.close(); }
            done++;
        }
        if (done == 0) throw new IOException("清单中的镜像都不在当前目录，未回刷任何分区");
        return done;
    }

    /** 从本工具生成的 manifest.json 里取出分区镜像白名单（格式固定，不引第三方 JSON 库）。 */
    private static java.util.Set<String> manifestFiles(String json) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (json == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"file\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ------------------------------------------------------------------

    private void writeReports(BackupManifest.DeviceInfo dev,
                              List<BackupManifest.Part> parts, Result r) throws Exception {
        List<String> reports = new ArrayList<String>();
        store.writeText(r.dir, "manifest.json", BackupManifest.buildManifest(dev, parts, allFiles(r.dir)));
        reports.add("manifest.json");
        store.writeText(r.dir, "restore-fastboot.bat", BackupManifest.buildBat(parts, dev.created));
        reports.add("restore-fastboot.bat");
        store.writeText(r.dir, "restore-fastboot.sh", BackupManifest.buildSh(parts, dev.created));
        reports.add("restore-fastboot.sh");
        store.writeText(r.dir, "rawprogram-restore.xml",
                BackupManifest.buildRawprogram(parts, dev.sectorSize));
        reports.add("rawprogram-restore.xml");
        store.writeText(r.dir, "README-恢复说明.txt",
                BackupManifest.buildReadme(dev, parts, r.totalBytes));
        reports.add("README-恢复说明.txt");
        ui.log("ok", "已生成清单与恢复脚本：" + reports);
    }

    private List<String> allFiles(Uri dir) {
        List<String> out = new ArrayList<String>();
        for (BackupStore.Entry e : store.list(dir)) if (!e.directory) out.add(e.name);
        java.util.Collections.sort(out);
        return out;
    }

    public List<Uri> listBackupDirs() {
        List<Uri> out = new ArrayList<Uri>();
        for (BackupStore.Entry e : store.list(store.rootDocument())) {
            if (e.directory && e.name.startsWith(PREFIX)) out.add(e.uri);
        }
        return out;
    }
}
