package com.zglinus.recstt.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.svc.TranscribeService;

/**
 * 记录上的动作：点开阅读页、长按菜单（转写/删除）。从 MainActivity 抽出的功能模块。
 */
public class RecActions {

    public interface Ui {
        /** 需要刷新列表。 */
        void reload();
    }

    private static final int MENU_TRANSCRIBE = 11;
    private static final int MENU_DELETE = 12;

    private final Activity act;
    private final Db db;
    private final Handler handler;
    private final Ui ui;

    public RecActions(Activity act, Db db, Handler handler, Ui ui) {
        this.act = act;
        this.db = db;
        this.handler = handler;
        this.ui = ui;
    }

    /** 点一条：进「边听边读」。 */
    public void onClick(Rec rec) {
        if (rec == null) {
            return;
        }
        Intent i = new Intent(act, ReaderActivity.class);
        i.putExtra("rec_id", rec.id);
        act.startActivity(i);
    }

    /** 长按一条：转写 / 删除。 */
    public void onLongClick(final Rec rec, View row) {
        if (rec == null) {
            return;
        }
        PopupMenu p = new PopupMenu(act, row);
        p.getMenu().add(0, MENU_TRANSCRIBE, 0, "转写");
        p.getMenu().add(0, MENU_DELETE, 1, "删除记录");
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_TRANSCRIBE:
                        startTranscribe(rec);
                        return true;
                    case MENU_DELETE:
                        confirmDelete(rec);
                        return true;
                    default:
                        return false;
                }
            }
        });
        p.show();
    }

    public void startTranscribe(Rec rec) {
        if (RecList.isRunning(rec.state)) {
            Toast.makeText(act, "这条已经在转写了", Toast.LENGTH_SHORT).show();
            return;
        }
        TranscribeService.start(act, rec.id);
        Toast.makeText(act, "已交给转写服务", Toast.LENGTH_SHORT).show();
        // 服务写库是异步的，稍等一下再拉一次，别让行还显示「未转写」
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!act.isFinishing()) {
                    ui.reload();
                }
            }
        }, 800L);
    }

    public void confirmDelete(final Rec rec) {
        new AlertDialog.Builder(act)
                .setTitle("删除记录")
                .setMessage("只删库里的这一条（含转写文本），录音文件不动。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (RecList.isRunning(rec.state)) {
                            TranscribeService.cancel(act, rec.id);
                        }
                        db.delete(rec.id);
                        ui.reload();
                        Toast.makeText(act, "已删除", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
