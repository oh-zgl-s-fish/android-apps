package com.zglinus.recstt.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

/**
 * 首页顶栏交互：搜索框开关、「更多」菜单。从 MainActivity 抽出的功能模块。
 */
public class MainMenu {

    private static final int MENU_TPL = 1;
    private static final int MENU_APPEAR = 2;
    private static final int MENU_SETTINGS = 3;
    private static final int MENU_DIAG = 4;

    public interface Ui {
        /** 搜索框关掉且内容清空了，或者需要重新拉一次列表。 */
        void reload();
    }

    private final Activity act;
    private final LinearLayout searchBox;
    private final EditText search;
    private final Ui ui;

    public MainMenu(Activity act, LinearLayout searchBox, EditText search, Ui ui) {
        this.act = act;
        this.searchBox = searchBox;
        this.search = search;
        this.ui = ui;
    }

    /** 点放大镜：开关搜索框。 */
    public void toggleSearch() {
        boolean show = searchBox.getVisibility() != View.VISIBLE;
        searchBox.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm =
                (InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (show) {
            search.requestFocus();
            act.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (imm != null) {
                imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            if (imm != null) {
                imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
            if (search.getText().length() > 0) {
                search.setText(""); // 触发 afterTextChanged → reload
            } else {
                ui.reload();
            }
        }
    }

    /** 点「更多」：匹配模板 / 外观 / 设置 / 复制诊断信息。 */
    public void showMore(View anchor) {
        PopupMenu p = new PopupMenu(act, anchor);
        p.getMenu().add(0, MENU_TPL, 0, "匹配模板");
        p.getMenu().add(0, MENU_APPEAR, 1, "外观");
        p.getMenu().add(0, MENU_SETTINGS, 2, "设置");
        p.getMenu().add(0, MENU_DIAG, 3, "复制诊断信息");
        p.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_TPL:
                        act.startActivity(new Intent(act, TemplatesActivity.class));
                        return true;
                    case MENU_APPEAR:
                        act.startActivity(new Intent(act, AppearActivity.class));
                        return true;
                    case MENU_SETTINGS:
                        act.startActivity(new Intent(act, SettingsActivity.class));
                        return true;
                    case MENU_DIAG:
                        Glass.dumpLastDraws();
                        Diag.copyToClipboard(act);
                        return true;
                    default:
                        return false;
                }
            }
        });
        p.show();
    }
}
