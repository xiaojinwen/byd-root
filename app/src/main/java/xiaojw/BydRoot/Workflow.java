package xiaojw.BydRoot;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * 车机 Root 与模块部署的完整流程，全部由手机端协议实现：
 *   ADB 命令   -> AdbClient（自实现 ADB over USB/WiFi）
 *   fastboot   -> FastbootClient（自实现 fastboot over USB Host）
 *   文件 MD5   -> ResourceDir.md5
 * 所需镜像与模块包由用户自行准备，app 不附带任何外部资源。
 */
public final class Workflow {

    public static final int CONNECT = 0;
    public static final int DETECT = 1;
    public static final int VERIFY = 2;
    public static final int TEMP_ROOT = 3;
    public static final int PERM_ROOT = 4;
    public static final int INSTALL_APKS = 5;
    public static final int DISABLE_DOCS = 6;
    public static final int MANUAL_STEPS = 7;
    public static final int CHECK_ROOT = 8;
    public static final int FLASH_MODULE = 9;
    public static final int FINISH = 10;
    public static final int FIX_VOICE = 11;
    public static final int REBOOT = 12;
    public static final int FLASH_GUARDIAN = 13;
    public static final int AUTO_BACKUP = 14;

    /** 推送到车机的临时文件名；救砖模块不再内置，须由用户在资源目录提供（文件名含防砖/救砖）。 */
    public static final String GUARDIAN_ASSET = "guardian-module.zip";

    /** 内置模块的 Magisk id，仅用于探测安装目录与自检日志，不对用户展示。 */
    private static final String GUARDIAN_ID = "magisk-brick-guardian";

    public static final String[] STEP_NAMES = {
            "连接设备", "识别版本", "校验文件", "临时 Root", "长期 Root",
            "安装 Magisk / MT", "禁用下载应用", "Magisk 修复引导", "验证 Root",
            "刷入模块", "清缓存 / 完成", "修复小迪", "重启车机", "安装救砖模块",
            "自动全分区备份"
    };

    /**
     * 完整流程：临时 Root 后、刷入 boot 前先备份，此时 boot 分区仍是原始镜像；
     * 救砖守护先于待刷模块包就位并重启自检，后续模块若导致无法开机才能被自动禁用。
     */
    public static final int[] FULL_FLOW = {
            CONNECT, DETECT, VERIFY, TEMP_ROOT, AUTO_BACKUP, PERM_ROOT, INSTALL_APKS,
            DISABLE_DOCS, MANUAL_STEPS, CHECK_ROOT, FLASH_GUARDIAN, FLASH_MODULE, FINISH
    };

    /** 单步按钮展示顺序：与完整流程一致（救砖先于模块包），末尾补两个附加工具。 */
    public static final int[] STEP_ORDER = {
            CONNECT, DETECT, VERIFY, TEMP_ROOT, PERM_ROOT, INSTALL_APKS,
            DISABLE_DOCS, MANUAL_STEPS, CHECK_ROOT, FLASH_GUARDIAN, FLASH_MODULE, FINISH,
            FIX_VOICE, REBOOT
    };

    /** 需要在 fastboot / bootloader 模式下执行的单步。 */
    public static boolean isFastbootStep(int s) {
        return s == TEMP_ROOT || s == PERM_ROOT;
    }

    /** 依赖资源目录里文件的步骤（救砖模块也已改为用户自备，同样在此列）。 */
    public static boolean needsResourceDir(int s) {
        return s == DETECT || s == VERIFY || s == TEMP_ROOT || s == PERM_ROOT
                || s == INSTALL_APKS || s == FLASH_MODULE || s == AUTO_BACKUP
                || s == FLASH_GUARDIAN;
    }

    public static final class Settings {
        public boolean wifiAdb = false;
        public String host = "";
        public int port = 5555;
    }

    private final Context ctx;
    private final UiBridge ui;
    private final Settings settings;
    private ResourceDir res;
    private AdbClient adb;

    private String outswver;
    private String controller;
    private String core;
    private Map<String, DeviceConfig.Entry> config;
    private ResourceDir.FileRef bootFile;
    private ResourceDir.FileRef moduleFile;
    private boolean singleStep;

    public Workflow(Context ctx, UiBridge ui, ResourceDir res, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
        this.res = res;
        this.settings = settings;
    }

