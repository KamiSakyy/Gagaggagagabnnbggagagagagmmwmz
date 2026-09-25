package com.echidna.studio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.echidna.studio.anim.Show;
import com.echidna.studio.track.CameraController;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.echidna.studio.track.FaceSignals;
import com.echidna.studio.track.TrackingHub;

import java.util.List;
import java.util.Locale;

/**
 * The studio window: the model on a full screen GL surface with a compact control panel on top.
 *
 * <p>The layout is built in code instead of XML so that the whole app stays in one small set of
 * files and the UI can be tuned together with the behaviour it drives. Everything the streamer needs
 * is one tap away: the five shows, the random motion, the camera mode, the chroma key backgrounds
 * for OBS, the microphone lip sync, the motion gallery with all 68 animations and the self check.</p>
 */
public final class MainActivity extends Activity implements ModelStage.Listener, EchidnaRenderer.StatusListener {

    private static final int REQUEST_PERMISSIONS = 4711;
    private static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private GLSurfaceView glView;
    private EchidnaRenderer renderer;
    private ModelStage stage;
    private TrackingHub hub;
    private AudioLevelMonitor audio;

    private TextView statusText;
    private TextView fpsText;
    private TextView hintText;
    private TextView permissionText;
    private LinearLayout bottomPanel;
    private LinearLayout showRow;
    private LinearLayout sliderRow;
    private LinearLayout galleryPanel;
    private Button cameraButton;
    private Button micButton;
    private Button galleryButton;
    private Button mirrorButton;

    private static final String PREFS = "echidna-studio";
    private static final long HIDE_UI_DELAY_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SelfTest selfTest;

    /** Каталог движений внутри assets и результат их фонового разбора. */
    private static final String MOTION_DIR = "live2d/Echidna/motions";

    private volatile String motionsLoadReport;
    private volatile boolean motionsCheckStarted;
    private boolean cameraMode;
    private boolean micEnabled;
    private boolean previewMirror = true;
    private String lastModelReport;
    private String lastFps = "";
    private BackgroundStyle backgroundCursor = BackgroundStyle.NIGHT;
    private SharedPreferences prefs;
    private LinearLayout topBar;
    private LinearLayout sideBar;
    private View hintView;
    private View errorPanel;
    private TextView errorText;
    private Button hideButton;
    private boolean uiHidden;
    private float pinchStartScale = 1.0f;
    private float pinchStartDistance;
    private float dragStartOffsetX;
    private float dragStartOffsetY;
    private float dragStartX;
    private float dragStartY;
    private boolean lookAtTouchWasActive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyImmersiveMode();

        stage = new ModelStage();
        renderer = new EchidnaRenderer(this, stage);
        renderer.setStatusListener(this);
        renderer.setStageListener(this);
        hub = new TrackingHub(this);
        hub.setSignalsListener(this::onSignals);
        hub.setPreviewListener(this::onPreview);
        audio = new AudioLevelMonitor(this);
        audio.setListener(level -> {
            if (stage != null) {
                stage.setMicLevel(level);
            }
        });

