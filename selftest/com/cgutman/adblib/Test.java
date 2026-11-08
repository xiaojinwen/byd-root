package com.cgutman.adblib;

import xiaojw.BydRoot.BackupManifest;
import xiaojw.BydRoot.DeviceConfig;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import javax.crypto.Cipher;

/** 纯 JVM 自测：不依赖 Android API，验证版本解析 / ADB 消息 / RSA 授权数据格式。 */
public class Test {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + " -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[1] config.txt 版本映射");
        Map<String, DeviceConfig.Entry> cfg = DeviceConfig.parse(DeviceConfig.DEFAULT_CONFIG);
        check("5 个控制器版本", cfg.size() == 5, cfg.keySet().toString());
        check("13 -> boot MD5", "B3EDE7F5A948EFD8D3FE764E5418A304"
                .equalsIgnoreCase(cfg.get("13").md5), String.valueOf(cfg.get("13")));
        check("21 -> 核心 2506090", "2506090".equals(cfg.get("21").core), cfg.get("21").core);

        System.out.println("[2] /etc/system.prop 版本识别");
        String prop = "# test\napps.setting.product.outswver=13.1.22.2511250.1\n"
                + "apps.setting.product.outswver = 17.1.22.2511190.1\nro.build.type=user\n";
        String out = DeviceConfig.prop(prop, DeviceConfig.PROP_OUTSWVER);
        check("读取 outswver", "13.1.22.2511250.1".equals(out), String.valueOf(out));
        check("控制器 = 13", "13".equals(DeviceConfig.controllerOf(out)),
                String.valueOf(DeviceConfig.controllerOf(out)));
        check("核心版本 = 2511250", "2511250".equals(DeviceConfig.coreOf(out)),
                String.valueOf(DeviceConfig.coreOf(out)));
        check("boot 文件名", "boot-13.img".equals(DeviceConfig.bootName("13")),
                DeviceConfig.bootName("13"));

        System.out.println("[3] 模块包名内嵌 MD5");
        String m = DeviceConfig.moduleMd5FromName("Module-13_2511_3e93e99b08eca5176651cc1157e11d37.zip");
        check("解析 MD5", "3e93e99b08eca5176651cc1157e11d37".equals(m), String.valueOf(m));
        check("无 MD5 返回 null", DeviceConfig.moduleMd5FromName("Module-13.zip") == null, "not null");

        System.out.println("[4] ADB 消息编解码");
        byte[] pkt = AdbProtocol.generateConnect();
        AdbProtocol.AdbMessage msg =
                AdbProtocol.AdbMessage.parseAdbMessage(new ByteArrayInputStream(pkt));
        check("校验和 / magic", AdbProtocol.validateMessage(msg), "invalid");
        check("CNXN 命令", msg.command == AdbProtocol.CMD_CNXN, Integer.toHexString(msg.command));
        check("maxdata = 65536", msg.arg1 == 65536, String.valueOf(msg.arg1));
        check("host:: 载荷", new String(msg.payload, "UTF-8").startsWith("host::"), "bad payload");

        System.out.println("[5] ADB RSA 公钥（adbd 授权用）");
        AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(new AdbBase64() {
            @Override public String encodeToString(byte[] data) {
                return Base64.getEncoder().encodeToString(data);
            }
        });
        String payload = new String(crypto.getAdbPublicKeyPayload(), "UTF-8");
        check("以 NUL 结尾", payload.endsWith("\0"), "no NUL");
        byte[] raw = Base64.getDecoder().decode(payload.substring(0, payload.indexOf(' ')));
        check("RSAPublicKey 结构 524 字节", raw.length == 524, "len=" + raw.length);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        check("2048bit = 64 words", bb.getInt() == 64, String.valueOf(bb.getInt(0)));
        check("exponent = 65537", ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                .getInt(520) == 65537, "bad exponent");

        System.out.println("[6] AUTH TOKEN 签名（PKCS#1 v1.5 + SHA1）");
        byte[] token = new byte[20];
        new Random(42).nextBytes(token);
        byte[] sig = crypto.signAdbTokenPayload(token);
        check("签名 256 字节", sig.length == 256, "len=" + sig.length);
        Field f = AdbCrypto.class.getDeclaredField("keyPair");
        f.setAccessible(true);
        KeyPair kp = (KeyPair) f.get(crypto);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, kp.getPublic());
        byte[] plain = c.doFinal(sig);
        byte[] tail = Arrays.copyOfRange(plain, plain.length - 20, plain.length);
        // adbd 用 RSA_verify(SHA_DIGEST_LENGTH, token, ...) 验签：末尾 20 字节就是 token 原文
        check("签名内 token 原文一致", Arrays.equals(token, tail), "mismatch");
        check("PKCS#1 v1.5 头部 00 01", (plain[0] & 0xff) == 0 && (plain[1] & 0xff) == 1,
                "bad padding");

        System.out.println("[7] 全分区备份清单 / 恢复脚本");
        check("eMMC 分区推整盘", "/dev/block/mmcblk0".equals(
                BackupManifest.wholeDiskOf("/dev/block/mmcblk0p12")),
                BackupManifest.wholeDiskOf("/dev/block/mmcblk0p12"));
        check("UFS 分区推整盘", "/dev/block/sda".equals(
                BackupManifest.wholeDiskOf("/dev/block/sda12")),
                BackupManifest.wholeDiskOf("/dev/block/sda12"));
        check("整盘不再被截断", "/dev/block/mmcblk0".equals(
                BackupManifest.wholeDiskOf("/dev/block/mmcblk0")),
                BackupManifest.wholeDiskOf("/dev/block/mmcblk0"));
        String table = "boot_a|/dev/block/mmcblk0p12|mmcblk0p12|131072|2048\n"
                + "userdata|/dev/block/mmcblk0p30|mmcblk0p30|41943040|8192000\n"
                + "bad name|/dev/x|x|1|2\n";
        java.util.List<BackupManifest.Part> parts = BackupManifest.parsePartTable(table,
                "/dev/block/by-name");
        check("分区表安全解析（跳过非法名）", parts.size() == 2, String.valueOf(parts.size()));
        check("LBA / 大小换算", parts.get(0).startLba == 2048 && parts.get(0).numLba == 131072
                && parts.get(0).size == 131072L * 512, "wrong lba");
        check("镜像文件名", "boot_a.img".equals(parts.get(0).file), parts.get(0).file);
        check("getprop 解析", "DiLink".equals(BackupManifest.propOf(
                "[ro.product.model]: [DiLink]\n[ro.serialno]: [ABC123]\n",
                "ro.product.model")), "bad prop");
        check("SHA-256 提取", "aabb".length() == 4 && BackupManifest.parseSha256(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  /dev/x\n")
                .equals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                "bad sha");
        check("用户数据分区识别", BackupManifest.isUserData("userdata")
                && BackupManifest.isUserData("cache") && !BackupManifest.isUserData("boot_a"),
                "wrong");
        parts.get(0).dumped = parts.get(0).size;
        parts.get(0).sha256 = "deadbeef";
        parts.get(1).skipped = true;
        BackupManifest.DeviceInfo di = new BackupManifest.DeviceInfo();
        di.created = "2026-11-08 03:30:00";
        di.model = "DiLink";
        di.wholeDisk = "/dev/block/mmcblk0";
        di.sectorSize = 512;
        String json = BackupManifest.buildManifest(di, parts, Arrays.asList("gpt-primary.bin"));
        check("manifest.json 含设备与分区", json.contains("\"model\": \"DiLink\"")
                && json.contains("\"name\": \"boot_a\"") && json.contains("\"startLba\": 2048")
                && json.contains("\"totalBytes\": " + parts.get(0).size), "bad manifest");
        String bat = BackupManifest.buildBat(parts, di.created);
        check("PC 回刷脚本含 fastboot flash", bat.contains("fastboot flash boot_a boot_a.img"),
                "missing flash");
        check("PC 回刷脚本跳过 userdata", bat.contains("rem [跳过] userdata"), "not skipped");
        String xml = BackupManifest.buildRawprogram(parts, 512);
        check("9008 rawprogram 含 LBA", xml.contains("start_sector=\"2048\"")
                && xml.contains("num_partition_sectors=\"131072\""), "bad xml");
        check("文件名安全化", "a_b.img".equals(
                BackupManifest.safeName("a/b") + ".img"), "unsafe");

        System.out.println("\n单元结果: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        LoopbackTest.main(new String[0]);
    }
}
