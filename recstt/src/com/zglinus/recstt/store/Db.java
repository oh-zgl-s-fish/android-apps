package com.zglinus.recstt.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.model.Seg;
import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.tpl.TplEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 存储（纯 framework，android.database.sqlite）。
 *
 * <p>四张表：rec / seg / kv / tpl。单例通过 {@link #get(Context)} 拿。
 * 重扫时 {@link #upsert(Rec)} 只刷新元数据，不会把已有的转写状态/文本清掉。
 */
public final class Db extends SQLiteOpenHelper {

    public static final String DB_NAME = "recstt.db";
    public static final int DB_VERSION = 1;

    private static volatile Db INSTANCE;

    /** 契约里的常用键默认值（kv 里没存时用）。 */
    private static final Map<String, String> KV_DEFAULTS = new HashMap<String, String>();

    static {
        KV_DEFAULTS.put("server_url", "http://192.0.2.10:54087");
        KV_DEFAULTS.put("server_url_wg", "http://198.51.100.10:54087");
        KV_DEFAULTS.put("lang", "zh");
        KV_DEFAULTS.put("bg_blur", "24");
        KV_DEFAULTS.put("bg_scrim", "45");
        KV_DEFAULTS.put("bg_enabled", "1");
    }

    public static Db get(Context c) {
        if (INSTANCE == null) {
            synchronized (Db.class) {
                if (INSTANCE == null) {
                    Context app = c == null ? null : c.getApplicationContext();
                    INSTANCE = new Db(app != null ? app : c);
                }
            }
        }
        return INSTANCE;
    }

    public Db(Context c) {
        super(c, DB_NAME, null, DB_VERSION);
    }

    // ---------------------------------------------------------------- schema

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rec ("
                + "id INTEGER PRIMARY KEY,"
                + "path TEXT UNIQUE,"
                + "file_name TEXT,"
                + "display_name TEXT,"
                + "phone TEXT,"
                + "phone_kind TEXT,"
                + "start_ms INTEGER NOT NULL DEFAULT 0,"
                + "time_source TEXT,"
                + "dur_ms INTEGER NOT NULL DEFAULT -1,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "mtime INTEGER NOT NULL DEFAULT 0,"
                + "template_id TEXT,"
                + "job_id TEXT,"
                + "error TEXT,"
                + "state INTEGER NOT NULL DEFAULT 0,"
                + "progress INTEGER NOT NULL DEFAULT 0,"
                + "text TEXT,"
                + "updated_at INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_start ON rec(start_ms)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_state ON rec(state)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_phone ON rec(phone)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_name ON rec(display_name)");

        db.execSQL("CREATE TABLE IF NOT EXISTS seg ("
                + "rec_id INTEGER NOT NULL,"
                + "idx INTEGER NOT NULL,"
                + "start_ms INTEGER NOT NULL DEFAULT 0,"
                + "end_ms INTEGER NOT NULL DEFAULT 0,"
                + "text TEXT,"
                + "PRIMARY KEY(rec_id, idx))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_seg_rec ON seg(rec_id)");

        db.execSQL("CREATE TABLE IF NOT EXISTS kv ("
                + "k TEXT PRIMARY KEY,"
                + "v TEXT)");

        db.execSQL("CREATE TABLE IF NOT EXISTS tpl ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT,"
                + "format TEXT,"
                + "pattern TEXT,"
                + "time_fmt TEXT,"
                + "scope TEXT,"
                + "builtin INTEGER NOT NULL DEFAULT 0,"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "priority INTEGER NOT NULL DEFAULT 50)");

        seedBuiltinTemplates(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 目前只有 v1；日后加字段时在这里写可回退的 ALTER，别 drop 掉用户的记录。
        if (oldVersion < 1) {
            onCreate(db);
        }
    }

    private void seedBuiltinTemplates(SQLiteDatabase db) {
        for (Tpl t : TplEngine.builtin()) {
            db.insertWithOnConflict("tpl", null, tplValues(t), SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    // ---------------------------------------------------------------- rec

    /** 有则更新元数据（保留转写状态/文本/job），无则插入。 */
    public void upsert(Rec r) {
        if (r == null || r.path == null || r.path.isEmpty()) {
            return;
        }
        if (r.id == 0) {
            r.id = TplEngine.hashPath(r.path);
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues meta = recValues(r);

        ContentValues upd = new ContentValues(meta);
        if (r.startMs <= 0) {
            upd.remove("start_ms"); // 别把已解析出的好时间覆盖成 0
        }
        if (r.durMs < 0) {
            upd.remove("dur_ms"); // 读不出时长时保留旧值
        }
        int n = db.update("rec", upd, "id=?", new String[]{String.valueOf(r.id)});
        if (n > 0) {
            return;
        }

        ContentValues ins = new ContentValues(meta);
        ins.put("id", r.id);
        ins.put("state", r.state);
        ins.put("progress", clampProgress(r.progress));
        ins.put("job_id", r.jobId);
        ins.put("error", r.error);
        ins.put("updated_at", System.currentTimeMillis());
        long row = db.insertWithOnConflict("rec", null, ins, SQLiteDatabase.CONFLICT_IGNORE);
        if (row < 0) {
            // id 变了但 path 撞上老行：按 path 更新，避免同一文件留两条
            ContentValues upd2 = new ContentValues(upd);
            db.update("rec", upd2, "path=?", new String[]{r.path});
        }
    }

    public Rec byId(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM rec WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readRec(c) : null;
        } finally {
            c.close();
        }
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("seg", "rec_id=?", new String[]{String.valueOf(id)});
            db.delete("rec", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 列表：q 模糊搜姓名/号码/文件名/路径；stateFilter -1=全部；
     * 按 start_ms 倒序，未知时间（start_ms<=0）的排后面；limit<=0 表示不限。
     */
    public List<Rec> list(String q, int stateFilter, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM rec");
        List<String> args = new ArrayList<String>();
        List<String> where = new ArrayList<String>();

        if (stateFilter >= 0) {
            where.add("state=?");
            args.add(String.valueOf(stateFilter));
        }
        String kw = q == null ? "" : q.trim();
        if (!kw.isEmpty()) {
            String like = "%" + escapeLike(kw) + "%";
            where.add("(display_name LIKE ? ESCAPE '\\'"
                    + " OR phone LIKE ? ESCAPE '\\'"
                    + " OR file_name LIKE ? ESCAPE '\\'"
                    + " OR path LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < where.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append(where.get(i));
            }
        }
        sql.append(" ORDER BY CASE WHEN start_ms>0 THEN 0 ELSE 1 END, start_ms DESC, mtime DESC, id DESC");
        if (limit > 0) {
            sql.append(" LIMIT ?");
            args.add(String.valueOf(limit));
        }

        List<Rec> out = new ArrayList<Rec>();
        Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                out.add(readRec(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void setState(long id, int state, int progress, String error) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        v.put("progress", clampProgress(progress));
        v.put("error", error);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("rec", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void setJob(long id, String jobId) {
        ContentValues v = new ContentValues();
        v.put("job_id", jobId);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("rec", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** 全文转写落地：存 text 并置 state=3/progress=100/error=null。 */
    public void setSegsDone(long id, String text) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("state", 3);
        v.put("progress", 100);
        v.put("error", (String) null);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("rec", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** 整批替换某条录音的句子。 */
    public void saveSegs(long recId, List<Seg> segs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("seg", "rec_id=?", new String[]{String.valueOf(recId)});
            if (segs != null) {
                for (Seg s : segs) {
                    if (s == null) {
                        continue;
                    }
                    ContentValues v = new ContentValues();
                    v.put("rec_id", recId);
                    v.put("idx", s.idx);
                    v.put("start_ms", s.startMs);
                    v.put("end_ms", s.endMs);
                    v.put("text", s.text);
                    db.insertWithOnConflict("seg", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Seg> segs(long recId) {
        List<Seg> out = new ArrayList<Seg>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT rec_id, idx, start_ms, end_ms, text FROM seg WHERE rec_id=? ORDER BY idx ASC",
                new String[]{String.valueOf(recId)});
        try {
            while (c.moveToNext()) {
                Seg s = new Seg();
                s.recId = c.getLong(0);
                s.idx = c.getInt(1);
                s.startMs = c.getInt(2);
                s.endMs = c.getInt(3);
                s.text = c.isNull(4) ? null : c.getString(4);
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ---------------------------------------------------------------- kv

    public String kv(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT v FROM kv WHERE k=?", new String[]{k});
        try {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } finally {
            c.close();
        }
        if (def != null) {
            return def;
        }
        return KV_DEFAULTS.get(k);
    }

    public void kvPut(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------------------------- tpl

    public List<Tpl> templates() {
        List<Tpl> out = new ArrayList<Tpl>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM tpl ORDER BY priority ASC, id ASC", null);
        try {
            while (c.moveToNext()) {
                out.add(readTpl(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Tpl tpl(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM tpl WHERE id=?", new String[]{id});
        try {
            return c.moveToFirst() ? readTpl(c) : null;
        } finally {
            c.close();
        }
    }

    public void saveTpl(Tpl t) {
        if (t == null || t.id == null || t.id.isEmpty()) {
            return;
        }
        getWritableDatabase().insertWithOnConflict("tpl", null, tplValues(t),
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteTpl(String id) {
        getWritableDatabase().delete("tpl", "id=?", new String[]{id});
    }

    // ---------------------------------------------------------------- 行映射

    private static ContentValues recValues(Rec r) {
        ContentValues v = new ContentValues();
        v.put("path", r.path);
        v.put("file_name", r.fileName);
        v.put("display_name", r.displayName);
        v.put("phone", r.phone);
        v.put("phone_kind", r.phoneKind);
        v.put("start_ms", r.startMs);
        v.put("time_source", r.timeSource);
        v.put("dur_ms", r.durMs);
        v.put("size", r.size);
        v.put("mtime", r.mtime);
        v.put("template_id", r.templateId);
        v.put("updated_at", System.currentTimeMillis());
        return v;
    }

    private static ContentValues tplValues(Tpl t) {
        ContentValues v = new ContentValues();
        v.put("id", t.id);
        v.put("name", t.name);
        v.put("format", t.format);
        v.put("pattern", t.pattern);
        v.put("time_fmt", t.timeFmt);
        v.put("scope", t.scope);
        v.put("builtin", t.builtin ? 1 : 0);
        v.put("enabled", t.enabled ? 1 : 0);
        v.put("priority", t.priority);
        return v;
    }

    private static Rec readRec(Cursor c) {
        Rec r = new Rec();
        r.id = lng(c, "id", 0L);
        r.path = str(c, "path");
        r.fileName = str(c, "file_name");
        r.displayName = str(c, "display_name");
        r.phone = str(c, "phone");
        r.phoneKind = str(c, "phone_kind");
        r.startMs = lng(c, "start_ms", 0L);
        r.timeSource = str(c, "time_source");
        r.durMs = lng(c, "dur_ms", -1L);
        r.size = lng(c, "size", 0L);
        r.mtime = lng(c, "mtime", 0L);
        r.templateId = str(c, "template_id");
        r.jobId = str(c, "job_id");
        r.error = str(c, "error");
        r.state = integer(c, "state", 0);
        r.progress = integer(c, "progress", 0);
        return r;
    }

    private static Tpl readTpl(Cursor c) {
        Tpl t = new Tpl();
        t.id = str(c, "id");
        t.name = str(c, "name");
        t.format = str(c, "format");
        t.pattern = str(c, "pattern");
        t.timeFmt = str(c, "time_fmt");
        t.scope = str(c, "scope");
        t.builtin = integer(c, "builtin", 0) != 0;
        t.enabled = integer(c, "enabled", 1) != 0;
        t.priority = integer(c, "priority", 50);
        return t;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? def : c.getLong(i);
    }

    private static int integer(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? def : c.getInt(i);
    }

    // ---------------------------------------------------------------- 杂项

    private static int clampProgress(int p) {
        return p < 0 ? 0 : (p > 100 ? 100 : p);
    }

    /** LIKE 里 % 和 _ 要转义，否则用户搜 "1_2" 会变成通配。 */
    private static String escapeLike(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
