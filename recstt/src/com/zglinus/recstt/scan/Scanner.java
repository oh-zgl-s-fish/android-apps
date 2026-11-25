package com.zglinus.recstt.scan;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.util.Log;

import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.tpl.TplEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本机录音扫描：递归找音频文件 → TplEngine 提元数据 → Db.upsert。
 *
 * <p>限制：目录深度 3、只认 9 种扩展名、小于 10KB 的直接跳过（空录音/占位文件）。
 */
public final class Scanner {

    private static final String TAG = "recstt/scan";

    public interface Progress {
        void onProgress(int found, String path);
    }

    private static final String[] EXT = {
            "m4a", "mp3", "wav", "amr", "ogg", "opus", "aac", "flac",
    };

    private static final int MAX_DEPTH = 3;
    private static final long MIN_SIZE = 10L * 1024L;

    private Scanner() {
    }

    /** 常见录音目录（相对外部存储根）。 */
    public static List<String> defaultRoots() {
        String[] rel = {
                "MIUI/sound_recorder",
                "Recordings",
                "CallRecord",
                "Record",
                "Music/Recorder",
                "Download/Recordings",
        };
        File base = Environment.getExternalStorageDirectory();
        String root = base == null ? "/storage/emulated/0" : base.getAbsolutePath();
        List<String> out = new ArrayList<String>(rel.length);
        for (String r : rel) {
            out.add(root + "/" + r);
        }
        return out;
    }

    /** MediaMetadataRetriever 读时长；失败返回 -1，不抛异常。 */
    public static long durationMs(String path) {
        if (path == null || path.isEmpty()) {
            return -1L;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (s == null) {
                return -1L;
            }
            long d = Long.parseLong(s.trim());
            return d > 0 ? d : -1L;
        } catch (Exception e) {
            return -1L;
        } finally {
            try {
                r.release();
            } catch (Exception ignore) {
                // release 失败没什么可做的
            }
        }
    }

    /**
     * 扫描并入库。
     *
     * @param tpls 模板列表；null 表示用库里的模板。只使用 enabled=true 的模板，
     *             全被关掉时就直接走 TplEngine 的兜底（不自作主张复活内置模板）。
     * @return 入库条数
     */
    public static int scan(Context c, List<String> roots, List<Tpl> tpls, Progress p) {
        if (c == null) {
            return 0;
        }
        Db db = Db.get(c);

        List<Tpl> active = new ArrayList<Tpl>();
        List<Tpl> src = tpls != null ? tpls : db.templates();
        if (src != null) {
            for (Tpl t : src) {
                if (t != null && t.enabled) {
                    active.add(t);
                }
            }
        }

        List<String> dirs = (roots == null || roots.isEmpty()) ? defaultRoots() : roots;
        Set<String> seen = new HashSet<String>();
        int[] counter = new int[]{0};
        for (String d : dirs) {
            if (d == null || d.trim().isEmpty()) {
                continue;
            }
            File f = new File(d);
            if (!f.isDirectory() || !f.canRead()) {
                continue;
            }
            walk(db, f, 1, active, seen, counter, p);
        }
        return counter[0];
    }

    private static void walk(Db db, File dir, int depth, List<Tpl> tpls,
                             Set<String> seen, int[] counter, Progress p) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            String name = f.getName();
            if (name.startsWith(".")) {
                continue; // 隐藏文件/目录
            }
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH) {
                    walk(db, f, depth + 1, tpls, seen, counter, p);
                }
                continue;
            }
            if (!f.isFile() || f.length() < MIN_SIZE || !isAudio(name)) {
                continue;
            }
            String path;
            try {
                path = f.getCanonicalPath();
            } catch (Exception e) {
                path = f.getAbsolutePath();
            }
            if (!seen.add(path)) {
                continue; // 多个根目录互相包含时别重复数
            }
            try {
                Rec r = build(f, path, tpls);
                db.upsert(r);
                counter[0]++;
                if (p != null) {
                    p.onProgress(counter[0], path);
                }
            } catch (Exception e) {
                // 单个文件坏掉不拖垮整次扫描，但留 logcat 痕迹
                Log.w(TAG, "跳过 " + path + "：" + e);
            }
        }
    }

    private static Rec build(File f, String path, List<Tpl> tpls) {
        Rec r = new Rec();
        r.id = TplEngine.hashPath(path);
        r.path = path;
        r.fileName = f.getName();
        r.size = f.length();
        r.mtime = f.lastModified();
        r.durMs = durationMs(path);

        TplEngine.Hit h = TplEngine.parse(r.fileName, tpls);
        r.phone = h.phone;
        r.phoneKind = h.phoneKind;
        r.templateId = h.tplId;
        r.startMs = h.startMs;
        r.timeSource = h.timeSource;

        if (r.startMs <= 0) {
            // TplEngine 拿不到 mtime，这里补
            if (r.mtime > 0) {
                r.startMs = r.mtime;
                r.timeSource = "mtime";
            } else {
                r.timeSource = "none";
            }
        }

        if (h.name != null && !h.name.isEmpty()) {
            r.displayName = h.name;
        } else if (h.phone != null && !h.phone.isEmpty()) {
            r.displayName = h.phone;
        } else {
            r.displayName = stripExt(r.fileName);
        }
        return r;
    }

    private static boolean isAudio(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        for (String e : EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