    public void setResourceDir(ResourceDir r) { this.res = r; }
    public String getController() { return controller; }
    public String getOutswver() { return outswver; }

    private void checkCancel() throws IOException {
        if (ui.isCancelled()) throw new IOException("用户已停止");
    }

    private final ProgressSink progress = new ProgressSink() {
        @Override
        public void onProgress(long done, long total) {
            int pct = total > 0 ? (int) (done * 100 / total) : 0;
            ui.setProgress(pct, (done / 1024 / 1024) + "MB / " + (total / 1024 / 1024) + "MB");
        }
        @Override
        public void onMessage(String msg) { ui.log("info", msg); }
    };

    public void run(int[] steps) {
        singleStep = steps.length == 1;
        // 每次执行都从干净状态开始：Workflow 实例跨多次执行复用（宿主只更新 resDir），
        // 若不重置，上一台车机留下的 controller/bootFile 会被直接刷入下一台（变砖风险）。
        // 清空后，刷入 boot 前的 verify() 会因 bootFile==null 强制重新探测当前车机版本。
        outswver = null;
        controller = null;
        core = null;
        config = null;
        bootFile = null;
        moduleFile = null;
        try {
            // 缺资源目录时先明确报错，不再空跑 USB 连接 / 弹出一堆确认框。
            for (int s : steps) {
                if (needsResourceDir(s) && res == null) {
                    throw new IOException("请先完成第 1 步：选择资源目录");
                }
            }
            boolean hasRootStep = false;
            for (int s : steps) if (s == TEMP_ROOT || s == PERM_ROOT) hasRootStep = true;
            if (hasRootStep && !ui.awaitConfirm("风险提示",
                    "Root 操作存在风险：设备变砖、数据丢失、失去保修。\n\n"
                    + "请确认：车机已开 USB 调试、电脑杀毒/防火墙不会拦截、手机支持 USB OTG 且已用数据线连接。\n"
                    + "一切风险自负，确认继续？")) {
                ui.log("warn", "用户取消执行");
                return;
            }
            for (int i = 0; i < steps.length; i++) {
                checkCancel();
                ui.log("step", "[" + (i + 1) + "/" + steps.length + "] " + STEP_NAMES[steps[i]]);
                ui.setProgress(0, STEP_NAMES[steps[i]]);
                step(steps[i]);
                checkCancel();
            }
            ui.setProgress(100, "全部完成");
            ui.setStatus("流程完成");
            ui.log("ok", "全部步骤执行完成");
        } catch (Exception e) {
            ui.log("err", "流程中断：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            ui.setStatus("执行失败");
        } finally {
            ui.setBusy(false);
        }
    }

    private void step(int s) throws Exception {
        switch (s) {
            case CONNECT:      connectAdb(); break;
            case DETECT:       detect(); break;
            case VERIFY:       verify(); break;
            case TEMP_ROOT:    flashImage(false); break;
            case PERM_ROOT:    flashImage(true); break;
            case INSTALL_APKS: installApks(); break;
            case DISABLE_DOCS: disableDocs(); break;
            case MANUAL_STEPS: manualSteps(); break;
            case CHECK_ROOT:   checkRoot(); break;
            case FLASH_MODULE: flashModule(); break;
            case FINISH:       finish(); break;
            case FIX_VOICE:    fixVoice(); break;
            case REBOOT:       rebootDevice(); break;
            case FLASH_GUARDIAN: flashGuardian(); break;
            case AUTO_BACKUP:   autoBackup(); break;
            default: throw new IOException("未知步骤 " + s);
        }
    }

    /** 供宿主在自动备份步骤复用当前 ADB 连接，避免 USB Host 上再开一条连接。 */
    public AdbClient currentAdb() { return adb; }

    /**
     * 临时 Root 之后、长期 Root 之前：系统已带 root 但 boot 分区还没被 flash，
     * 此时 dd 出来的才是未改动的原始镜像。是否开启由宿主决定。
     */
    private void autoBackup() throws Exception {
        connectAdb();
        if (ui.onAutoBackup()) return;
        if (!ui.awaitConfirm("自动备份未完成",
                "临时 Root 后未能完成全分区备份，继续将没有原始分区镜像可回刷。\n\n"
                + "建议取消流程，先手动执行「fastboot 回读」或在备份选项里调整后重试。\n"
                + "点「继续」跳过备份继续 Root，点「取消」中止流程。")) {
            throw new IOException("用户选择中止：自动全分区备份未完成");
        }
        ui.log("warn", "已按用户选择跳过自动备份，继续 Root 流程（无原始镜像可回刷）");
    }

    // ---------- 连接管理 ----------

    private AdbClient newClient() { return new AdbClient(ctx, ui); }

    private void connectAdb() throws Exception {
        if (adb != null && adb.isConnected()) { ui.log("ok", "复用当前 ADB 连接"); return; }
        if (adb != null) adb.close();
        adb = newClient();
        if (settings.wifiAdb && settings.host != null && !settings.host.trim().isEmpty()) {
            adb.connectTcp(settings.host.trim(), settings.port, 15000);
        } else {
            adb.connectUsb(30000);
        }
        ui.setStatus("ADB 已连接");
    }

    /** 设备重启后 USB 会重新枚举，轮询直到 ADB 握手成功。 */
    private void waitAdb(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean announced = false;
        while (System.currentTimeMillis() < deadline) {
            checkCancel();
            if (adb != null) adb.close();
            adb = newClient();
            try {
                if (settings.wifiAdb && settings.host != null && !settings.host.trim().isEmpty()) {
                    adb.connectTcp(settings.host.trim(), settings.port, 8000);
                } else {
                    adb.connectUsb(8000);
                }
                if (adb.isConnected()) {
                    ui.log("ok", "ADB 连接已恢复");
                    ui.setStatus("ADB 已连接");
                    return;
                }
            } catch (Exception e) {
                if (!announced) {
                    ui.log("info", "等待车机开机并恢复 ADB…（" + e.getMessage() + "）");
                    announced = true;
                }
            }
            Thread.sleep(2000);
        }
        throw new IOException("等待 ADB 恢复超时，请检查 USB 连接与车机状态");
    }

    private void requireRes() throws IOException {
        if (res == null) throw new IOException("请先选择资源目录");
    }

    // ---------- 阶段 1：版本识别 ----------

    private void detect() throws Exception {
        connectAdb();
        String prop = adb.shell("cat /etc/system.prop", 25000);
        outswver = DeviceConfig.prop(prop, DeviceConfig.PROP_OUTSWVER);
        if (outswver == null || outswver.isEmpty()) {
            throw new IOException("未读到 " + DeviceConfig.PROP_OUTSWVER + "，检查 ADB 连接与车机系统");
        }
        controller = DeviceConfig.controllerOf(outswver);
        core = DeviceConfig.coreOf(outswver);
        ui.log("info", "系统版本 outswver = " + outswver);
        ui.log("info", "控制器版本 = " + controller + "，核心版本 = " + core);

        requireRes();
        String cfgText = DeviceConfig.DEFAULT_CONFIG;
        ResourceDir.FileRef cfg = res.findConfig();
        if (cfg != null) {
            cfgText = res.readText(cfg);
            ui.log("info", "使用目录内 config.txt");
        } else {
            ui.log("warn", "目录内无 config.txt，使用内置版本映射表");
        }
        config = DeviceConfig.parse(cfgText);
        DeviceConfig.Entry e = config.get(controller);
        if (e == null) {
            throw new IOException("控制器 " + controller + " 不受支持（支持："
                    + config.keySet() + "）");
        }
        if (core != null && !core.equals(e.core)) {
            throw new IOException("版本不匹配：预期核心版本 " + e.core + "，实际 " + core
                    + "，请先刷机至对应版本");
        }
        ui.log("ok", "版本验证通过，将使用 " + DeviceConfig.bootName(controller));
        ui.setStatus("控制器 " + controller + " · " + outswver);
    }

    // ---------- 阶段 2：文件校验 ----------

    private void verify() throws Exception {
        if (controller == null || config == null) detect();
        requireRes();
        DeviceConfig.Entry e = config.get(controller);
        bootFile = res.findBoot(controller);
        if (bootFile == null) throw new IOException("缺少 " + DeviceConfig.bootName(controller));
        String bootMd5 = res.md5(bootFile);
        if (!bootMd5.equalsIgnoreCase(e.md5)) {
            throw new IOException("boot.img MD5 校验失败：期望 " + e.md5 + "，实际 " + bootMd5);
        }
        ui.log("ok", "boot.img MD5 通过（" + bootMd5 + "，" + bootFile.size + " 字节）");

        moduleFile = res.findModuleZip();
        if (moduleFile == null) throw new IOException("未找到待刷模块包（.zip）");
        String nameMd5 = DeviceConfig.moduleMd5FromName(moduleFile.name);
        if (nameMd5 != null) {
            String real = res.md5(moduleFile);
            if (!real.equalsIgnoreCase(nameMd5)) {
                throw new IOException("模块包 MD5 校验失败：文件名声明 " + nameMd5 + "，实际 " + real);
            }
            ui.log("ok", "模块包 MD5 通过（" + real + "）");
        } else {
            ui.log("warn", "模块包名未带 MD5，跳过校验：" + moduleFile.name);
        }
        ui.log("info", "模块包：" + moduleFile.name + "（" + moduleFile.size + " 字节）");
        long bs = res.sizeOf(bootFile);
        long ms = res.sizeOf(moduleFile);
        if (bs <= 0 || ms <= 0) {
            throw new IOException("无法取得文件大小，请用系统文件选择器重新选择资源目录");
        }
    }

    // ---------- 阶段 3/4：临时 Root / 长期 Root ----------

    private void flashImage(boolean permanent) throws Exception {
        requireRes();
        if (bootFile == null) verify();
        connectAdb();
        ui.log("info", "重启至 bootloader…");
        adb.rebootBootloader();
        adb.close();
        adb = null;
        Thread.sleep(3000);

        FastbootClient fb = FastbootClient.connect(ctx, ui, 120000);
        InputStream in = null;
        try {
            in = res.open(bootFile);
            fb.download(in, res.sizeOf(bootFile), progress);
            if (permanent) {
                fb.flashBoot();
                ui.log("info", "重启车机…");
                fb.reboot();
            } else {
                fb.boot();
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            fb.close();
        }

        ui.log("info", "等待车机重新启动（临时 Root 启动较慢，请耐心）…");
        Thread.sleep(15000);
        waitAdb(180000);
    }

    // ---------- 阶段 5：安装 Magisk / MT ----------

    private void installApks() throws Exception {
        connectAdb();
        installApk("Magisk.apk");
        installApk("MT.apk");
    }

    private void installApk(String name) throws Exception {
        requireRes();
        ResourceDir.FileRef f = res.findApk(name);
        if (f == null) { ui.log("warn", "跳过：未找到 " + name); return; }
        String tmp = "/data/local/tmp/" + name;
        ui.log("info", "推送 " + name + "…");
        InputStream in = res.open(f);
        try { adb.push(in, res.sizeOf(f), tmp, "0644", progress); }
        finally { in.close(); }
        String out = adb.shell("pm install -r " + tmp, 180000);
        String trimmed = out.trim();
        ui.log(trimmed.contains("Success") ? "ok" : "warn", name + " 安装结果：" + trimmed);
        adb.shell("rm -f " + tmp, 15000);
    }

    // ---------- 阶段 6：禁用下载应用 ----------

    private void disableDocs() throws Exception {
        connectAdb();
        String out = adb.shell("pm disable-user --user 0 com.android.documentsui", 25000);
        ui.log("ok", "禁用 com.android.documentsui：" + out.trim());
    }

    // ---------- 阶段 7：手动步骤引导 ----------

    private void manualSteps() throws Exception {
        if (!ui.awaitConfirm("手动步骤 1 / 2",
                "在车机上打开 Magisk，点击「修复」（会自动重启）。\n完成后点「继续」。")) {
            throw new IOException("用户取消");
        }
        ui.log("info", "等待 Magisk 修复后的重启…");
        waitAdb(180000);
        if (!ui.awaitConfirm("手动步骤 2 / 2",
                "设备开机后，手动滚轮重启一次。\n完成后点「继续」，将开始部署模块包。")) {
            throw new IOException("用户取消");
        }
        waitAdb(180000);
    }

    // ---------- 阶段 8：验证 Root ----------

    private void checkRoot() throws Exception {
        connectAdb();
        for (int i = 0; i < 3; i++) {
            String id = adb.shell("su -c id", 20000);
            if (id.contains("uid=0")) {
                ui.log("ok", "Root 权限已获取：" + id.trim());
                return;
            }
            ui.log("warn", "Root 验证失败（第 " + (i + 1) + "/3 次）：" + id.trim());
            Thread.sleep(3000);
        }
        throw new IOException("Root 权限验证失败，请确认 Magisk 已修复且车机已授权 su");
    }

    // ---------- 阶段 9：刷入模块 ----------

    private void flashModule() throws Exception {
        requireRes();
        if (moduleFile == null) verify();
        connectAdb();
        String tmp = "/data/local/tmp/module-" + controller + ".zip";
        ui.log("info", "推送模块包到 " + tmp);
        InputStream in = res.open(moduleFile);
        try { adb.push(in, res.sizeOf(moduleFile), tmp, "0777", progress); }
        finally { in.close(); }
        adb.shell("su -c 'chown root:root " + tmp + "'", 15000);
        adb.shell("su -c 'chmod 0777 " + tmp + "'", 15000);
        ui.log("info", "执行 magisk --install-module …");
        String out = adb.shell("su -c 'magisk --install-module " + tmp + "'", 180000);
        ui.log("ok", "模块安装输出：" + out.trim());
        adb.shell("su -c 'rm -f " + tmp + "'", 15000);
        ui.log("ok", "模块包部署完成");
    }

    // ---------- 阶段 9.5：安装救砖守护模块 ----------

    /**
     * 系统内 ADB + root 执行，不需要 fastboot。
     * 守护模块需在待刷模块包之前就位：之后若模块导致无法开机，连续重启 3 次
     * 或开机卡住 90 秒后会自动禁用问题模块，避免彻底黑砖。
     */
    private void flashGuardian() throws Exception {
        connectAdb();
        String id = adb.shell("su -c id", 20000);
        if (!id.contains("uid=0")) {
            throw new IOException("安装救砖模块需要 root，请先完成「长期 Root」并在 Magisk 里授权 su");
        }

        InputStream in = null;
        String tmp = "/data/local/tmp/" + GUARDIAN_ASSET;
        try {
            if (res == null) {
                throw new IOException("请先在「准备资源目录」选择目录，并放入救砖模块（文件名含防砖/救砖）");
            }
            ResourceDir.FileRef f = res.findGuardianZip();
            if (f == null) {
                throw new IOException("未在资源目录找到救砖模块（文件名需含防砖/救砖）");
            }
            in = res.open(f);
            long size = res.sizeOf(f);
            String from = f.name + "（资源目录）";
            if (size <= 0) throw new IOException("救砖模块大小无效：" + from);

            ui.log("info", "救砖模块来源：" + from + "（" + size + " 字节）");
            ui.log("info", "推送到 " + tmp);
            adb.push(in, size, tmp, "0777", progress);
            adb.shell("su -c 'chmod 0777 " + tmp + "'", 15000);

            ui.log("info", "执行 magisk --install-module …");
            String out = adb.shell("su -c 'magisk --install-module " + tmp + "'", 180000);
            String low = out.toLowerCase(Locale.US);
            boolean bad = low.contains("! installation failed") || low.contains("error:");
            boolean done = low.contains("done") || low.contains("success");
            if (bad && !done) {
                throw new IOException("救砖模块安装失败：" + out.trim());
            }
            ui.log(done ? "ok" : "warn",
                    (done ? "救砖模块安装完成" : "模块安装命令已下发，但未读到明确结果") + "：" + out.trim());

            // Magisk 不同版本会装到 modules 或 modules_update（重启后才迁移），两处任一存在即成功
            String installed = adb.shell(
                    "su -c 'ls -d /data/adb/modules/" + GUARDIAN_ID + " "
                            + "/data/adb/modules_update/" + GUARDIAN_ID + " 2>/dev/null'", 20000);
            if (installed.contains(GUARDIAN_ID)) {
                ui.log("ok", "已确认救砖守护模块目录存在");
            } else if (done) {
                // 命令已回显完成，但目录探测不到（部分定制 Magisk 路径不同），不误判失败
                ui.log("warn", "命令回报完成，但未探测到模块目录；重启后请在 Magisk 模块页确认");
            } else if (singleStep) {
                throw new IOException("未在 /data/adb/modules* 下找到救砖守护模块，安装可能未生效");
            } else {
                ui.log("warn", "未探测到模块目录，完整流程继续；重启后请在 Magisk 模块页确认");
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            try { adb.shell("su -c 'rm -f " + tmp + "'", 15000); } catch (Exception ignored) { }
        }

        ui.log("warn", "救砖模块需重启后才生效；守护脚本会在首次开机后自检约 90 秒。");
        if (singleStep) {
            if (ui.awaitConfirm("重启使救砖模块生效",
                    "模块已写入 /data/adb/modules，但 Magisk 模块需重启才会加载。\n\n"
                    + "点「继续」立即通过 ADB 重启车机；点「取消」稍后自行滚轮重启。")) {
                rebootDevice();
                waitGuardianReady();
            }
        } else {
            ui.log("info", "完整流程：先重启一次让守护加载并自检，再刷模块包…");
            rebootDevice();
            waitGuardianReady();
        }
    }

    /**
     * 重启后确认守护已真正加载。所有判断都有超时且只告警不中断：
     * 定制 Magisk / 车机系统的目录与日志路径可能与标准实现不同。
     */
    private void waitGuardianReady() throws Exception {
        ui.log("info", "等待车机重启并重新连接 ADB…");
        Thread.sleep(8000);
        waitAdb(180000);

        boolean loaded = false;
        long loadDeadline = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < loadDeadline) {
            checkCancel();
            ensureAdb();
            String d = tryShell("su -c 'ls -d /data/adb/modules/" + GUARDIAN_ID + " 2>/dev/null'", 15000);
            if (d.contains(GUARDIAN_ID)) { loaded = true; break; }
            Thread.sleep(4000);
        }
        ui.log(loaded ? "ok" : "warn", loaded
                ? "救砖守护模块已加载"
                : "未探测到守护模块目录，可能仍在 modules_update；请重启后在 Magisk 模块页确认");

        // service.sh 会 sleep 90 秒自检后写出完成标记
        boolean checked = false;
        long checkDeadline = System.currentTimeMillis() + 150000;
        while (System.currentTimeMillis() < checkDeadline) {
            checkCancel();
            ensureAdb();
            String s = tryShell("su -c 'cat /data/adb/modules/" + GUARDIAN_ID + "/"
                    + "brick_guardian_late_debug.log 2>/dev/null'", 15000);
            if (s.contains("Late Script execution completed")) { checked = true; break; }
            Thread.sleep(5000);
        }
        ui.log(checked ? "ok" : "warn", checked
                ? "救砖守护自检完成，可以继续刷模块包"
                : "未在 150 秒内读到守护自检完成标记（日志路径可能不同），继续流程");

        // 后续刷模块包是关键步骤，重建一次连接确保可用
        if (adb != null) adb.close();
        adb = null;
        connectAdb();
    }

    /** Magisk 迁移 modules_update 时可能触发额外重启，探测前先确保连接可用。 */
    private void ensureAdb() throws Exception {
        if (adb == null || !adb.isConnected()) {
            ui.log("info", "检测到车机再次重启，等待 ADB 恢复…");
            waitAdb(180000);
        }
    }

    /** 轮询期间 ADB 可能短暂抖动，探测命令失败按「未就绪」处理而不中断流程。 */
    private String tryShell(String cmd, int timeoutMs) {
        try { return adb.shell(cmd, timeoutMs); } catch (Exception e) { return ""; }
    }

    // ---------- 阶段 10：清理 / 完成 ----------

    private void finish() throws Exception {
        connectAdb();
        adb.shell("su -c 'rm -rf /data/system/package_cache/*'", 30000);
        adb.shell("su -c 'rm -rf /data/dalvik-cache/*'", 30000);
        ui.log("ok", "系统缓存清理完成");
        if (ui.awaitConfirm("部署完成",
                "模块包已部署。滚轮重启使配置生效；若未生效请在 Magisk 手动挂载该模块。\n\n"
                + "点「继续」立即通过 ADB 重启车机，点「取消」稍后自行滚轮重启。")) {
            rebootDevice();
        } else {
            ui.log("info", "已跳过自动重启，请手动滚轮重启");
        }
    }

    // ---------- 附加工具 ----------

    private void fixVoice() throws Exception {
        connectAdb();
        String out = adb.shell("su -c 'pm enable com.byd.autovoice'", 25000);
        ui.log("ok", "修复小迪：" + out.trim());
    }

    private void rebootDevice() throws Exception {
        connectAdb();
        try { adb.shell("reboot", 5000); } catch (Exception ignored) { }
        ui.log("ok", "已发送重启命令");
    }

    public void close() {
        if (adb != null) adb.close();
        adb = null;
    }
}
