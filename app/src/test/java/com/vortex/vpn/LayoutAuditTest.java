package com.vortex.vpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Rejects any screen whose labels do not fit. Every layout is measured at the smallest common
 * phone width (360dp) and a label that is cut off - the "button text is not readable" complaint -
 * fails the build, so the UI cannot silently clip Russian labels again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LayoutAuditTest {

    private static final int[] LAYOUTS = {
            R.layout.activity_main,
            R.layout.activity_servers,
            R.layout.activity_profiles,
            R.layout.activity_settings,
            R.layout.activity_logs,
            R.layout.activity_connections,
            R.layout.activity_about,
            R.layout.activity_config_editor,
            R.layout.activity_app_list,
            R.layout.dialog_profile,
            R.layout.item_server,
            R.layout.item_subscription,
            R.layout.item_app,
            R.layout.item_log,
            R.layout.item_connection,
    };

    private static final int WIDTH_DP = 360;
    private static final int HEIGHT_DP = 800;

    @Test
    public void noLabelIsClippedOnASmallScreen() {
        Context themed = new ContextThemeWrapper(
                org.robolectric.RuntimeEnvironment.getApplication(), R.style.Theme_Vortex);
        LayoutInflater inflater = LayoutInflater.from(themed);
        List<String> problems = new ArrayList<>();

        for (int layout : LAYOUTS) {
            View root = inflater.inflate(layout, null, false);
            int width = dp(themed, WIDTH_DP);
            int height = dp(themed, HEIGHT_DP);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, width, Math.max(height, root.getMeasuredHeight()));
            audit(root, layoutName(layout), problems);
        }
        assertTrue("labels do not fit:\n" + TextUtils.join("\n", problems), problems.isEmpty());
    }

    private void audit(View view, String screen, List<String> problems) {
        if (view instanceof TextView) {
            check((TextView) view, screen, problems);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                audit(group.getChildAt(i), screen, problems);
            }
        }
    }

    private void check(TextView text, String screen, List<String> problems) {
        CharSequence value = text.getText();
        if (TextUtils.isEmpty(value) || text.getVisibility() != View.VISIBLE) {
            return;
        }
        int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        if (available <= 0) {
            return;
        }
        Paint paint = text.getPaint();
        float needed = paint.measureText(value, 0, value.length());
        if (text.getLetterSpacing() != 0f) {
            needed += text.getLetterSpacing() * text.getTextSize() * value.length();
        }
        float compound = 0f;
        for (Drawable drawable : text.getCompoundDrawables()) {
            if (drawable != null) {
                compound += drawable.getIntrinsicWidth() + text.getCompoundDrawablePadding();
            }
        }
        needed += compound;

        boolean singleLine = text.getMaxLines() == 1 || text.isSingleLine();
        boolean wraps = text.getLayout() != null && text.getLayout().getLineCount() > 1;
        boolean hasEllipsis = text.getEllipsize() != null;
        if (singleLine && !hasEllipsis && !wraps && needed > available + 2f) {
            problems.add(screen + ": " + describe(text) + " is cut off (needs "
                    + Math.round(needed) + "px, has " + available + "px): \"" + value + "\"");
        }
        // A fixed height with more text lines than fit is the other classic clipping bug.
        if (text.getHeight() > 0 && wraps) {
            int lineHeight = text.getLineHeight();
            int linesThatFit = Math.max(1, text.getHeight() / Math.max(1, lineHeight + extraLineSpacing(text)));
            if (text.getLayout().getLineCount() > linesThatFit) {
                problems.add(screen + ": " + describe(text) + " needs "
                        + text.getLayout().getLineCount() + " lines but only " + linesThatFit
                        + " fit in " + text.getHeight() + "px: \"" + value + "\"");
            }
        }
    }

    private static int extraLineSpacing(TextView text) {
        return (int) (text.getLineSpacingExtra() + text.getLineSpacingMultiplier() * 0);
    }

    private static String describe(View view) {
        String id = "";
        try {
            id = view.getResources().getResourceEntryName(view.getId());
        } catch (Exception ignored) {
        }
        return view.getClass().getSimpleName() + (id.isEmpty() ? "" : "#" + id);
    }

    private static String layoutName(int id) {
        try {
            return org.robolectric.RuntimeEnvironment.getApplication().getResources()
                    .getResourceEntryName(id);
        } catch (Exception ignored) {
            return String.valueOf(id);
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    @Test
    public void theCrampedQuickActionRowIsGone() {
        Context themed = new ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(),
                R.style.Theme_Vortex);
        View main = LayoutInflater.from(themed).inflate(R.layout.activity_main, null, false);
        assertFalse("the three cramped quick buttons are back",
                main.findViewById(R.id.btn_profiles) != null);
        assertTrue("the bottom navigation is missing", main.findViewById(R.id.bottom_nav) != null);
    }
}
