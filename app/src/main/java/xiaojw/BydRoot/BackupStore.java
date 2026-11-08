package xiaojw.BydRoot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SAF 目录树写入层：备份产物全部落在用户选择的资源目录下 backup-&lt;时间戳&gt;/ 子目录。
 * 只依赖 framework DocumentsContract，不引第三方库。
 */
public final class BackupStore {

    public static final class Entry {
        public final String name;
        public final Uri uri;
        public final boolean directory;
        public final long size;
        Entry(String name, Uri uri, boolean directory, long size) {
            this.name = name;
            this.uri = uri;
            this.directory = directory;
            this.size = size;
        }
    }

    private final Context ctx;
    private final Uri tree;

    public BackupStore(Context ctx, Uri tree) {
        this.ctx = ctx.getApplicationContext();
        this.tree = tree;
    }

    public Uri treeUri() { return tree; }

    /** 在指定父目录下创建文件/子目录；已存在同名时直接返回它。 */
    public Uri create(Uri parent, String name, String mime) throws Exception {
        Entry old = findChild(parent, name);
        if (old != null) return old.uri;
        Uri created = DocumentsContract.createDocument(ctx.getContentResolver(), parent, mime, name);
        if (created == null) throw new IllegalStateException("无法创建 " + name + "（目录是否有写权限？）");
        return created;
    }

    public Uri createDir(Uri parent, String name) throws Exception {
        return create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR);
    }

    public Uri createFile(Uri parent, String name) throws Exception {
        return create(parent, name, "application/octet-stream");
    }

    public OutputStream openWrite(Uri doc) throws Exception {
        OutputStream out = ctx.getContentResolver().openOutputStream(doc, "w");
        if (out == null) throw new IllegalStateException("无法写入 " + doc);
        return out;
    }

    public InputStream openRead(Uri doc) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(doc);
        if (in == null) throw new IllegalStateException("无法读取 " + doc);
        return in;
    }

    public String readText(Uri doc) throws Exception {
        InputStream in = openRead(doc);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally { in.close(); }
    }

    /** SAF provider 不给 COLUMN_SIZE 时流式统计，避免回刷时 download 长度算错。 */
    public long sizeOf(Entry e) throws Exception {
        if (e.size > 0) return e.size;
        InputStream in = openRead(e.uri);
        try {
            long n = 0;
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
            return n;
        } finally { in.close(); }
    }

    public Uri writeText(Uri parent, String name, String text) throws Exception {
        // 用 octet-stream，避免部分 SAF provider 按 text/plain 自动追加 .txt 后缀
        Uri doc = create(parent, name, "application/octet-stream");
        OutputStream out = openWrite(doc);
        try { out.write(text.getBytes("UTF-8")); out.flush(); }
        finally { out.close(); }
        return doc;
    }

    public List<Entry> list(Uri parent) {
        List<Entry> out = new ArrayList<Entry>();
        ContentResolver cr = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(parent));
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
                out.add(new Entry(name,
                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), size));
            }
        } finally { c.close(); }
        return out;
    }

    public Entry findChild(Uri parent, String name) {
        for (Entry e : list(parent)) if (name.equalsIgnoreCase(e.name)) return e;
        return null;
    }

    /** 根目录（tree 自身）对应的 document Uri。 */
    public Uri rootDocument() {
        return DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
    }

    public String describe() { return tree.toString(); }
}
