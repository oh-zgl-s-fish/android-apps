package com.zglinus.recstt.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.scan.Scanner;
import com.zglinus.recstt.store.Db;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描本机录音 + 存储权限。从 MainActivity 抽出的功能模块。
 *
 * <p>界线：只负责「要不要扫描、扫到哪儿了、回来怎么办」，不碰列表/筛选/菜单。
 * 扫描状态变化通过 {@link Ui} 回调给调用方刷新界面。
 */
public class ScanFlow {

    /** 扫描过程中的界面回调，全部在主线程。 */
    public interface Ui {
        /** 扫描状态变了：是否在扫、已找到多少、当前文件名。 */
        void onScanProgress(boolean scanning, int found, String path);

        /** 扫描结束；count < 0 表示失败，error 是人话原因。 */
        void onScanDone(int count, String error);

        /** 提示一句（Toast）。 */
        void toast(String msg);

        /** 该刷新列表了。 */
        void reload();
    }

    public interface PermissionRequester {
        void requestPermissions(String[] perms, int code);
    }

    private final Activity act;
    private final Db db;
    private final TextView fabLabel;
    private final Ui ui;
    private final PermissionRequester perm;

    private volatile boolean scanning;
    private volatile int found;
    private volatile String path = "";

    public ScanFlow(Activity act, Db db, TextView fabLabel, Ui ui, PermissionRequester perm) {
        this.act = act;
        this.db = db;
        this.fabLabel = fabLabel;
        this.ui = ui;
        this.perm = perm;
    }

    public boolean isScanning() {
        return scanning;
    }

    public int found() {
        return found;
    }

    public String currentPath() {
        return path;
    }

    /** 右下角按钮：没权限先要权限，有权限就开扫。 */
    public void onFabClick() {
        if (scanning) {
            return;
        }
        if (!hasAllFiles()) {
            openAllFilesSettings();
            return;
        }
        start();
    }

    public void start() {
        if (scanning) {
            return;
        }
        final List<String> roots = roots();
        final List<Tpl> tpls = db.templates();

        scanning = true;
        found = 0;
        path = "";
        if (fabLabel != null) {
            fabLabel.setText("扫描中…");
        }
        ui.onScanProgress(true, 0, "");

        new Thread(new Runnable() {
            @Override
            public void run() {
                int count = -1;
                String error = null;
                try {
                    count = Scanner.scan(act, roots, tpls, new Scanner.Progress() {
                        @Override
                        public void onProgress(final int n, final String p) {
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (act.isFinishing()) {
                                        return;
                                    }
                                    found = n;
                                    path = fileNameOf(p);
                                    ui.onScanProgress(true, found, path);
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : "：" + t.getMessage());
                }
                final int result = count;
                final String err = error;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (act.isFinishing()) {
                            return;
                        }
                        if (fabLabel != null) {
                            fabLabel.setText("扫描本机");
                        }
                        ui.onScanDone(result, err);
                        ui.reload();
                        if (result >= 0) {
                            ui.toast("扫描完成：这次入库 " + result + " 条（老记录只更新元数据）");
                        } else {
                            ui.toast("扫描失败：" + err);
                        }
                    }
                });
            }
        }, "recstt-scan").start();
    }

    /** 扫描目录：优先用设置里存的 roots（换行分隔），没设就走 Scanner 的默认目录。 */
    public List<String> roots() {
        List<String> out = new ArrayList<String>();
        String raw = db.kv("roots", "");
        if (raw != null) {
            String[] lines = raw.split("\\r?\\n");
            for (String line : lines) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out.isEmpty() ? Scanner.defaultRoots() : out;
    }

    public boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        return act.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 没权限就去要：30+ 直接跳「所有文件访问权限」页，低了才退回运行时权限。 */
    public void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            perm.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 41);
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        i.setData(Uri.parse("package:" + act.getPackageName()));
        try {
            act.startActivity(i);
            return;
        } catch (Exception e) {
            // 有些 ROM 把这个页面摘了，退到应用详情页（那里也能进权限）
        }
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + act.getPackageName())));
        } catch (Exception e) {
            ui.toast("打不开系统设置，请手动授予「所有文件访问权限」");
        }
    }

    /** 权限结果回来：给了就接着扫，没给就说清为什么扫不了。 */
    public void onPermissionResult(int requestCode, String[] permissions, int[] grants) {
        if (requestCode != 41) {
            return;
        }
        boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (ok) {
            start();
        } else {
            ui.toast("没有存储读取权限，扫不了本机录音");
        }
    }

    private static String fileNameOf(String p) {
        if (p == null) {
            return "";
        }
        int i = p.lastIndexOf('/');
        return i >= 0 && i + 1 < p.length() ? p.substring(i + 1) : p;
    }
}
