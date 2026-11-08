# BydRoot · 车机助手

用一台支持 **USB OTG（USB Host）** 的 Android 手机，代替 PC 完成比亚迪车机的 **Root、Magisk 模块刷写、全分区备份 / 恢复** 与 **9008 EDL 深刷救砖**。

> 不需要电脑，不需要 9008 盒子，一根 OTG 数据线 + 手机即可。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-green.svg)
![Version](https://img.shields.io/badge/version-1.5.14-brightgreen.svg)

---

## 功能

| 模块 | 说明 | 连接模式 |
|---|---|---|
| 识别版本 / 校验文件 | 读取车机版本、校验 boot 镜像 MD5 | 系统内 ADB |
| 临时 Root | `fastboot boot` 临时引导，不改分区 | fastboot |
| 长期 Root | `fastboot flash boot` 写入修补后的 boot | fastboot |
| 全分区备份 | 导出 by-name 分区、主备 GPT、SHA-256 清单、PC 回刷脚本 | ADB + root / fastboot 回读 |
| 从备份恢复 | 按备份目录 `fastboot flash` 回写分区 | fastboot |
| 模块部署 | 安装 Magisk / MT、刷入模块 zip、禁用下载应用、修复小迪 | ADB + root |
| 救砖守护 | 装守护模块，连续重启 3 次或卡开机 90s 自动禁用问题模块 | ADB + root |
| **9008 EDL 深刷** | 绕过 bootloader 直接重写存储，用于黑砖救砖（Sahara + Firehose） | USB 05C6:9008 |

UI 分为两个 Tab：

- **常规刷机** —— ①②③④⑤ 分步操作、一键完整流程、备份恢复、分步排查
- **9008 强刷** —— 选择刷机包目录、识别 `rawprogram*.xml` / Firehose 编程器、一键强刷

---

## 环境要求

- **手机端**：Android 8.0（API 26）及以上，支持 **USB Host / OTG**
- **车机端**：开启 USB 调试；fastboot 刷写需停在 bootloader；9008 深刷需进入高通 EDL 模式
- **线材**：USB OTG 数据线（**只能走 USB**，fastboot / 9008 阶段不支持网络 ADB）

## 资源目录准备

在手机存储建一个文件夹，把下列文件**平铺**放进去，再在 App 内选中该目录并授权读写（App 只读这一层，不进子目录）：

| 文件 | 必需 | 说明 |
|---|---|---|
| `boot-<版本号>.img` | ✅（Root 用） | 如 `boot-13.img`，文件名编号由车机 `outswver` 第一段决定，MD5 需与 `config.txt` 一致 |
| 待刷模块 `.zip` | ✅（刷模块用） | 优先取文件名以 `4.5ui-` 开头者；多个 zip 时按名排序取第一个 |
| `Magisk.apk` / `MT.apk` | 建议 | 文件名需精确（大小写不敏感） |
| `config.txt` | 可选 | 每行 `控制器版本 核心版本 boot.img 的 MD5`，缺省用内置版本表 |
| 救砖模块 `.zip` | ✅（救砖用） | 文件名含 `防砖` / `救砖` / `guardian` 才会被识别为守护模块 |

**9008 深刷包**：目录中需含 `rawprogram*.xml`（本工具备份会自动生成 `rawprogram-restore.xml`）+ 各分区 `.img` + 对应机型的 Firehose 编程器（`.mbn` / `.elf`）。可放在所选目录的任意层级，App 会递归查找。

---

## 构建

```bash
# 环境：JDK 17+、Android SDK（platform 36）
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/Android/Sdk

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/BydRoot_<versionName>.apk
```

说明：

- `minSdk 26 / targetSdk 34 / compileSdk 36`（miuix 0.8.x + Compose 要求 compileSdk 36）
- UI：Jetpack Compose + [miuix 0.8.8](https://github.com/miuix-kotlin-multiplatform/miuix)（HyperOS / 澎湃风格）
- 协议层：纯 Java（`AdbClient` / `FastbootClient` / `EdlClient`），与 UI 解耦，便于单测
- 签名统一走根目录 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），debug 与 release 共用同一签名以便覆盖升级
  > `keystore.properties`、`local.properties`、`*.jks` 均已加入 `.gitignore`，请自行创建，切勿提交。

仓库结构与关键类：

```
app/src/main/java/
├── com/cgutman/adblib/        # adblib（BSD-3-Clause），ADB 协议实现
└── xiaojw/BydRoot/
    ├── AdbClient.java          # 系统内 ADB
    ├── FastbootClient.java     # fastboot 刷写 / 回读
    ├── EdlClient.java          # 9008 Sahara + Firehose 深刷引擎
    ├── EdlPackage.java         # rawprogram*.xml 解析
    ├── UsbTransport.java       # USB Host 传输层（ADB/fastboot/EDL）
    ├── ResourceDir.java        # SAF 目录读取与递归查找
    ├── PartitionBackup.java    # 全分区备份 / 恢复
    ├── Workflow.java           # 完整流程编排
    ├── MainActivity.kt         # 入口与任务调度
    ├── FlashScreen.kt          # 刷机页（常规 / 9008 双 Tab）
    └── ToolsScreen.kt          # 工具页
```

---

## 免责声明

**刷机有风险，操作需谨慎。** 解锁 bootloader、改写 boot 分区、9008 深刷均可能导致车机变砖、失去保修或影响行车功能。请务必：

1. 先完成**全分区备份**，确认备份可用再动手；
2. 刷机包 / boot 镜像必须与**车机型号、版本严格匹配**；
3. 全程保持 USB OTG 连接稳定，勿拔线、勿断电。

因使用本工具造成的任何直接或间接损失，作者不承担任何责任。

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。第三方组件归属见 [NOTICE](NOTICE)。
