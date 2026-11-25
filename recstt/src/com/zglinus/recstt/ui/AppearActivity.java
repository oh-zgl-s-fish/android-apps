package com.zglinus.recstt.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.store.Db;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 设计稿第 11 屏：外观（自定义背景 / 模糊 / 遮罩 / 从背景取色开关）。
 *
 * <p>kv 读写严格按 INTERFACES.md：
 * <ul>
 *   <li>{@code bg_path}：当前背景图路径（复制到 getFilesDir()/bg.jpg 后的绝对路径）</li>
 *   <li>{@code bg_enabled}：1=启用背景图，0=不启用（纯色/无背景都属于这一类）</li>
 *   <li>{@code bg_blur} 0–40（默认 24）、{@code bg_scrim} 0–80（默认 45）</li>
 *   <li>{@code bg_tint}：v0.1 只保存开关值，取色逻辑下版本才生效</li>
 * </ul>
 *
 * <p>「纯色」和「无背景」在 0.1 里对 Bg 的效果是一样的（都不铺图，用主题底色），
 * 这里额外用 {@code bg_mode} 记住本页选中项，只在文案和预览上区分；
 * 其它界面仍只认 bg_path / bg_enabled，不受影响。
 */
public final class AppearActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 9101;

    private static final int BLUR_MAX = 40;
    private static final int SCRIM_MAX = 80;

    /** 模糊低于这个百分比（且确实有背景图）时提示文字可能看不清。 */
    private static final int SCRIM_LOW = 20;

    /** 纯色模式预览用的示例底色（和主题底色区分开，以免看起来像"没生效"）。 */
    private static final int COLOR_SOLID = 0xFF4A4458;

    private static final long MAX_BG_BYTES = 24L * 1024L * 1024L;

    private Db db;

    /** image / solid / none */
    private String mode = "none";
    private int blur = 24;
    /** 卡片毛玻璃单独一档：和背景模糊解耦，背景糊但卡片还可以更糊/更清。 */
    private int cardBlur = 16;
    private int scrim = 45;

    private Bitmap previewBmp;
    private Bitmap thumbBmp;

    private FrameLayout preview;
    private ImageView previewImg;
    private View previewScrim;
    private LinearLayout tilesRow;
    private TextView statusLine;
    private TextView warn;
    private TextView blurVal;
    private TextView cardBlurVal;
    private TextView scrimVal;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this); // 先定主题/调色板，再 super.onCreate
        super.onCreate(savedInstanceState);
        db = Db.get(this);

        mode = resolveMode();
        blur = clamp(intKv("bg_blur", 24), 0, BLUR_MAX);
        cardBlur = clamp(intKv("bg_card_blur", 16), 0, BLUR_MAX);
        scrim = clamp(intKv("bg_scrim", 45), 0, SCRIM_MAX);
        loadBitmaps();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.SURFACE);

        LinearLayout page = Ui.col(this);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(page);

        page.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = Ui.col(this);
        content.setPadding(dp(16), dp(6), dp(16), dp(28));
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顺序按设计稿语义：先选背景，再调模糊/遮罩，预览卡片就在滑杆下方，拖的时候肉眼能对上
        buildBgCard(content);
        buildBlurCard(content);
        sectionTitle(content, "预览");
        buildPreview(content);
        buildWarn(content);
        buildTintCard(content);
        buildFootnote(content);

        setContentView(root);
        renderAll();
    }

    /** 注意：本页是背景编辑页，故意不铺旧背景（否则改了模式后旧图还留在后面，会和预览打架）。 */

    // ---------------------------------------------------------------- 顶栏

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setMinimumHeight(dp(56) + statusBarHeight());
        bar.setPadding(dp(6), statusBarHeight(), dp(12), 0);
        // 顶栏压一层近不透明的底，深色背景图上标题也读得清。
        // 原来写死 0xF2FEF7FF：纯色模式跟系统深色时就成了白底白字（真机复现）。
        bar.setBackgroundColor(Ui.alpha(Ui.SURFACE, 0xF2));

        View back = backButton();
        bar.addView(back);

        TextView title = Ui.tv(this, "外观", 20, Ui.ON_SURFACE, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(4);
        bar.addView(title, tlp);
        return bar;
    }

    private View backButton() {
        int res = drawableId("ic_back");
        if (res != 0) {
            View b = Ui.iconButton(this, res, Ui.ON_SURFACE, new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
            b.setContentDescription("返回");
            return b;
        }
        // 没有图标资源时退化成文字箭头，保证能返回
        TextView t = Ui.tv(this, "←", 22, Ui.ON_SURFACE, false);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription("返回");
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        return t;
    }

    // ---------------------------------------------------------------- 预览卡片

    private void buildPreview(LinearLayout content) {
        preview = new FrameLayout(this);
        preview.setBackground(Ui.round(0xFF2B2633, 20, this));
        roundedClip(preview, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
        lp.bottomMargin = dp(8);
        content.addView(preview, lp);
    }

    private void buildWarn(LinearLayout content) {
        warn = Ui.tv(this, "遮罩偏低，文字可能看不清", 12, Ui.ERROR, true);
        warn.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        lp.bottomMargin = dp(2);
        content.addView(warn, lp);
    }

    // ---------------------------------------------------------------- 背景选择

    private void buildBgCard(LinearLayout content) {
        sectionTitle(content, "背景");
        LinearLayout card = card(content);

        tilesRow = Ui.row(this);
        card.addView(tilesRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusLine = Ui.tv(this, "", 12, Ui.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        card.addView(statusLine, slp);

        TextView pick = Ui.tv(this, "从相册选图…", 13, Ui.PRIMARY, true);
        pick.setPadding(0, dp(12), dp(8), dp(2));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        card.addView(pick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addTile(String kind, String label) {
        final boolean selected = mode.equals(kind);

        LinearLayout t = Ui.col(this);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(6), dp(9), dp(6), dp(9));
        t.setBackground(Ui.round(
                selected ? Ui.SECONDARY_CONTAINER : Ui.SURFACE, 14,
                selected ? Ui.PRIMARY : Ui.OUTLINE_VARIANT, selected ? 2 : 1, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTile(kind); }
        });

        FrameLayout swatch = new FrameLayout(this);
        swatch.setBackground(Ui.round(Ui.SURFACE_HIGH, 8, this));
        roundedClip(swatch, 8);
        t.addView(swatch, new LinearLayout.LayoutParams(dp(58), dp(34)));

        if ("image".equals(kind)) {
            if (thumbBmp != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(thumbBmp);
                swatch.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                swatch.addView(centerHint("未选"), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else if ("solid".equals(kind)) {
            View v = new View(this);
            v.setBackgroundColor(COLOR_SOLID);
            swatch.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            swatch.addView(centerHint("无"), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        TextView lab = Ui.tv(this, label, 12,
                selected ? Ui.ON_SECONDARY_CONTAINER : Ui.ON_SURFACE_VARIANT, selected);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(6);
        t.addView(lab, llp);

        tilesRow.addView(t);
    }

    private TextView centerHint(String s) {
        TextView t = Ui.tv(this, s, 10, Ui.OUTLINE, false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void onTile(String kind) {
        if ("image".equals(kind)) {
            if (existingBgFile() == null) {
                pickImage();
                return;
            }
            setMode("image");
        } else if ("solid".equals(kind)) {
            setMode("solid");
        } else {
            setMode("none");
        }
    }

    /**
     * 选背景图：ACTION_OPEN_DOCUMENT → 读流 → 复制到 getFilesDir()/bg.jpg → 写 kv。
     * 先写 bg.tmp 再 rename，避免复制到一半退出把旧背景弄坏。
     */
    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "系统里没有文件选择器，无法选图", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        importImage(data.getData());
    }

    private void importImage(final Uri uri) {
        final File dir = getFilesDir();
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = copyToBgFile(dir, uri);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (err != null) {
                            Toast.makeText(AppearActivity.this, "选图失败：" + err,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        File dst = new File(dir, "bg.jpg");
                        db.kvPut("bg_path", dst.getAbsolutePath());
                        db.kvPut("bg_enabled", "1");
                        db.kvPut("bg_mode", "image");
                        mode = "image";
                        loadBitmaps();
                        renderAll();
                        Toast.makeText(AppearActivity.this, "背景已更新",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "bg-import").start();
    }

    /** 后台线程里跑；成功返回 null，失败返回一句人话。 */
    private String copyToBgFile(File dir, Uri uri) {
        File tmp = new File(dir, "bg.tmp");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return "打不开所选图片";
            }
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BG_BYTES) {
                    return "图片太大（上限 24 MB）";
                }
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return "这不是能识别的图片";
            }

            File dst = new File(dir, "bg.jpg");
            if (dst.exists() && !dst.delete()) {
                return "旧背景删不掉";
            }
            if (!tmp.renameTo(dst)) {
                return "背景写入失败";
            }
            return null;
        } catch (Exception e) {
            String m = e.getMessage();
            return m == null || m.isEmpty() ? e.toString() : m;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    // ---------------------------------------------------------------- 模糊 / 遮罩

    private void buildBlurCard(LinearLayout content) {
        sectionTitle(content, "模糊与遮罩");
        LinearLayout card = card(content);

        LinearLayout blurRow = Ui.row(this);
        blurRow.setMinimumHeight(dp(44));
        TextView blurLab = Ui.tv(this, "模糊", 14, Ui.ON_SURFACE, false);
        blurRow.addView(blurLab, Ui.lp(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar blurBar = new SeekBar(this);
        blurBar.setMax(BLUR_MAX);
        blurBar.setProgress(blur);
        tintSeekBar(blurBar);
        blurRow.addView(blurBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        blurVal = Ui.tv(this, blur + " dp", 12, Ui.ON_SURFACE_VARIANT, false);
        blurVal.setGravity(Gravity.END);
        blurRow.addView(blurVal, Ui.lp(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 监听器在数值 TextView 建好之后再挂，拖动时不会踩到空引用
        blurBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                blur = progress;
                blurVal.setText(blur + " dp");
                if (fromUser) {
                    db.kvPut("bg_blur", String.valueOf(blur));
                    updatePreviewLive();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        card.addView(blurRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 卡片模糊：和上面的背景模糊分开调。
        // 背景可以很糊（当壁纸用），卡片仍然可以在中间找一档——太糊字会飘，太清背景花纹又抢字。
        LinearLayout cardRow = Ui.row(this);
        cardRow.setMinimumHeight(dp(44));
        TextView cardLab = Ui.tv(this, "卡片", 14, Ui.ON_SURFACE, false);
        cardRow.addView(cardLab, Ui.lp(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar cardBar = new SeekBar(this);
        cardBar.setMax(BLUR_MAX);
        cardBar.setProgress(cardBlur);
        tintSeekBar(cardBar);
        cardRow.addView(cardBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cardBlurVal = Ui.tv(this, cardBlur + " dp", 12, Ui.ON_SURFACE_VARIANT, false);
        cardBlurVal.setGravity(Gravity.END);
        cardRow.addView(cardBlurVal, Ui.lp(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT));

        cardBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cardBlur = progress;
                cardBlurVal.setText(cardBlur + " dp");
                if (fromUser) {
                    db.kvPut("bg_card_blur", String.valueOf(cardBlur));
                    updatePreviewLive();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        card.addView(cardRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout scrimRow = Ui.row(this);
        scrimRow.setMinimumHeight(dp(44));
        TextView scrimLab = Ui.tv(this, "遮罩", 14, Ui.ON_SURFACE, false);
        scrimRow.addView(scrimLab, Ui.lp(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar scrimBar = new SeekBar(this);
        scrimBar.setMax(SCRIM_MAX);
        scrimBar.setProgress(scrim);
        tintSeekBar(scrimBar);
        scrimRow.addView(scrimBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scrimVal = Ui.tv(this, scrim + " %", 12, Ui.ON_SURFACE_VARIANT, false);
        scrimVal.setGravity(Gravity.END);
        scrimRow.addView(scrimVal, Ui.lp(dp(50), ViewGroup.LayoutParams.WRAP_CONTENT));

        scrimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scrim = progress;
                scrimVal.setText(scrim + " %");
                if (fromUser) {
                    db.kvPut("bg_scrim", String.valueOf(scrim));
                    updatePreviewLive();
                    updateWarn();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        card.addView(scrimRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView range = Ui.tv(this, "模糊 0–40 dp（Android 12+ 生效，低版本只遮罩）；遮罩 0–80 %",
                11, Ui.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        card.addView(range, rlp);
    }

    private void tintSeekBar(SeekBar bar) {
        ColorStateList cs = ColorStateList.valueOf(Ui.PRIMARY);
        bar.setProgressTintList(cs);
        bar.setThumbTintList(cs);
    }

    // ---------------------------------------------------------------- 取色开关

    private void buildTintCard(LinearLayout content) {
        sectionTitle(content, "取色");
        LinearLayout card = card(content);

        LinearLayout row = Ui.row(this);
        TextView title = Ui.tv(this, "从背景取色", 15, Ui.ON_SURFACE, false);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked("1".equals(db.kv("bg_tint", "0").trim()));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                db.kvPut("bg_tint", checked ? "1" : "0");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = Ui.tv(this,
                "v0.1 只保存开关（bg_tint），取色逻辑下版本生效，本版本不会改变主题色。",
                12, Ui.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        card.addView(sub, slp);
    }

    private void buildFootnote(LinearLayout content) {
        TextView foot = Ui.tv(this,
                "模糊/遮罩实时预览；对比度低于 4.5:1 会提示", 12, Ui.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        content.addView(foot, lp);

        TextView foot2 = Ui.tv(this,
                "对比度按遮罩百分比粗判：有背景图且遮罩低于 20% 时给红字提醒。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(4);
        content.addView(foot2, lp2);
    }

    // ---------------------------------------------------------------- 渲染

    private void renderAll() {
        renderTiles();
        rebuildPreview();
        updateStatus();
        updateWarn();
    }

    private void renderTiles() {
        tilesRow.removeAllViews();
        addTile("image", "当前图片");
        addTile("solid", "纯色");
        addTile("none", "无背景");
    }

    /** 模式切换/换图时才重建预览的层级；拖滑杆只改 alpha 和模糊半径，不重建 View。 */
    private void rebuildPreview() {
        preview.removeAllViews();
        previewImg = null;
        previewScrim = null;

        boolean image = "image".equals(mode) && previewBmp != null;
        boolean solid = "solid".equals(mode);
        boolean imageBroken = "image".equals(mode) && previewBmp == null;
        boolean withScrim = image || solid;

        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        if (image) {
            previewImg = new ImageView(this);
            previewImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            previewImg.setImageBitmap(previewBmp);
            preview.addView(previewImg, fill);
        } else {
            View base = new View(this);
            base.setBackgroundColor(solid ? COLOR_SOLID : Ui.SURFACE);
            preview.addView(base, fill);
        }

        if (withScrim) {
            previewScrim = new View(this);
            preview.addView(previewScrim, fill);
        }

        LinearLayout box = Ui.col(this);
        box.setPadding(dp(16), 0, dp(16), dp(14));
        preview.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        int c1 = withScrim ? 0xFFFFFFFF : Ui.ON_SURFACE;
        int c2 = withScrim ? 0xE6FFFFFF : Ui.ON_SURFACE_VARIANT;
        box.addView(Ui.tv(this, "王小明 · 今天 14:32", 17, c1, true));
        TextView l2 = Ui.tv(this, "通话录音已转成文字，边听边看。", 13, c2, false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(2);
        box.addView(l2, lp2);

        if (imageBroken) {
            TextView err = Ui.tv(this, "背景图读取失败，请重新选图", 12, Ui.ERROR, true);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(6);
            box.addView(err, elp);
        }

        updatePreviewLive();
    }

    /** 拖滑杆时只动这两样：遮罩 alpha、图片模糊半径。 */
    private void updatePreviewLive() {
        if (previewScrim != null) {
            int alpha = Math.round(255f * Math.max(0, Math.min(100, scrim)) / 100f);
            previewScrim.setBackgroundColor(Color.argb(alpha, 16, 14, 22));
        }
        if (previewImg != null) {
            applyBlur(previewImg, blur > 0 ? dp(blur) : 0);
        }
    }

    private void updateStatus() {
        File f = existingBgFile();
        if ("image".equals(mode)) {
            statusLine.setText(f == null ? "当前：尚未选择图片"
                    : "当前：自定义图片 " + f.getName() + "（" + sizeText(f.length()) + "）");
        } else if ("solid".equals(mode)) {
            statusLine.setText("当前：纯色（使用主题底色，不铺背景图）");
        } else {
            statusLine.setText("当前：无背景（使用主题底色）");
        }
    }

    private void updateWarn() {
        // 用预览里真的铺上了图来判断，避免拖动滑杆时每帧查 SQLite
        boolean low = "image".equals(mode) && scrim < SCRIM_LOW && previewImg != null;
        warn.setVisibility(low ? View.VISIBLE : View.GONE);
    }

    /** 只有 Android 12+ 才有 RenderEffect；低版本保持不模糊，遮罩照旧。radiusPx<=0 表示清除模糊。 */
    private static void applyBlur(ImageView iv, int radiusPx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iv.setRenderEffect(radiusPx > 0
                    ? RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                    : null);
        }
    }

    // ---------------------------------------------------------------- 状态与 kv

    private String resolveMode() {
        String path = db.kv("bg_path", "");
        boolean enabled = !"0".equals(db.kv("bg_enabled", "1").trim());
        if (path != null && !path.trim().isEmpty() && enabled && new File(path.trim()).isFile()) {
            return "image";
        }
        String m = db.kv("bg_mode", "");
        if ("solid".equals(m)) {
            return "solid";
        }
        if ("image".equals(m) && existingBgFile() != null) {
            return "image";
        }
        return "none";
    }

    private void setMode(String m) {
        if ("image".equals(m)) {
            File f = existingBgFile();
            if (f == null) {
                // 还没有图：先别改状态，等选完图在 onActivityResult 里切
                pickImage();
                return;
            }
            mode = "image";
            db.kvPut("bg_path", f.getAbsolutePath());
            db.kvPut("bg_enabled", "1");
        } else {
            mode = m;
            db.kvPut("bg_path", "");
            db.kvPut("bg_enabled", "0");
        }
        db.kvPut("bg_mode", mode); // 只记本页选中项，Bg 不读它
        loadBitmaps();
        // 背景一换，主题/调色板/毛玻璃全都要重来：只 renderAll 刷本页文案是不够的，
        // 真机表现就是"切了纯色，卡片还是旧毛玻璃、底色也没变"。
        recreate();
    }

    private File existingBgFile() {
        String p = db.kv("bg_path", "");
        if (p != null && !p.trim().isEmpty()) {
            File f = new File(p.trim());
            if (f.isFile() && f.length() > 0) {
                return f;
            }
        }
        File def = new File(getFilesDir(), "bg.jpg");
        return def.isFile() && def.length() > 0 ? def : null;
    }

    private void loadBitmaps() {
        File f = existingBgFile();
        if (f == null) {
            previewBmp = null;
            thumbBmp = null;
            return;
        }
        // 缩略图一直留着（切回"当前图片"要用）；大图只在真的显示图片背景时才解码，省内存
        thumbBmp = decodeScaled(f, 320);
        previewBmp = "image".equals(mode) ? decodeScaled(f, 1600) : null;
    }

    private static Bitmap decodeScaled(File f, int maxDim) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), b);
            if (b.outWidth <= 0 || b.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(b.outWidth, b.outHeight) / sample > maxDim) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private int intKv(String key, int def) {
        String s = db.kv(key, String.valueOf(def));
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private void sectionTitle(LinearLayout parent, String text) {
        TextView t = Ui.tv(this, text, 13, Ui.ON_SURFACE_VARIANT, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(2);
        parent.addView(t, lp);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = Ui.col(this);
        int pad = dp(16);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(Ui.round(Ui.SURFACE_CONTAINER, 20, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(c, lp);
        return c;
    }

    private void roundedClip(final View v, final float radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int drawableId(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignore) {
            // 关不掉就算了
        }
    }
}
