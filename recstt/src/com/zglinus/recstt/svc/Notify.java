package com.zglinus.recstt.svc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.util.Log;

import com.zglinus.recstt.R;

/**
 * 通知渠道与通知构造的集中处（纯 framework，无 AndroidX）。
 *
 * <p>两个渠道：
 * <ul>
 *   <li>{@link #CHANNEL_PROGRESS}（"stt"）：前台服务的进度条，IMPORTANCE_LOW —— 常驻、不响铃不震动，符合「低打扰」；</li>
 *   <li>{@link #CHANNEL_RESULT}（"stt_result"）：转写完成 / 失败，IMPORTANCE_DEFAULT —— 失败要被看见，
 *       不然「不许静默失败」只落在数据库里，用户不点开 App 根本不知道。</li>
 * </ul>
 */
public final class Notify {

  /** 前台服务的渠道，名字就是契约里的 "stt"。 */
  public static final String CHANNEL_PROGRESS = "stt";
  /** 结果通知渠道（完成/失败）。 */
  public static final String CHANNEL_RESULT = "stt_result";
  /** 前台服务通知 id。 */
  public static final int ID_SERVICE = 0x5301;

  private static final String TAG = "Notify";
  private static final String APP_NAME = "录音转写";
  /** 用类名字符串指向界面，避免 svc 层硬依赖 ui 层的编译顺序（同包互相引用会拖住单文件编译）。 */
  private static final String ACTIVITY_READER = "com.zglinus.recstt.ui.ReaderActivity";

  private Notify() {
  }

  /** 幂等建渠道；Service.onCreate 和每次发通知前都可以调。 */
  public static void ensureChannels(Context c) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) {
      return;
    }
    try {
      NotificationChannel progress =
          new NotificationChannel(CHANNEL_PROGRESS, "转写进度", NotificationManager.IMPORTANCE_LOW);
      progress.setDescription("前台转写服务的常驻进度，不响铃不震动");
      progress.setSound(null, null);
      progress.enableVibration(false);
      progress.setShowBadge(false);
      progress.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
      nm.createNotificationChannel(progress);

      NotificationChannel result =
          new NotificationChannel(CHANNEL_RESULT, "转写结果", NotificationManager.IMPORTANCE_DEFAULT);
      result.setDescription("转写完成或失败的结果通知");
      result.setShowBadge(true);
      nm.createNotificationChannel(result);
    } catch (Throwable t) {
      Log.w(TAG, "建通知渠道失败", t);
    }
  }

  /** 前台服务的进度通知（带「取消」按钮、点开跳到阅读界面）。 */
  public static Notification progress(Context c, long recId, String title, String text,
                                     int pct, boolean indeterminate) {
    Notification.Builder b = new Notification.Builder(c, CHANNEL_PROGRESS);
    b.setSmallIcon(R.drawable.ic_notify);
    b.setContentTitle(nz(title, APP_NAME));
    b.setContentText(nz(text, ""));
    b.setSubText(APP_NAME);
    b.setOngoing(true);
    b.setAutoCancel(false);
    b.setOnlyAlertOnce(true);
    b.setShowWhen(false);
    b.setCategory(Notification.CATEGORY_PROGRESS);
    b.setVisibility(Notification.VISIBILITY_PRIVATE);
    if (pct >= 0 && !indeterminate) {
      b.setProgress(100, clampPct(pct), false);
    } else {
      b.setProgress(0, 0, true);
    }
    if (recId > 0) {
      b.setContentIntent(openReader(c, recId));
      try {
        Icon icon = Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel);
        b.addAction(new Notification.Action.Builder(icon, "取消", cancelAction(c, recId)).build());
      } catch (Throwable t) {
        Log.w(TAG, "加取消按钮失败", t);
      }
    }
    return b.build();
  }

  /**
   * 完成 / 失败通知。id 按 recId 派生，同一条录音重跑会覆盖上一条，不会堆一屏幕。
   *
   * @param ok true=完成，false=失败
   */
  public static void result(Context c, long recId, boolean ok, String name, String detail) {
    Notification.Builder b = new Notification.Builder(c, CHANNEL_RESULT);
    b.setSmallIcon(R.drawable.ic_notify);
    b.setContentTitle(ok ? "转写完成" : "转写失败");
    b.setContentText(ok ? nz(name, "录音") : nz(detail, "原因未知"));
    if (ok) {
      b.setStyle(new Notification.BigTextStyle().bigText(nz(name, "录音") + "\n" + nz(detail, "")));
    } else {
      b.setStyle(new Notification.BigTextStyle().bigText(nz(name, "录音") + "\n" + nz(detail, "原因未知")));
    }
    b.setAutoCancel(true);
    b.setOnlyAlertOnce(false);
    b.setShowWhen(true);
    b.setWhen(System.currentTimeMillis());
    b.setCategory(ok ? Notification.CATEGORY_STATUS : Notification.CATEGORY_ERROR);
    b.setVisibility(Notification.VISIBILITY_PRIVATE);
    if (recId > 0) {
      b.setContentIntent(openReader(c, recId));
    }
    // 失败时补一个「再试一次」的入口：直接跳阅读界面，UI 里本来就有重试
    show(c, idFor(recId), b.build());
  }

  public static void show(Context c, int id, Notification n) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null || n == null) {
      return;
    }
    try {
      nm.notify(id, n);
    } catch (Throwable t) {
      Log.w(TAG, "发通知失败 id=" + id, t);
    }
  }

  public static void cancel(Context c, int id) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm == null) {
      return;
    }
    try {
      nm.cancel(id);
    } catch (Throwable t) {
      Log.w(TAG, "撤通知失败 id=" + id, t);
    }
  }

  /** 结果通知 id：用 recId 低 30 位，同一条录音只有一条结果通知。 */
  public static int idFor(long recId) {
    return 0x40000000 | (int) (recId & 0x3FFFFFFFL);
  }

  /** 点通知 → 阅读界面（带 rec_id）。 */
  public static PendingIntent openReader(Context c, long recId) {
    Intent i = new Intent();
    i.setClassName(c, ACTIVITY_READER);
    i.putExtra("rec_id", recId);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getActivity(c, (int) (recId & 0x3FFFFFFFL), i, flags);
  }

  /** 进度通知上的「取消」→ 发一条 ACTION_CANCEL 给 Service（不额外注册广播接收器）。 */
  public static PendingIntent cancelAction(Context c, long recId) {
    Intent i = new Intent(c, TranscribeService.class);
    i.setAction(TranscribeService.ACTION_CANCEL);
    i.putExtra(TranscribeService.EXTRA_REC, recId);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getService(c, (int) (recId & 0x3FFFFFFFL), i, flags);
  }

  private static int clampPct(int pct) {
    if (pct < 0) {
      return 0;
    }
    return pct > 100 ? 100 : pct;
  }

  private static String nz(String s, String def) {
    return s == null || s.trim().isEmpty() ? def : s.trim();
  }
}
