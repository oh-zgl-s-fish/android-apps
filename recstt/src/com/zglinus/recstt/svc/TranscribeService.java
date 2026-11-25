package com.zglinus.recstt.svc;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.model.Seg;
import com.zglinus.recstt.net.Endpoints;
import com.zglinus.recstt.net.SttClient;
import com.zglinus.recstt.wg.Tunnel;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.ui.Diag;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 转写前台服务：submit → 每 1.5s 轮询 → 存分段。
 *
 * <p>几条硬规矩：
 * <ul>
 *   <li>任何异常都写 Db（state=4 + 一句人话原因）并发一条失败通知，绝不静默；</li>
 *   <li>进程被杀不会丢任务：state=1/2 且 Db 里有 jobId 时，再调 {@link #start} 会**续轮询**而不是重新上传；</li>
 *   <li>上传/轮询/取结果全在单线程队列里跑，主线程只负责 startForeground，不碰网络也不碰数据库；</li>
 *   <li>取消两条路：内存里在跑 → 置标记（上传中立刻断，轮询最多 1.5s 后断）+ 通知服务端 DELETE；
 *       内存里没有（进程重启过）→ 按 Db 里的 jobId 兜底删一次。</li>
 * </ul>
 */
public final class TranscribeService extends Service {

  /** 开始转写某条录音；已跑/已排队则忽略，state=1/2 且带 jobId 则续轮询。 */
  public static final String ACTION_START = "com.zglinus.recstt.action.START";
  /** 取消某条录音的转写。 */
  public static final String ACTION_CANCEL = "com.zglinus.recstt.action.CANCEL";
  /** Intent extra：录音 id（Rec.id）。 */
  public static final String EXTRA_REC = "rec_id";

  private static final String TAG = "TranscribeService";

  /** Rec.state 的取值，跟契约对齐。 */
  private static final int ST_NONE = 0;
  private static final int ST_QUEUED = 1;
  private static final int ST_RUNNING = 2;
  /** 上传中：以前复用 ST_QUEUED，导致列表一直显示"排队"、progress 恒 0，看着像卡死。 */
  private static final int ST_UPLOADING = 5;
  private static final int ST_DONE = 3;
  private static final int ST_FAILED = 4;

  /** 轮询间隔。 */
  private static final long POLL_MS = 1500L;
  /** 单个任务最长等待（服务端卡死不能被无限等下去）。 */
  private static final long MAX_WAIT_MS = 3L * 60L * 60L * 1000L;
  /** 轮询请求的读超时：状态接口应当秒回，45 秒没动静就该报「等待超时」。 */
  private static final int POLL_READ_TIMEOUT_MS = 45 * 1000;
  /** Db 没给 server_url 时的兜底（契约里写明的默认值）。 */
  private static final String DEFAULT_SERVER = "http://192.0.2.10:54087";
  /** 单次 WakeLock 时长，轮询里会续。 */
  private static final long WAKE_MS = 30L * 60L * 1000L;

  /** 内存表：recId → 正在跑的任务。UI 用 liveProgress 查进度。 */
  private static final Map<Long, Job> JOBS = new ConcurrentHashMap<>();
  /** 内存表：recId → 0..100 进度；没有条目 = 没在跑。 */
  private static final Map<Long, Integer> LIVE = new ConcurrentHashMap<>();

  private ExecutorService exec;
  private PowerManager.WakeLock wake;
  private final AtomicInteger pending = new AtomicInteger();
  private volatile int lastStartId;
  private volatile boolean destroyed;

  // ------------------------------------------------------------------ 对外静态 API

  /** 开始转写。调用方（界面）应在自己的前台调用，否则系统可能拒起前台服务。 */
  public static void start(Context c, long recId) {
    if (c == null || recId <= 0) {
      return;
    }
    Intent i = new Intent(c, TranscribeService.class);
    i.setAction(ACTION_START);
    i.putExtra(EXTRA_REC, recId);
    try {
      c.startForegroundService(i);
    } catch (Throwable t) {
      Log.w(TAG, "startForegroundService 失败，退回 startService；rec=" + recId, t);
      try {
        c.startService(i);
      } catch (Throwable t2) {
        Log.e(TAG, "起服务失败；rec=" + recId, t2);
        markFailedAsync(c, recId, "系统不允许后台启动转写服务，请回到 App 里再点一次");
      }
    }
  }

  /** 取消。在跑就断在跑，不在跑就按 Db 里的 jobId 兜底删。 */
  public static void cancel(Context c, long recId) {
    if (c == null || recId <= 0) {
      return;
    }
    Intent i = new Intent(c, TranscribeService.class);
    i.setAction(ACTION_CANCEL);
    i.putExtra(EXTRA_REC, recId);
    try {
      c.startService(i);
    } catch (Throwable t) {
      Log.w(TAG, "发取消失败（服务可能没在跑）；rec=" + recId, t);
      // 服务都起不来，只能本地复位，避免列表永远卡在「转写中」
      resetLocally(c, recId);
    }
  }

  /** 内存里的进度；-1 表示这条没在跑（含：没开始、已结束、已失败）。 */
  public static int liveProgress(long recId) {
    Integer v = LIVE.get(recId);
    return v == null ? -1 : v.intValue();
  }

  /** 这条是不是正在转（含排队/上传）。 */
  public static boolean isRunning(long recId) {
    return LIVE.containsKey(recId);
  }

  /**
   * 进程被杀后重进界面用：把 Db 里 state=1/2 且仍有 jobId 的录音重新挂上轮询。
   * 界面在 onCreate/onResume 里调一次即可（会读一次 Db，别在滚动里调）。
   */
  public static void resumePending(Context c) {
    if (c == null) {
      return;
    }
    try {
      List<Rec> all = Db.get(c).list(null, -1, 500);
      if (all == null) {
        return;
      }
      // 打开应用时把**所有没完成的任务**统一复位回未转写。
      //
      // 为什么不再"续跑"：任务 ID 存在服务端，但手机这边进程已经没了，
      // 续跑依赖服务端还留着那个 job —— 服务重启过就丢。
      // 更实际的问题是：用户看到的"失败/转写中"其实是上次的地板状态，
      // 留着只会让人以为文件坏了。复位到未转写，需要时重新点一次。
      int reset = 0;
      for (int i = 0; i < all.size(); i++) {
        Rec r = all.get(i);
        if (r == null) continue;
        // 只放过"已完成"和"本来就是未转写"的
        if (r.state == ST_DONE || r.state == ST_NONE) continue;
        if (r.state != ST_FAILED && r.state != ST_QUEUED
            && r.state != ST_RUNNING && r.state != ST_UPLOADING) {
          continue;
        }
        Db.get(c).setJob(r.id, "");
        Db.get(c).setState(r.id, ST_NONE, 0, null);
        reset++;
        Log.i(TAG, "复位未完成任务 rec=" + r.id + " 原状态=" + r.state);
      }
      if (reset > 0) {
        Diag.add("Transcribe", "复位未完成任务 " + reset + " 条");
        Log.i(TAG, "本次复位未完成任务 " + reset + " 条");
      }
    } catch (Throwable t) {
      Log.w(TAG, "恢复未完成任务失败", t);
    }
  }

  // ------------------------------------------------------------------ Service 生命周期

  @Override
  public void onCreate() {
    super.onCreate();
    exec = Executors.newSingleThreadExecutor();
    Notify.ensureChannels(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    lastStartId = startId;
    destroyed = false;
    String action = intent == null ? null : intent.getAction();
    long recId = intent == null ? 0L : intent.getLongExtra(EXTRA_REC, 0L);

    if (ACTION_CANCEL.equals(action)) {
      // 先挂上通知：startForegroundService 起的实例必须在 5s 内 startForeground
      fg(recId, "录音转写", "取消中…", -1, true);
      handleCancel(recId);
      return START_NOT_STICKY;
    }

    if (recId <= 0) {
      // 没有任务（例如系统空唤醒）：别赖着当常驻服务
      Log.w(TAG, "onStartCommand 没有有效 rec_id，action=" + action);
      if (pending.get() <= 0) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
      }
      return START_NOT_STICKY;
    }

    fg(recId, "录音转写", "准备转写…", -1, true);
    Job job = new Job(recId);
    if (JOBS.putIfAbsent(recId, job) != null) {
      Log.i(TAG, "已在队列里，忽略重复 start；rec=" + recId);
      return START_NOT_STICKY;
    }
    LIVE.put(recId, 0);
    pending.incrementAndGet();
    try {
      exec.execute(new Runnable() {
        @Override
        public void run() {
          runJob(job);
        }
      });
    } catch (Throwable t) {
      // 线程池已经关了（服务在销毁），任务没法跑：写个失败，别让列表卡在排队
      pending.decrementAndGet();
      JOBS.remove(recId);
      LIVE.remove(recId);
      Log.e(TAG, "任务入队失败 rec=" + recId, t);
      markFailedAsync(this, recId, "服务内部错误：任务没能入队");
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    // 注意：这里**不**置 canceled —— 那是「用户取消」，会把 DB 复位成未转写。
    // 服务被系统回收只是暂停，state 保持 2 + jobId 留着，下次 start() 会续轮询。
    JOBS.clear();
    LIVE.clear();
    if (exec != null) {
      exec.shutdownNow();
    }
    releaseWake();
    super.onDestroy();
  }

  // ------------------------------------------------------------------ 主流程

  private void runJob(Job job) {
    final long recId = job.recId;
    final Db db = Db.get(this);
    String name = "录音";
    int lastPct = 0;
    String jobId = "";
    try {
      if (job.canceled.get()) {
        Log.i(TAG, "取消（还在排队）rec=" + recId);
        return;
      }
      acquireWake();

      Rec rec = db.byId(recId);
      if (rec == null) {
        notifyResult(recId, false, "录音", "找不到这条录音（可能已经被删了）");
        Log.w(TAG, "rec 不存在 id=" + recId);
        return;
      }
      final String title = displayName(rec);
      name = title;

      SttClient client = buildClient(this, db);
      jobId = rec.jobId == null ? "" : rec.jobId.trim();
      boolean resume = !jobId.isEmpty() && (rec.state == ST_QUEUED || rec.state == ST_RUNNING || rec.state == ST_UPLOADING);

      if (resume) {
        // 续轮询：不重新上传，直接接着看服务端那个任务
        lastPct = clampPct(rec.progress);
        db.setState(recId, ST_RUNNING, lastPct, null);
        LIVE.put(recId, lastPct);
        fg(recId, name, "接着等上次的任务…", lastPct, lastPct <= 0);
        Log.i(TAG, "续轮询 rec=" + recId + " job=" + jobId);
      } else {
        jobId = "";
        File f = new File(rec.path == null ? "" : rec.path);
        if (!f.isFile()) {
          fail(db, recId, name, 0, "音频文件不在了：" + displayFileName(rec, f));
          return;
        }

        // 大文件先问一次健康检查：鉴权不对就别白传几十兆（其它错误忽略，交给真上传去报）
        try {
          client.health();
        } catch (SttClient.SttException se) {
          if (se.code == 401 || se.code == 403) {
            throw se;
          }
          Log.w(TAG, "健康检查可疑，继续尝试上传：" + se.human);
        } catch (IOException ioe) {
          Log.w(TAG, "健康检查失败，继续尝试上传", ioe);
        }
        if (job.canceled.get()) {
          return;
        }

        db.setState(recId, ST_UPLOADING, 0, null);
        LIVE.put(recId, 0);
        fg(recId, name, "上传中…", 0, true);

        final String lang = safeLang(db.kv("lang", "zh"));
        final long[] lastSentPct = { -1 };
        jobId = client.submit(f, lang, new SttClient.UploadProgress() {
          @Override
          public void onBytes(long sent, long total) {
            if (job.canceled.get() || destroyed) {
              throw new CancelSignal();
            }
            int pct = total <= 0 ? 0 : (int) (sent * 100L / total);
            if (pct != lastSentPct[0]) {
              lastSentPct[0] = pct;
              if (pct >= 100) {
                // 字节发完 ≠ 完事：服务端要等任务真正入队才回 202，
                // 这中间可能好几秒（它要落盘、排队）。之前这里停在 99% 不动，
                // 看着像卡死；改成明确说"已发完，等服务器接收"。
                LIVE.put(recId, 99);
                fg(recId, title, "已发完，等服务器接收…", 0, true);
              } else {
                LIVE.put(recId, Math.max(0, Math.min(98, pct)));
                fg(recId, title, "上传中 " + pct + "%（" + humanBytes(sent) + " / " + humanBytes(total) + "）", 0, true);
              }
            }
          }
        });
        db.setJob(recId, jobId);
        db.setState(recId, ST_RUNNING, 0, null);
        LIVE.put(recId, 0);
        Log.i(TAG, "提交成功 rec=" + recId + " job=" + jobId);
      }

      // ---- 轮询 ----
      SttClient poller = client.withReadTimeout(POLL_READ_TIMEOUT_MS);
      long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
      String prevState = "";
      while (true) {
        if (destroyed) {
          Log.i(TAG, "服务销毁，暂停轮询（下次 start 续）rec=" + recId);
          return;
        }
        if (job.canceled.get()) {
          doCancel(db, recId, jobId, name);
          return;
        }
        if (System.currentTimeMillis() > deadline) {
          throw new SttClient.SttException(0, "等待超时（任务超过 3 小时还没转完）", "poll deadline exceeded");
        }
        renewWake();

        SttClient.JobStatus st = poller.status(jobId);
        String state = st.state == null ? "" : st.state.trim();

        if ("done".equals(state)) {
          break;
        }
        if ("failed".equals(state)) {
          String why = (st.error == null || st.error.trim().isEmpty())
              ? "服务器说转写失败（没给原因）" : st.error.trim();
          fail(db, recId, name, lastPct, why);
          return;
        }
        if ("canceled".equals(state)) {
          // 服务端那边取消了：本地回到未转写，原因写清楚
          db.setJob(recId, "");
          db.setState(recId, ST_NONE, 0, null);
          notifyResult(recId, false, name, "服务端把这个任务取消了（可能重启或人工取消）");
          Log.i(TAG, "服务端取消 rec=" + recId + " job=" + jobId);
          return;
        }

        int pct = clampPct((int) Math.round(st.progress * 100.0));
        if (pct != lastPct || !state.equals(prevState)) {
          lastPct = pct;
          prevState = state;
          db.setState(recId, ST_RUNNING, pct, null);
          LIVE.put(recId, pct);
          fg(recId, name, stateText(state, pct, st), pct, false);
        }
        sleepPoll(job);
      }

      // ---- 取结果、落库 ----
      db.setState(recId, ST_RUNNING, 99, null);
      LIVE.put(recId, 99);
      fg(recId, name, "收尾：取转写结果…", 99, false);

      StringBuilder text = new StringBuilder();
      // 取结果用长超时（几千句的长音频，返回体可能不小）
      List<Seg> segs = poller.withReadTimeout(SttClient.READ_TIMEOUT_LONG_MS).result(jobId, recId, text);
      int n = segs == null ? 0 : segs.size();
      if (n > 0) {
        db.saveSegs(recId, segs);
      }
      String full = text.toString().trim();
      if (full.isEmpty()) {
        full = joinText(segs);
      }
      db.setSegsDone(recId, full);          // 契约：这里面会置 state=3
      db.setState(recId, ST_DONE, 100, null); // 再兜一遍，保证 progress=100 / error 清空
      Log.i(TAG, "完成 rec=" + recId + " job=" + jobId + " segs=" + n + " chars=" + full.length());
      notifyResult(recId, true, name, n > 0 ? ("共 " + n + " 句，点开逐句看") : "转写完成");
    } catch (CancelSignal cs) {
      // 上传途中被取消：本地复位；服务端可能已经吃下半个文件，那个孤儿任务只能让它自己跑完
      if (!destroyed) {
        try {
          db.setJob(recId, "");
          db.setState(recId, ST_NONE, 0, null);
        } catch (Throwable t) {
          Log.w(TAG, "取消后写 Db 失败 rec=" + recId, t);
        }
      }
      Log.i(TAG, "已取消（上传阶段）rec=" + recId);
    } catch (InterruptedException ie) {
      // 服务在销毁：保持 state=2 + jobId，留给下次续轮询，不写失败
      Thread.currentThread().interrupt();
      Log.i(TAG, "轮询被中断（服务销毁）rec=" + recId);
    } catch (Throwable t) {
      String human = humanError(t);
      try {
        db.setState(recId, ST_FAILED, lastPct, human);
      } catch (Throwable t2) {
        Log.e(TAG, "写失败状态失败 rec=" + recId, t2);
      }
      notifyResult(recId, false, name, human);
      Log.e(TAG, "转写失败 rec=" + recId + " job=" + jobId + "：" + human, t);
    } finally {
      JOBS.remove(recId);
      LIVE.remove(recId);
      releaseWake();
      if (!destroyed) {
        finishIfIdle();
      }
    }
  }

  // ------------------------------------------------------------------ 取消

  private void handleCancel(long recId) {
    if (recId <= 0) {
      finishIfIdle();
      return;
    }
    Job running = JOBS.get(recId);
    if (running != null) {
      running.canceled.set(true);
      fg(recId, "录音转写", "正在取消…", -1, true);
      return; // 由 runJob 的检查点收尾（轮询最多 1.5s，上传立刻断）
    }
    // 内存里没有：进程可能重启过，按 Db 里的 jobId 兜底删
    pending.incrementAndGet();
    try {
      exec.execute(new Runnable() {
        @Override
        public void run() {
          try {
            Db db = Db.get(TranscribeService.this);
            Rec rec = db.byId(recId);
            if (rec == null) {
              return;
            }
            if (rec.state == ST_QUEUED || rec.state == ST_RUNNING || rec.state == ST_UPLOADING) {
              doCancel(db, recId, rec.jobId == null ? "" : rec.jobId.trim(), displayName(rec));
            }
          } catch (Throwable t) {
            Log.w(TAG, "兜底取消失败 rec=" + recId, t);
          } finally {
            finishIfIdle();
          }
        }
      });
    } catch (Throwable t) {
      pending.decrementAndGet();
      Log.e(TAG, "兜底取消入队失败 rec=" + recId, t);
      resetLocally(this, recId);
    }
  }

  /** 真正执行取消：通知服务端 + 本地复位 + 必要时告知「远端可能还在跑」。 */
  private void doCancel(Db db, long recId, String jobId, String name) {
    boolean remoteOk = true;
    try {
      if (jobId != null && !jobId.trim().isEmpty()) {
        SttClient c = buildClient(this, db);
        remoteOk = c.cancel(jobId); // false = 任务本来就已经结束，也算干净
      }
    } catch (Throwable t) {
      remoteOk = false;
      Log.w(TAG, "通知服务端取消失败 rec=" + recId + " job=" + jobId, t);
    }
    try {
      db.setJob(recId, "");
      db.setState(recId, ST_NONE, 0, null);
    } catch (Throwable t) {
      Log.w(TAG, "取消后写 Db 失败 rec=" + recId, t);
    }
    if (!remoteOk) {
      // 用户点的是取消，本地已停；远端可能还在跑这台 GPU，得说一声
      notifyResult(recId, false, name, "本地已取消，但服务器没应答（远端任务可能还在跑）");
    }
    Log.i(TAG, "已取消 rec=" + recId + " job=" + jobId + " remoteOk=" + remoteOk);
  }

  /** 服务都起不来时的最后手段：直接改 Db（不在主线程之外做网络，纯本地）。 */
  private static void resetLocally(Context c, long recId) {
    try {
      Db db = Db.get(c);
      db.setJob(recId, "");
      db.setState(recId, ST_NONE, 0, null);
    } catch (Throwable t) {
      Log.w(TAG, "本地复位失败 rec=" + recId, t);
    }
  }

  /** 起服务就被系统拒（后台限制之类）：写一条可见的失败，别让用户等一个不存在的任务。 */
  private static void markFailedAsync(final Context c, final long recId, final String human) {
    try {
      new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            Db.get(c).setState(recId, ST_FAILED, 0, human);
          } catch (Throwable t) {
            Log.e(TAG, "写「服务起不来」状态失败 rec=" + recId, t);
          }
        }
      }, "recstt-start-fail").start();
    } catch (Throwable t) {
      Log.e(TAG, "连兜底线程都起不来 rec=" + recId, t);
    }
  }

  // ------------------------------------------------------------------ 失败 / 通知

  private void fail(Db db, long recId, String name, int pct, String human) {
    String why = (human == null || human.trim().isEmpty()) ? "转写失败（原因未知）" : human.trim();
    try {
      db.setState(recId, ST_FAILED, clampPct(pct), why);
    } catch (Throwable t) {
      Log.e(TAG, "写失败状态失败 rec=" + recId, t);
    }
    notifyResult(recId, false, name, why);
    Log.w(TAG, "转写失败 rec=" + recId + "：" + why);
  }

  /** 前台通知（id 固定 ID_SERVICE，常驻）。 */
  private void fg(long recId, String title, String text, int pct, boolean indeterminate) {
    if (destroyed) {
      return;
    }
    try {
      Notification n = Notify.progress(this, recId, title, text, pct, indeterminate);
      startForeground(Notify.ID_SERVICE, n);
    } catch (Throwable t) {
      Log.w(TAG, "更新前台通知失败 rec=" + recId, t);
    }
  }

  private void notifyResult(long recId, boolean ok, String name, String detail) {
    try {
      Notify.result(this, recId, ok, name, detail);
    } catch (Throwable t) {
      Log.w(TAG, "发结果通知失败 rec=" + recId, t);
    }
  }

  private void finishIfIdle() {
    if (pending.get() > 0 || destroyed) {
      return;
    }
    try {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } catch (Throwable t) {
      Log.w(TAG, "stopForeground 失败", t);
    }
    stopSelf(lastStartId);
  }

  // ------------------------------------------------------------------ 小工具

  private static SttClient buildClient(Context ctx, Db db) {
    // 局域网能通就用局域网，不通自动退 WG（Endpoints 是拿 /v2/health 实测的，不是猜 SSID）。
    // 两条都不通时它仍返回一个地址，让上层报出真实的连接错误，而不是悄悄换到别的机器。
    String url = Endpoints.pick(ctx);
    String token = db.kv("token", null);
    if (token == null) {
      token = "";
    }
    SttClient client = new SttClient(url, token);
    // 关键：探测走隧道、转写也必须走隧道。
    // 之前只给 Endpoints 挂了代理，真正上传却直连，于是"自检通了、转写全超时"。
    if (Tunnel.running()) {
      client.withHttpProxy("127.0.0.1", Tunnel.LOCAL_PORT);
      Log.i(TAG, "本次转写走隧道: " + url);
    } else {
      Log.i(TAG, "本次转写直连: " + url);
    }
    return client;
  }

  private static String safeLang(String lang) {
    if (lang == null) {
      return "zh";
    }
    String s = lang.trim();
    return s.isEmpty() ? "zh" : s;
  }

  /** 异常 → 给用户的一句话；口径统一放在 SttClient.human（界面自检也用它）。 */
  static String humanError(Throwable t) {
    return SttClient.human(t);
  }

  private static String stateText(String state, int pct, SttClient.JobStatus st) {
    StringBuilder sb = new StringBuilder();
    if ("loading".equals(state)) {
      sb.append("服务端加载模型…");
    } else if ("queued".equals(state)) {
      sb.append("排队中…");
    } else if ("running".equals(state)) {
      sb.append("转写中 ").append(pct).append('%');
    } else {
      sb.append("处理中 ").append(pct).append('%');
    }
    if (st != null && st.segments > 0) {
      sb.append(" · 已出 ").append(st.segments).append(" 句");
    }
    if (st != null && st.etaSec != null && st.etaSec.longValue() > 0) {
      sb.append(" · 剩余约 ").append(st.etaSec.longValue()).append(" 秒");
    }
    return sb.toString();
  }

  /** 轮询间隔里每 200ms 看一眼取消标记，保证点取消后不用等满 1.5 秒。 */
  private static void sleepPoll(Job job) throws InterruptedException {
    long until = System.currentTimeMillis() + POLL_MS;
    while (System.currentTimeMillis() < until) {
      if (job.canceled.get()) {
        return;
      }
      long left = until - System.currentTimeMillis();
      Thread.sleep(left < 200 ? Math.max(1L, left) : 200L);
    }
  }

  private void acquireWake() {
    try {
      if (wake == null) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
          wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recstt:transcribe");
          wake.setReferenceCounted(false);
        }
      }
      if (wake != null && !wake.isHeld()) {
        wake.acquire(WAKE_MS);
      }
    } catch (Throwable t) {
      Log.w(TAG, "拿 WakeLock 失败", t);
    }
  }

  /** 长任务（>30min）WakeLock 会过期，轮询里续上；拿不到也不致命。 */
  private void renewWake() {
    try {
      if (wake != null && !wake.isHeld()) {
        wake.acquire(WAKE_MS);
      }
    } catch (Throwable t) {
      Log.w(TAG, "续 WakeLock 失败", t);
    }
  }

  private void releaseWake() {
    try {
      if (wake != null && wake.isHeld()) {
        wake.release();
      }
    } catch (Throwable t) {
      Log.w(TAG, "放 WakeLock 失败", t);
    }
  }

  private static String displayName(Rec rec) {
    if (rec == null) {
      return "录音";
    }
    if (rec.displayName != null && !rec.displayName.trim().isEmpty()) {
      return rec.displayName.trim();
    }
    if (rec.fileName != null && !rec.fileName.trim().isEmpty()) {
      return rec.fileName.trim();
    }
    String p = rec.path;
    if (p != null) {
      int i = p.lastIndexOf('/');
      if (i >= 0 && i + 1 < p.length()) {
        return p.substring(i + 1);
      }
      return p;
    }
    return "录音";
  }

  private static String displayFileName(Rec rec, File f) {
    if (rec != null && rec.fileName != null && !rec.fileName.trim().isEmpty()) {
      return rec.fileName.trim();
    }
    return f == null ? "?" : f.getName();
  }

  private static String joinText(List<Seg> segs) {
    if (segs == null || segs.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segs.size(); i++) {
      Seg s = segs.get(i);
      if (s == null || s.text == null) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(s.text);
    }
    return sb.toString();
  }

  private static String humanBytes(long bytes) {
    if (bytes < 1024L) {
      return bytes + " B";
    }
    if (bytes < 1024L * 1024L) {
      return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }
    if (bytes < 1024L * 1024L * 1024L) {
      return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  private static int clampPct(int pct) {
    if (pct < 0) {
      return 0;
    }
    return pct > 100 ? 100 : pct;
  }

  /** 一条在跑的任务（只存取消标记，进度在 LIVE 表里）。 */
  private static final class Job {
    final long recId;
    final AtomicBoolean canceled = new AtomicBoolean(false);

    Job(long recId) {
      this.recId = recId;
    }
  }

  /** 上传回调里中断上传用的私有信号（UploadProgress 不能抛受检异常）。 */
  private static final class CancelSignal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CancelSignal() {
      super("canceled");
    }
  }
}