        try {
            buildUi();
            loadSettings();
        } catch (Throwable error) {
            // Even a broken interface must not be a silent death: show the reason in a plain window.
            EchidnaLog.e("APP", "интерфейс не построился", error);
            EchidnaLog.saveCrashReport(this, error);
            showFallbackScreen(error);
            return;
        }
        EchidnaLog.i("APP", "Echidna Studio запущено, версия " + versionName());
    }

    /**
     * Last resort screen: plain text, no layout tricks, so the user always learns what happened.
     */
    private void showFallbackScreen(Throwable error) {
        final java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        final TextView text = new TextView(this);
        text.setText("Echidna Studio не смогла построить интерфейс.\n\n"
                + writer.toString()
                + "\n\nПерезапусти приложение; если повторится — пришли мне этот текст.");
        text.setTextColor(0xFFF3ECFF);
        text.setTextSize(12);
        text.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(text);
        scroll.setBackgroundColor(0xFF120E20);
        setContentView(scroll);
    }

    // ------------------------------------------------------------------- UI

    private void buildUi() {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF120E20);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.setPreserveEGLContextOnPause(true);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // One finger steers the gaze like a cursor, two fingers frame the model: pinch to resize,
        // drag to move it. With the interface hidden a single tap brings it back.
        glView.setOnTouchListener(this::onSurfaceTouch);

        root.addView(buildTopBar());
        root.addView(buildSideBar());
        root.addView(buildBottomPanel());
        root.addView(buildHint());
        root.addView(buildPermissionBanner());
        root.addView(buildErrorPanel());

        setContentView(root);
    }

    private View buildTopBar() {
        final LinearLayout bar = new LinearLayout(this);
        topBar = bar;
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));
        bar.setBackground(rounded(0xB3140F22, 18));

        final TextView title = new TextView(this);
        title.setText("Echidna Studio");
        title.setTextColor(0xFFF3ECFF);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(title);

        statusText = new TextView(this);
        statusText.setText("загрузка модели…");
        statusText.setTextColor(0xFFB388FF);
        statusText.setTextSize(12);
        bar.addView(statusText);

        fpsText = new TextView(this);
        fpsText.setTextColor(0x88FFFFFF);
        fpsText.setTextSize(11);
        bar.addView(fpsText);

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        params.setMargins(dp(10), dp(10), dp(10), 0);
        bar.setLayoutParams(params);
        return bar;
    }

    private View buildSideBar() {
        final LinearLayout bar = new LinearLayout(this);
        sideBar = bar;
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        bar.addView(sideButton("\uD83C\uDFB2", "случайная анимация", v -> renderer.requestRandomMotion()));
        cameraButton = sideButton("\uD83C\uDFA5", "режим камеры", v -> toggleCameraMode());
        bar.addView(cameraButton);
        galleryButton = sideButton("\uD83C\uDFAC", "все анимации", v -> toggleGallery());
        bar.addView(galleryButton);
        bar.addView(sideButton("\uD83C\uDFA8", "фон для OBS", v -> cycleBackground()));
        mirrorButton = sideButton("\uD83E\uDE9E", "зеркалить", v -> toggleMirror());
        bar.addView(mirrorButton);
        micButton = sideButton("\uD83C\uDFA4", "микрофон", v -> toggleMic());
        bar.addView(micButton);
        bar.addView(sideButton("\uD83E\uDDEA", "самопроверка", v -> runSelfTest()));
        hideButton = sideButton("\uD83D\uDC41", "спрятать интерфейс", v -> toggleUi());
        bar.addView(hideButton);
        return bar;
    }

    private Button sideButton(String glyph, String description, View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(glyph);
        button.setTextSize(20);
        button.setContentDescription(description);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(rounded(0x99140F22, 24));
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(52));
        params.setMargins(0, dp(5), dp(10), dp(5));
        button.setLayoutParams(params);
        return button;
    }

    private View buildBottomPanel() {
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        bottomPanel.setBackground(rounded(0xB3140F22, 18));
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.setMargins(dp(10), 0, dp(10), dp(10));
        bottomPanel.setLayoutParams(params);

        showRow = new LinearLayout(this);
        showRow.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(showRow);
        bottomPanel.addView(scroller);
        populateShows();

        sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.VERTICAL);
        sliderRow.setVisibility(View.GONE);
        sliderRow.addView(slider("Размер модели", 40, 240, 100, value -> {
            renderer.setModelScale(value / 100.0f);
            saveSetting("scale", value);
        }));
        sliderRow.addView(slider("Смещение", -200, 200, 0, value -> {
            renderer.setModelOffsetY(value / 200.0f);
            saveSetting("offsetY", value);
        }));
        sliderRow.addView(slider("Громкость губ", 20, 300, 100, value -> {
            final float gain = value / 100.0f;
            audio.setGain(gain);
            stage.setMicGain(gain);
            saveSetting("gain", value);
        }));
        bottomPanel.addView(sliderRow);

        galleryPanel = new LinearLayout(this);
        galleryPanel.setOrientation(LinearLayout.VERTICAL);
        galleryPanel.setVisibility(View.GONE);
        galleryPanel.setPadding(0, dp(6), 0, 0);
        bottomPanel.addView(galleryPanel);

        return bottomPanel;
    }

    private void populateShows() {
        showRow.removeAllViews();
        final List<Show> shows = ModelStage.shows();
        for (int i = 0; i < shows.size(); i++) {
            final Show show = shows.get(i);
            final Button button = new Button(this);
            button.setText(show.emoji + " " + show.title);
            button.setAllCaps(false);
            button.setTextSize(13);
            button.setTextColor(0xFFF3ECFF);
            button.setBackground(rounded(0x66B388FF, 16));
            button.setPadding(dp(14), dp(8), dp(14), dp(8));
            button.setOnClickListener(v -> {
                renderer.requestShow(show.id);
                toast(show.emoji + " " + show.title + " — " + show.blurb);
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(8), 0);
            button.setLayoutParams(params);
            showRow.addView(button);
        }
    }

    private View slider(String title, int min, int max, int value, final ValueListener listener) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(0xAAFFFFFF);
        label.setTextSize(11);
        label.setWidth(dp(110));
        row.addView(label);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.onValue(progress + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(bar);
        return row;
    }

    private View buildHint() {
        hintText = new TextView(this);
        hintText.setText("Тяни по экрану — Ехидна смотрит за пальцем");
        hintText.setTextColor(0x77FFFFFF);
        hintText.setTextSize(11);
        hintText.setGravity(Gravity.CENTER);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(0, 0, 0, dp(150));
        hintText.setLayoutParams(params);
        hintView = hintText;
        handler.postDelayed(() -> hintText.setVisibility(View.GONE), 7000);
        return hintText;
    }

    private View buildPermissionBanner() {
        permissionText = new TextView(this);
        permissionText.setText("Нужна камера, чтобы Ехидна повторяла мимику. Нажми, чтобы разрешить.");
        permissionText.setTextColor(0xFF120E20);
        permissionText.setTextSize(13);
        permissionText.setGravity(Gravity.CENTER);
        permissionText.setPadding(dp(14), dp(12), dp(14), dp(12));
        permissionText.setBackground(rounded(0xFFB388FF, 16));
        permissionText.setVisibility(View.GONE);
        permissionText.setOnClickListener(v -> requestPermissions());
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        params.setMargins(dp(20), 0, dp(20), 0);
        permissionText.setLayoutParams(params);
        return permissionText;
    }

    // ------------------------------------------------------------ error screen

    /**
     * Shows a readable report instead of a silent disappearance or a red line nobody can act on.
     * The panel offers a retry, a copy of the diagnostics and a way to dismiss the message.
     */
    private void showError(String message, boolean withClear) {
        if (errorPanel == null) {
            return;
        }
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        if (withClear) {
            // The user has seen it; the next run starts clean unless it happens again.
            EchidnaLog.clearCrashReport(this);
        }
    }

    private void hideError() {
        if (errorPanel != null) {
            errorPanel.setVisibility(View.GONE);
        }
    }

    private View buildErrorPanel() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(rounded(0xF2140F22, 18));
        panel.setVisibility(View.GONE);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        params.setMargins(dp(12), dp(90), dp(12), dp(90));
        panel.setLayoutParams(params);
        panel.setOnClickListener(v -> hideError());

        final TextView title = new TextView(this);
        title.setText("Сообщение приложения");
        title.setTextColor(0xFFFF7A9A);
        title.setTextSize(15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(title);

        errorText = new TextView(this);
        errorText.setTextColor(0xFFF3ECFF);
        errorText.setTextSize(12);
        errorText.setPadding(0, dp(8), 0, dp(10));
        final android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(errorText);
        panel.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(panelButton("Повторить", v -> {
            hideError();
            renderer.requestModelReload();
            toast("Пробую загрузить модель снова");
        }));
        buttons.addView(panelButton("Скопировать лог", v -> copyDiagnostics()));
        buttons.addView(panelButton("Отправить", v -> shareDiagnostics()));
        buttons.addView(panelButton("Скрыть", v -> hideError()));
        panel.addView(buttons);
        errorPanel = panel;
        return panel;
    }

    private Button panelButton(String title, View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(0xFFF3ECFF);
        button.setBackground(rounded(0x66B388FF, 14));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void copyDiagnostics() {
        final android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Echidna Studio", EchidnaLog.diagnostics()));
        }
        toast("Лог скопирован в буфер обмена");
    }

    private void shareDiagnostics() {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Echidna Studio: отчёт");
        intent.putExtra(Intent.EXTRA_TEXT, EchidnaLog.diagnostics());
        try {
            startActivity(Intent.createChooser(intent, "Отправить отчёт"));
        } catch (Throwable error) {
            copyDiagnostics();
        }
    }

    // ----------------------------------------------------------- streaming mode

    /** Hides every control so the model can be captured full screen, e.g. by OBS. */
    private void toggleUi() {
        uiHidden = !uiHidden;
        final int visibility = uiHidden ? View.GONE : View.VISIBLE;
        topBar.setVisibility(visibility);
        sideBar.setVisibility(visibility);
        bottomPanel.setVisibility(visibility);
        if (hintView != null) {
            hintView.setVisibility(View.GONE);
        }
        hideError();
        toast(uiHidden ? "Интерфейс спрятан: нажми на экран, чтобы вернуть" : "Интерфейс вернулся");
    }

    // ---------------------------------------------------------------- settings

    private void loadSettings() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String background = prefs.getString("background", BackgroundStyle.NIGHT.name());
        try {
            backgroundCursor = BackgroundStyle.valueOf(background);
        } catch (IllegalArgumentException ignored) {
            backgroundCursor = BackgroundStyle.NIGHT;
        }
        previewMirror = prefs.getBoolean("mirror", true);
        final int scale = prefs.getInt("scale", 100);
        final int gain = prefs.getInt("gain", 100);
        final int offset = prefs.getInt("offsetY", 0);
        renderer.setBackground(backgroundCursor);
        renderer.setPreviewMirror(previewMirror);
        renderer.setModelScale(scale / 100.0f);
        renderer.setModelOffsetY(offset / 200.0f);
        audio.setGain(gain / 100.0f);
        stage.setMicGain(gain / 100.0f);
        stage.mapper().setMirrored(previewMirror);
        micEnabled = false;
    }

    private void saveSetting(String key, int value) {
        if (prefs != null) {
            prefs.edit().putInt(key, value).apply();
        }
    }

    private void saveSetting(String key, boolean value) {
        if (prefs != null) {
            prefs.edit().putBoolean(key, value).apply();
        }
    }

    private void saveSetting(String key, String value) {
        if (prefs != null) {
            prefs.edit().putString(key, value).apply();
        }
    }

    /** Touch handling of the model surface. Returns true when the event was consumed. */
    private boolean onSurfaceTouch(View view, MotionEvent event) {
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            // A second finger switches to framing; remember where everything started.
            pinchStartDistance = pointerDistance(event);
            pinchStartScale = renderer.modelScale();
            dragStartOffsetX = renderer.modelOffsetX();
            dragStartOffsetY = renderer.modelOffsetY();
            dragStartX = event.getX(0);
            dragStartY = event.getY(0);
            lookAtTouchWasActive = true;
            renderer.onTouch(0, 0, false);
            return true;
        }

        if (event.getPointerCount() >= 2) {
            final float distance = pointerDistance(event);
            if (pinchStartDistance > 10.0f && distance > 10.0f) {
                final float scale = clampScale(pinchStartScale * (distance / pinchStartDistance));
                renderer.setModelScale(scale);
                saveSetting("scale", Math.round(scale * 100.0f));
            }
            final float dx = (event.getX(0) - dragStartX) / Math.max(1.0f, view.getWidth());
            final float dy = (event.getY(0) - dragStartY) / Math.max(1.0f, view.getHeight());
            renderer.setModelOffsetX(dragStartOffsetX + dx * 1.6f);
            renderer.setModelOffsetY(dragStartOffsetY - dy * 1.6f);
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (uiHidden) {
                    return true;
                }
                final float x = (event.getX() / Math.max(1.0f, view.getWidth())) * 2.0f - 1.0f;
                final float y = (event.getY() / Math.max(1.0f, view.getHeight())) * 2.0f - 1.0f;
                renderer.onTouch(x, -y, true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (lookAtTouchWasActive) {
                    lookAtTouchWasActive = false;
                    saveSetting("offsetY", Math.round(renderer.modelOffsetY() * 200.0f));
                }
                if (uiHidden) {
                    toggleUi();
                    return true;
                }
                handler.postDelayed(() -> renderer.onTouch(0, 0, false), 900);
                return true;
            }
            default:
                return false;
        }
    }

    private static float pointerDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0.0f;
        }
        final float dx = event.getX(0) - event.getX(1);
        final float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clampScale(float value) {
        return Math.max(0.4f, Math.min(2.4f, value));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface ValueListener {
        void onValue(int value);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        reportPreviousCrash();
    }

    /** Shows what killed the previous run, if anything did. */
    private void reportPreviousCrash() {
        final String report = EchidnaApplication.lastCrashReport(this);
        if (report == null || report.trim().isEmpty()) {
            return;
        }
        final String readable = report.length() > 1500 ? report.substring(0, 1500) + "…" : report;
        showError("прошлый запуск завершился ошибкой.\n\n" + readable, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        if (cameraMode) {
            startCamera();
        }
        if (micEnabled) {
            audio.start();
            stage.setMicEnabled(true);
        }
        handleIntentExtras(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.stop();
        audio.stop();
        glView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        hub.release();
        audio.stop();
        if (glView != null) {
            // Only the model is dropped here. The framework itself stays up for the whole process:
            // disposing it would throw away the id manager the model keeps using when the user
            // comes back to the app.
            glView.queueEvent(renderer::releaseModel);
            glView.onPause();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    private void applyImmersiveMode() {
        final View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------- commands

    /** Handles the extras the automated check and direct shortcuts use. */
    private void handleIntentExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        final String show = intent.getStringExtra("echidna_show");
        if (show != null) {
            renderer.requestShow(show);
        }
        final String motion = intent.getStringExtra("echidna_motion");
        if (motion != null) {
            renderer.requestMotion(motion);
        }
        if (intent.getBooleanExtra("echidna_camera", false)) {
            handler.postDelayed(this::enableCameraMode, 700);
        }
        if (intent.getBooleanExtra("echidna_gallery", false)) {
            handler.postDelayed(this::showGallery, 500);
        }
        final String background = intent.getStringExtra("echidna_background");
        if (background != null) {
            try {
                final BackgroundStyle style = BackgroundStyle.valueOf(background);
                renderer.setBackground(style);
                toast("Фон: " + style.title);
            } catch (IllegalArgumentException ignored) {
                // unknown name, keep the current background
            }
        }
        if (intent.getBooleanExtra("echidna_selftest", false)) {
            final boolean withCamera = intent.getBooleanExtra("echidna_selftest_camera", false);
            handler.postDelayed(() -> runSelfTest(withCamera), 900);
        }
        if (intent.getBooleanExtra("echidna_autoplay", false)) {
            startAutoplay();
        }
        intent.removeExtra("echidna_show");
        intent.removeExtra("echidna_motion");
        intent.removeExtra("echidna_camera");
        intent.removeExtra("echidna_gallery");
        intent.removeExtra("echidna_background");
        intent.removeExtra("echidna_selftest");
        intent.removeExtra("echidna_selftest_camera");
        intent.removeExtra("echidna_autoplay");
    }

    private void startAutoplay() {
        final List<Show> shows = ModelStage.shows();
        final int[] index = {0};
        final Runnable next = new Runnable() {
            @Override
            public void run() {
                final Show show = shows.get(index[0] % shows.size());
                renderer.requestShow(show.id);
                index[0]++;
                handler.postDelayed(this, (long) (show.duration * 1000) + 200);
            }
        };
        handler.post(next);
        EchidnaLog.i("APP", "автовоспроизведение шоу включено");
    }

    private void toggleCameraMode() {
        if (cameraMode) {
            disableCameraMode();
        } else {
            enableCameraMode();
        }
    }

    private void enableCameraMode() {
        if (!CameraController.hasPermission(this)) {
            permissionText.setVisibility(View.VISIBLE);
            requestPermissions();
            return;
        }
        cameraMode = true;
        // Commands travel through the renderer so that the GL thread is the only one touching the
        // stage: the interface must never resize or re-target the scene behind its back.
        renderer.requestCamera();
        renderer.setPreviewEnabled(true);
        renderer.setPreviewMirror(previewMirror);
        glView.setZOrderOnTop(false);
        if (!startCamera()) {
            cameraMode = false;
            renderer.setPreviewEnabled(false);
            renderer.requestIdle();
            return;
        }
        if (!micEnabled && AudioLevelMonitor.hasPermission(this)) {
            micEnabled = audio.start();
            stage.setMicEnabled(micEnabled);
        }
        cameraButton.setText("\u23F9");
        sliderRow.setVisibility(View.VISIBLE);
        micButton.setText(micEnabled ? "\uD83D\uDD34" : "\uD83C\uDFA4");
        toast("Режим камеры: Ехидна повторяет твою мимику");
        EchidnaLog.i("APP", "режим камеры включён, трекер " + hub.trackerName());
    }

    private void disableCameraMode() {
        cameraMode = false;
        hub.stop();
        renderer.setPreviewEnabled(false);
        renderer.requestIdle();
        cameraButton.setText("\uD83C\uDFA5");
        sliderRow.setVisibility(View.GONE);
        toast("Режим камеры выключен");
    }

    private boolean startCamera() {
        final boolean started = hub.start(false);
        if (!started) {
            toast("Камера не запустилась: " + hub.camera().lastError());
            permissionText.setVisibility(CameraController.hasPermission(this) ? View.GONE : View.VISIBLE);
        } else {
            permissionText.setVisibility(View.GONE);
        }
        return started;
    }

    private void toggleMic() {
        if (micEnabled) {
            micEnabled = false;
            audio.stop();
            stage.setMicEnabled(false);
            stage.setMicLevel(0.0f);
            micButton.setText("\uD83C\uDFA4");
            toast("Липсинк по микрофону выключен");
            return;
        }
        if (!AudioLevelMonitor.hasPermission(this)) {
            toast("Нет разрешения на микрофон");
            requestPermissions();
            return;
        }
        micEnabled = audio.start();
        stage.setMicEnabled(micEnabled);
        micButton.setText(micEnabled ? "\uD83D\uDD34" : "\uD83C\uDFA4");
        toast(micEnabled ? "Липсинк по микрофону включён" : "Микрофон недоступен");
    }

    private void cycleBackground() {
        backgroundCursor = backgroundCursor.next();
        final BackgroundStyle style = backgroundCursor;
        renderer.setBackground(style);
        saveSetting("background", style.name());
        toast(style.isChromaKey()
                ? "Фон: " + style.title + " — для OBS (фильтр «Хромакей»)"
                : "Фон: " + style.title);
    }

    private void toggleMirror() {
        previewMirror = !previewMirror;
        renderer.setPreviewMirror(previewMirror);
        stage.mapper().setMirrored(previewMirror);
        saveSetting("mirror", previewMirror);
        mirrorButton.setText(previewMirror ? "\uD83E\uDE9E" : "\uD83D\uDD04");
        toast(previewMirror ? "Зеркально, как в зеркале" : "Без зеркала");
    }

    private void toggleGallery() {
        if (galleryPanel.getVisibility() == View.VISIBLE) {
            galleryPanel.setVisibility(View.GONE);
            galleryButton.setText("\uD83C\uDFAC");
        } else {
            showGallery();
        }
    }

    private void showGallery() {
        if (galleryPanel.getChildCount() == 0) {
            populateGallery();
        }
        galleryPanel.setVisibility(View.VISIBLE);
        galleryButton.setText("\u2716");
    }

    private void populateGallery() {
        final List<String> motions = stage.knownMotions();
        final List<String[]> groups = ModelStage.motionGroups(motions);
        for (int g = 0; g < groups.size(); g++) {
            final String[] group = groups.get(g);
            final TextView title = new TextView(this);
            title.setText(group[0]);
            title.setTextColor(0xFFB388FF);
            title.setTextSize(12);
            title.setPadding(0, dp(6), 0, dp(4));
            galleryPanel.addView(title);

            final LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            final HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(wrap);
            for (int i = 1; i < group.length; i++) {
                final String name = group[i];
                final Button chip = new Button(this);
                chip.setText(name.replace('_', ' '));
                chip.setAllCaps(false);
                chip.setTextSize(11);
                chip.setTextColor(0xFFF3ECFF);
                chip.setBackground(rounded(0x44FFFFFF, 14));
                chip.setPadding(dp(10), dp(4), dp(10), dp(4));
                chip.setOnClickListener(v -> {
                    renderer.requestMotion(name);
                    toast(name);
                });
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, 0, dp(6), 0);
                chip.setLayoutParams(params);
                wrap.addView(chip);
            }
            galleryPanel.addView(scroll);
        }
        EchidnaLog.i("APP", "галерея: " + motions.size() + " анимаций");
    }

    private void runSelfTest() {
        runSelfTest(false);
    }

    /**
     * Parses every motion file of the model with the real Live2D motion parser.
     *
     * This is the check that would have caught the bug that once left the screen empty: the counters
     * of a motion used to be smaller than its curves, and the engine then threw while loading a
     * motion. Runs in a background thread, the result is read by the self test.
     */
    private void parseAllMotions() {
        final StringBuilder broken = new StringBuilder();
        int total = 0;
        int failed = 0;
        try {
            final String[] files = getAssets().list(MOTION_DIR);
            if (files == null) {
                motionsLoadReport = "каталог движений пуст: " + MOTION_DIR;
                return;
            }
            java.util.Arrays.sort(files);
            for (String file : files) {
                if (!file.endsWith(".motion3.json")) {
                    continue;
                }
                total++;
                try {
                    final byte[] data = readAsset(MOTION_DIR + "/" + file);
                    CubismMotion.create(data);
                } catch (Throwable error) {
                    failed++;
                    if (failed <= 6) {
                        broken.append(broken.length() == 0 ? "" : ", ")
                              .append(file).append(" (").append(error.getClass().getSimpleName()).append(')');
                    }
                }
            }
        } catch (Throwable error) {
            motionsLoadReport = "проверка движений сорвалась: " + error;
            return;
        }
        final String report = failed == 0
            ? "движения прочитаны: " + total + "/" + total + ", ошибок 0"
            : "движения прочитаны: " + (total - failed) + "/" + total + ", ошибок " + failed + ": " + broken;
        motionsLoadReport = report;
        EchidnaLog.i("MOTION", report);
    }

    private byte[] readAsset(String path) throws java.io.IOException {
        try (java.io.InputStream stream = getAssets().open(path)) {
            final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private void runSelfTest(boolean withCamera) {
        toast("Самопроверка пошла, детали в логе");
        if (selfTest == null) {
            selfTest = new SelfTest(this, new SelfTest.Callbacks() {
                @Override
                public String modelReport() {
                    return lastModelReport;
                }

                @Override
                public void startShow(String showId) {
                    renderer.requestShow(showId);
                }

                @Override
                public void playMotion(String name) {
                    renderer.requestMotion(name);
                }

                @Override
                public void enterCameraMode(boolean realCamera) {
                    if (realCamera) {
                        MainActivity.this.enterCameraModeForSelfTest();
                    } else {
                        hub.stop();
                        renderer.setPreviewEnabled(false);
                        renderer.requestCamera();
                        hub.start(true);
                    }
                }

                @Override
                public void leaveCameraMode() {
                    hub.stop();
                    renderer.setPreviewEnabled(false);
                    renderer.requestIdle();
                    cameraMode = false;
                    cameraButton.setText("\uD83C\uDFA5");
                }

                @Override
                public List<String> motions() {
                    return stage.knownMotions();
                }

                @Override
                public String stageReport() {
                    return stage.mode() + " / " + String.valueOf(stage.currentMotionName());
                }

                @Override
                public String signalsReport() {
                    return hub.signalsJson();
                }

                @Override
                public String trackerReport() {
                    return hub.report();
                }

                @Override
                public String cameraReport() {
                    return hub.camera().lastError();
                }

                @Override
                public String parameterReport() {
                    return renderer.parameterSummary();
                }

                @Override
                public String motionsLoadReport() {
                    final String ready = motionsLoadReport;
                    if (ready != null) {
                        return ready;
                    }
                    if (!motionsCheckStarted) {
                        motionsCheckStarted = true;
                        final Thread worker = new Thread(MainActivity.this::parseAllMotions, "motion-check");
                        worker.setDaemon(true);
                        worker.start();
                    }
                    return null;
                }
            });
        }
        selfTest.start(withCamera);
    }

    private void enterCameraModeForSelfTest() {
        if (!CameraController.hasPermission(this)) {
            requestPermissions();
            return;
        }
        cameraMode = true;
        renderer.requestCamera();
        renderer.setPreviewEnabled(true);
        startCamera();
        cameraButton.setText("\u23F9");
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean cameraGranted = false;
        boolean audioGranted = false;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (Manifest.permission.CAMERA.equals(permissions[i])) {
                cameraGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            } else if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                audioGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        permissionText.setVisibility(cameraGranted ? View.GONE : View.VISIBLE);
        if (cameraGranted && cameraMode) {
            startCamera();
        }
        if (audioGranted && micEnabled) {
            audio.start();
            stage.setMicEnabled(true);
        }
        EchidnaLog.i("APP", "разрешения: камера=" + cameraGranted + ", микрофон=" + audioGranted);
    }

    // ------------------------------------------------------- renderer bridge

    private void onSignals(FaceSignals signals) {
        stage.setSignals(signals);
    }

    private void onPreview(Bitmap frame) {
        renderer.setPreviewFrame(frame);
    }

    @Override
    public void onStageState(String modeTitle, String detail, String currentMotion) {
        runOnUiThread(() -> {
            final String motion = currentMotion == null ? "" : " · " + currentMotion;
            statusText.setText(modeTitle + " · " + detail + motion);
        });
    }

    @Override
    public void onModelReady(String report) {
        lastModelReport = report;
        runOnUiThread(() -> {
            statusText.setText("модель готова · " + report);
            statusText.setTextColor(0xFFB388FF);
            hideError();
        });
        EchidnaLog.i("APP", "APPREADY " + Json.object()
                .put("model", report)
                .put("motions", stage.knownMotions().size())
                .put("shows", ModelStage.shows().size())
                .put("version", versionName())
                .toString());
    }

    @Override
    public void onModelFailed(String message) {
        lastModelReport = null;
        runOnUiThread(() -> {
            statusText.setText("модель не загрузилась: " + message);
            statusText.setTextColor(Color.RED);
            showError("Не удалось показать Ехидну.\n\n" + message
                    + "\n\nМожно попробовать ещё раз или отправить мне лог кнопкой ниже.", false);
        });
        EchidnaLog.e("APP", "модель не загрузилась: " + message);
    }

    @Override
    public void onFps(float fps) {
        lastFps = String.format(Locale.US, "%.1f fps", fps);
        runOnUiThread(() -> fpsText.setText(lastFps + " · " + hub.trackerName()
                + (cameraMode ? " · кадров " + hub.camera().frameCount() : "")));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }
}
