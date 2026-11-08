package xiaojw.BydRoot;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 通过 SAF 目录树读取用户自备的刷机资源文件。
 * 只用 framework API（DocumentsContract），不引入 androidx，保证离线构建零依赖。
 */
public final class ResourceDir {

    public static final class FileRef {
        public final String name;
        public final Uri uri;
        public final long size;
        FileRef(String name, Uri uri, long size) {
            this.name = name;
            this.uri = uri;
            this.size = size;
        }
    }

    private final Context ctx;
    private final Uri tree;

    public ResourceDir(Context ctx, Uri tree) {
        this.ctx = ctx.getApplicationContext();
        this.tree = tree;
    }

    public static int persistFlags() {
        // 备份产物要写回这个目录树，读写权限都要持久化
        return Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    }

    public List<FileRef> list() {
        List<FileRef> out = new ArrayList<FileRef>();
        ContentResolver cr = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null);
        if (c == null) return out;
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? -1 : c.getLong(2);
                String mime = c.getString(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                out.add(new FileRef(name, uri, size));
            }
        } finally { c.close(); }
        return out;
    }

    public FileRef find(String name) {
        for (FileRef f : list()) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /**
     * 递归列出目录树下的全部文件（含子目录），供 9008 强刷包定位用。
     * 强刷包通常把 rawprogram*.xml、各分区 .img 与 Firehose 编程器平铺或分目录放置，
     * 顶层 list() 看不到子目录里的镜像，必须递归。
     */
    public List<FileRef> listRecursive() {
        List<FileRef> out = new ArrayList<FileRef>();
        try {
            listRecursive(DocumentsContract.getTreeDocumentId(tree), out);
        } catch (Exception e) {
            // getTreeDocumentId 在某些 provider 上抛异常时退化为仅列顶层
            try { out.addAll(list()); } catch (Exception ignored) { }
        }
        return out;
    }

    private void listRecursive(String docId, List<FileRef> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, docId);
        Cursor c = ctx.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? -1 : c.getLong(2);
                String mime = c.getString(3);
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    listRecursive(id, out);
                } else {
                    out.add(new FileRef(name, uri, size));
                }
            }
        } finally { c.close(); }
    }

    /** 整棵树里按文件名（大小写不敏感）查找单个文件。 */
    public FileRef findRecursive(String name) {
        if (name == null || name.isEmpty()) return null;
        for (FileRef f : listRecursive()) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /** 定位 9008 刷机包的分区表：优先 rawprogram-restore.xml（本工具备份产物），其次任意 rawprogram*.xml。 */
    public FileRef findRawprogram() {
        List<FileRef> files = listRecursive();
        FileRef fallback = null;
        for (FileRef f : files) {
            String n = f.name.toLowerCase(Locale.US);
            if (n.equals("rawprogram-restore.xml")) return f;
            if (n.startsWith("rawprogram") && n.endsWith(".xml") && !n.contains("patch")) fallback = f;
        }
        return fallback;
    }

    /** 定位 Firehose 编程器（.mbn/.elf），优先文件名含 firehose / prog / edl 的。 */
    public FileRef findFirehose() {
        List<FileRef> files = listRecursive();
        FileRef priority = null, any = null;
        for (FileRef f : files) {
            String n = f.name.toLowerCase(Locale.US);
            if (!n.endsWith(".mbn") && !n.endsWith(".elf")) continue;
            if (any == null) any = f;
            if (n.contains("firehose") || n.contains("prog") || n.contains("edl")) priority = f;
        }
        return priority != null ? priority : any;
    }

    public FileRef findBoot(String controller) { return find(DeviceConfig.bootName(controller)); }
    public FileRef findConfig() { return find("config.txt"); }
    public FileRef findApk(String name) { return find(name); }

    /**
     * 选待刷模块包：跳过救砖类 zip，优先文件名含 4.5ui- 标记的（不区分大小写、不要求在开头），
     * 否则按名称排序取第一个。按名称排序保证同一目录多次执行结果一致。
     */
    public FileRef findModuleZip() {
        List<FileRef> candidates = new ArrayList<FileRef>();
        List<FileRef> prefixed = new ArrayList<FileRef>();
        for (FileRef f : list()) {
            String n = f.name.toLowerCase(Locale.US);
            if (!n.endsWith(".zip")) continue;
            if (n.contains("guardian") || n.contains("防砖") || n.contains("救砖")) continue;
            candidates.add(f);
            if (n.contains("4.5ui-")) prefixed.add(f);
        }
        if (!prefixed.isEmpty()) {
            sortByName(prefixed);
            return prefixed.get(0);
        }
        if (candidates.isEmpty()) return null;
        sortByName(candidates);
        return candidates.get(0);
    }

    private static void sortByName(List<FileRef> list) {
        java.util.Collections.sort(list, new java.util.Comparator<FileRef>() {
            @Override public int compare(FileRef a, FileRef b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    /**
     * 救砖守护模块：只认特征名（防砖/救砖/guardian），避免把待刷模块包误当救砖模块。
     * 模块必须放在资源目录里由用户自备，APK 不再内置。
     */
    public FileRef findGuardianZip() {
        for (FileRef f : list()) {
            String n = f.name.toLowerCase(Locale.US);
            if (!n.endsWith(".zip")) continue;
            if (n.contains("guardian") || n.contains("防砖") || n.contains("救砖")) return f;
        }
        return null;
    }

    public InputStream open(FileRef f) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(f.uri);
        if (in == null) throw new IllegalStateException("无法打开 " + f.name);
        return in;
    }

    public String readText(FileRef f) throws Exception {
        InputStream in = open(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    /** SAF 个别 provider 不给 COLUMN_SIZE；此时流式统计，避免 fastboot download 长度错误。 */
    public long sizeOf(FileRef f) throws Exception {
        if (f.size > 0) return f.size;
        InputStream in = open(f);
        try {
            long n = 0;
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
            return n;
        } finally { in.close(); }
    }

    public String md5(FileRef f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        InputStream in = open(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally { in.close(); }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    public String describe() { return tree.toString(); }
}
