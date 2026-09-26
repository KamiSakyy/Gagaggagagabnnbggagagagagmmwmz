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
import android.widget.ScrollView;
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
 * for OBS, the motion gallery with all the animations and the self check.</p>
 *
 * <p>Everything the character does comes from the camera: the face, the body and the hands. There is
 * no microphone - the mouth of the model is driven by the mouth of the person.</p>
 */
public final class MainActivity extends Activity implements ModelStage.Listener, EchidnaRenderer.StatusListener {

    private static final int REQUEST_PERMISSIONS = 4711;
    /**
     * Только камера.
     *
     * <p>Микрофон приложению больше не нужен: движение модели идёт исключительно от камеры - лицо,
     * тело и кисти рук. Ничего не слушается и никуда не отправляется.</p>
     */
    private static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA
    };

    private GLSurfaceView glView;
    private EchidnaRenderer renderer;
    private ModelStage stage;
    private TrackingHub hub;

    private TextView statusText;
    private TextView fpsText;
    private TextView handText;
    /** Строка эмоции: видно, что приложение понимает выражение лица. */
    private TextView moodText;
    private TextView hintText;
    private TextView permissionText;
    private LinearLayout bottomPanel;
    private LinearLayout showRow;
    private LinearLayout sliderRow;
    private LinearLayout galleryPanel;
    private Button cameraButton;
    private Button galleryButton;
    private Button mirrorButton;
    private Button moreButton;
    private Button flipCameraButton;
    private FrameLayout overlayPanel;
    private ScrollView overlayScroll;
    private TextView modelSummary;
    private LinearLayout actionBar;
    /** Полоса закрытия: показывается вместо панели действий, пока открыто окно персонажей. */
    private LinearLayout closeBar;

    /** Цвет подписей на тёмной панели. */
    private static final int COLOR_TEXT = 0xFFF3ECFF;

    private static final String PREFS = "echidna-studio";
    private static final long HIDE_UI_DELAY_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SelfTest selfTest;

    private volatile String motionsLoadReport;
    private volatile boolean motionsCheckStarted;
    private boolean cameraMode;
    private boolean previewMirror = true;
    private String lastModelReport;
    private String lastFps = "";
    private BackgroundStyle backgroundCursor = BackgroundStyle.NIGHT;
    private SharedPreferences prefs;
    /** Окно персонажей: список всех моделей, настройки камеры, выражения и ползунки. */
    private LinearLayout modelPanel;
    private LinearLayout modelRow;
    private Button modelsButton;
    private Button previewWindowButton;
    private Button calibrateButton;
    private Button turnButton;
    private Button expressionsButton;
    private LinearLayout expressionRow;
    private String modelId = ModelCatalog.DEFAULT_ID;
    private boolean previewFullScreen;
    private boolean turnInverted;
    /** Направление каналов рук: у разных ригов оно разное, поэтому переключается кнопкой. */
    private boolean armInverted;
    /** Большая цифра жеста: сколько пальцев показывает человек. */
    private TextView gestureBadge;
    private boolean expressionsVisible;
    private boolean modelsPanelVisible;
    private LinearLayout topBar;
    private View hintView;
    private View errorPanel;
    private TextView errorText;
    private Button hideButton;
    private Button armButton;
    private boolean uiHidden;
    /** Последний показанный жест, чтобы не трогать интерфейс на каждом кадре. */
    private volatile int shownGesture = -1;
    /** Какая эмоция уже показана плашкой: по смене показывается следующая. */
    private volatile int shownEmotion = com.echidna.studio.track.EmotionDetector.NEUTRAL;
    private long gestureShownAt;
    private long lastCameraRestartAt;
    private final Runnable hideGestureBadge = () -> {
        if (gestureBadge != null
                && android.os.SystemClock.elapsedRealtime() - gestureShownAt > 1500) {
            gestureBadge.setVisibility(View.GONE);
        }
    };
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
        // Модели распознавания поднимаются заранее, пока человек выбирает персонажа: тогда
        // включение камеры мгновенное. Разрешение к этому моменту обычно уже выдано.
        if (CameraController.hasPermission(this)) {
            hub.warmUp();
        }
        hub.setPreviewListener(this::onPreview);

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
        handler.postDelayed(this::showFirstRunHint, 1200);
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
        root.addView(buildBottomPanel());
        // Окно персонажей лежит под панелью действий, чтобы нижние кнопки остались доступными.
        root.addView(buildOverlay());
        root.addView(buildActionBar());
        root.addView(buildCloseBar());
        root.addView(buildHint());
        root.addView(buildPermissionBanner());
        root.addView(buildErrorPanel());
        root.addView(buildGestureBadge());

        setContentView(root);
    }

    /**
     * Большая плашка с числом пальцев.
     *
     * <p>У ригов Live2D кисть - одна картинка, отдельных пальцев в модели нет, поэтому показать
     * "четыре пальца" самой моделью нельзя. Зато видно и то, что модель nod-ит четыре раза, и то,
     * что на экране горит цифра: зритель понимает жест сразу. Полоса идёт от края до края, как и
     * всё остальное в интерфейсе.</p>
     */
    private View buildGestureBadge() {
        gestureBadge = new TextView(this);
        gestureBadge.setTextColor(0xFF120E20);
        gestureBadge.setTextSize(34);
        gestureBadge.setTypeface(gestureBadge.getTypeface(), android.graphics.Typeface.BOLD);
        gestureBadge.setGravity(android.view.Gravity.CENTER);
        gestureBadge.setBackgroundColor(0xEEFFD54F);
        gestureBadge.setPadding(dp(10), dp(10), dp(10), dp(10));
        gestureBadge.setVisibility(View.GONE);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.TOP;
        params.topMargin = dp(96);
        gestureBadge.setLayoutParams(params);
        return gestureBadge;
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
        statusText.setText("Загружаю " + ModelCatalog.byId(modelId).title + "…");
        statusText.setTextColor(0xFFB388FF);
        statusText.setTextSize(12);
        // Человек первым делом трогает то, что видит: нажатие на имя модели открывает их выбор.
        statusText.setPadding(0, dp(2), 0, dp(2));
        statusText.setOnClickListener(v -> showOverlay(true));
        bar.addView(statusText);

        fpsText = new TextView(this);
        fpsText.setTextColor(0x88FFFFFF);
        fpsText.setTextSize(11);
        bar.addView(fpsText);

        // Живая строка про руку: по ней сразу видно, видит ли камера кисть и насколько она поднята.
        // Без неё невозможно понять, почему модель не двигает рукой - трекер её не видит или у
        // модели нет каналов рук.
        handText = new TextView(this);
        handText.setTextColor(0x99FFFFFF);
        handText.setTextSize(11);
        handText.setText("рука: не видна");
        bar.addView(handText);

        // Строка эмоции: «радость 82%», «удивление 51%», «спокойствие». Пока распознавание
        // поднимается, здесь же видно, что оно загружается, - а не пустой экран.
        moodText = new TextView(this);
        moodText.setTextColor(0x99FFFFFF);
        moodText.setTextSize(11);
        moodText.setText("эмоция: —");
        bar.addView(moodText);

        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP;
        params.setMargins(dp(10), dp(10), dp(10), 0);
        bar.setLayoutParams(params);
        return bar;
    }

    /**
     * Нижняя панель действий: иконки из набора Material и подписи под ними.
     *
     * <p>Раньше управление висело сбоку рядом эмодзи без подписей, и человек не понимал, где выбрать
     * персонажа: кнопки были одинаковыми, а список моделей прятался внутри одной из них. Теперь
     * четыре подписанные кнопки внизу — «Персонажи», «Камера», «Анимации», «Ещё».</p>
     */
    private View buildActionBar() {
        final LinearLayout bar = new LinearLayout(this);
        actionBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        bar.setBackgroundColor(0xF2100C1C);
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        modelsButton = navItem(R.drawable.ic_models, "Персонажи", v -> showOverlay(true));
        bar.addView(modelsButton);
        cameraButton = navItem(R.drawable.ic_camera, "Камера", v -> toggleCameraMode());
        bar.addView(cameraButton);
        galleryButton = navItem(R.drawable.ic_motions, "Анимации", v -> toggleGallery());
        bar.addView(galleryButton);
        moreButton = navItem(R.drawable.ic_more, "Ещё", v -> showOverlay(false));
        bar.addView(moreButton);
        return bar;
    }

    /**
     * Полоса с кнопкой «Закрыть» на всю ширину.
     *
     * <p>Крестик в углу окна человек не находил: он маленький и сливается с тёмным фоном. Пока окно
     * персонажей открыто, вместо панели действий показывается эта полоса — кнопка во всю ширину
     * экрана, с иконкой и подписью, её невозможно не заметить.</p>
     */
    private View buildCloseBar() {
        closeBar = new LinearLayout(this);
        closeBar.setOrientation(LinearLayout.HORIZONTAL);
        closeBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        closeBar.setBackgroundColor(0xF2100C1C);
        closeBar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        closeBar.setVisibility(View.GONE);

        final Button close = new Button(this);
        close.setText("Закрыть");
        close.setTextSize(15);
        close.setAllCaps(false);
        close.setTextColor(0xFF231436);
        close.setBackground(rounded(0xFFB388FF, 20));
        close.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_close, 0, 0, 0);
        close.setCompoundDrawablePadding(dp(8));
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> hideOverlay());
        close.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        closeBar.addView(close);
        return closeBar;
    }

    /** Одна кнопка нижней панели: иконка сверху, подпись снизу. */
    private Button navItem(int iconRes, String label, View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), dp(8), dp(2), dp(8));
        button.setBackground(rounded(0x00000000, 14));
        button.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
        button.setCompoundDrawablePadding(dp(3));
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    /** Подсветка выбранной кнопки: сразу видно, какой режим включён. */
    private void highlight(View item, boolean on) {
        if (item != null) {
            item.setBackground(rounded(on ? 0xCC5E35B1 : 0x00000000, 14));
        }
    }

    /** Кнопка с иконкой Material и подписью — для настроек внутри окна персонажей. */
    private Button labeledButton(int iconRes, String label, View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(0xFFD8CCFF);
        button.setBackground(rounded(0x99140F22, 16));
        button.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(6));
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        button.setLayoutParams(params);
        return button;
    }

    private View buildBottomPanel() {
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomPanel.setBackground(rounded(0xB3140F22, 18));
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        // Панель шоу стоит над нижней панелью действий, чтобы они не закрывали друг друга.
        params.setMargins(dp(10), 0, dp(10), dp(66));
        bottomPanel.setLayoutParams(params);

        showRow = new LinearLayout(this);
        showRow.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(showRow);
        bottomPanel.addView(scroller);
        populateShows();

        galleryPanel = new LinearLayout(this);
        galleryPanel.setOrientation(LinearLayout.VERTICAL);
        galleryPanel.setVisibility(View.GONE);
        galleryPanel.setPadding(0, dp(6), 0, 0);
        bottomPanel.addView(galleryPanel);

        return bottomPanel;
    }


    // ------------------------------------------------------- окно персонажей

    /** Перед построением окна обновляет и список моделей, и выражения лица. */
    private void buildModelPanel() {
        populateModels();
        populateExpressions();
    }

    /**
     * Окно персонажей: полный список моделей, настройки камеры, выражения и ползунки.
     *
     * <p>Это главное окно приложения, поэтому оно прокручивается целиком и лежит поверх модели:
     * список моделей больше не спрятан в горизонтальную ленту, где видно только первую.</p>
     */
    private View buildOverlay() {
        overlayPanel = new FrameLayout(this);
        overlayPanel.setBackgroundColor(0xF2100C1C);
        overlayPanel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayPanel.setVisibility(View.GONE);
        overlayPanel.setClickable(true);

        overlayScroll = new ScrollView(this);
        overlayScroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        overlayPanel.addView(overlayScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        modelPanel = new LinearLayout(this);
        modelPanel.setOrientation(LinearLayout.VERTICAL);
        overlayScroll.addView(modelPanel);

        // Заголовок во всю ширину: слева название, справа крупная кнопка закрытия, которую видно
        // всегда, а не маленький крестик без подписи.
        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(4));
        final TextView title = new TextView(this);
        title.setText("Персонажи");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        final Button closeTop = new Button(this);
        closeTop.setText("Закрыть");
        closeTop.setTextSize(13);
        closeTop.setAllCaps(false);
        closeTop.setTextColor(0xFF231436);
        closeTop.setBackground(rounded(0xFFB388FF, 18));
        closeTop.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_close, 0, 0, 0);
        closeTop.setCompoundDrawablePadding(dp(6));
        closeTop.setOnClickListener(v -> hideOverlay());
        closeTop.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        header.addView(closeTop);
        modelPanel.addView(header);

        modelSummary = new TextView(this);
        modelSummary.setTextColor(0xFFB388FF);
        modelSummary.setTextSize(12);
        modelSummary.setPadding(0, dp(2), 0, dp(8));
        modelPanel.addView(modelSummary);

        modelPanel.addView(sectionTitle("Выбери персонажа"));
        modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.VERTICAL);
        modelPanel.addView(modelRow);

        modelPanel.addView(sectionTitle("Камера"));
        previewWindowButton = labeledButton(R.drawable.ic_eye, "камера: окошко в углу",
                v -> togglePreviewWindow());
        previewWindowButton.setVisibility(View.GONE);
        calibrateButton = labeledButton(R.drawable.ic_check, "калибровка", v -> calibrate());
        calibrateButton.setVisibility(View.GONE);
        turnButton = labeledButton(R.drawable.ic_flip, "поворот: обычно",
                v -> toggleTurnDirection());
        turnButton.setVisibility(View.GONE);
        flipCameraButton = labeledButton(R.drawable.ic_camera, "камера: 0\u00b0",
                v -> flipCamera());
        flipCameraButton.setVisibility(View.GONE);
        final LinearLayout cameraRow = new LinearLayout(this);
        cameraRow.setOrientation(LinearLayout.HORIZONTAL);
        cameraRow.addView(previewWindowButton);
        cameraRow.addView(calibrateButton);
        cameraRow.addView(turnButton);
        cameraRow.addView(flipCameraButton);
        final HorizontalScrollView cameraScroller = new HorizontalScrollView(this);
        cameraScroller.setHorizontalScrollBarEnabled(false);
        cameraScroller.addView(cameraRow);
        modelPanel.addView(cameraScroller);

        modelPanel.addView(sectionTitle("Выражения лица"));
        expressionRow = new LinearLayout(this);
        expressionRow.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView expressionScroller = new HorizontalScrollView(this);
        expressionScroller.setHorizontalScrollBarEnabled(false);
        expressionScroller.addView(expressionRow);
        modelPanel.addView(expressionScroller);

        modelPanel.addView(sectionTitle("Ещё"));
        final LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout settingsLine1 = new LinearLayout(this);
        settingsLine1.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout settingsLine2 = new LinearLayout(this);
        settingsLine2.setOrientation(LinearLayout.HORIZONTAL);
        settingsLine1.addView(labeledButton(R.drawable.ic_palette, "фон", v -> cycleBackground()));
        settingsLine1.addView(labeledButton(R.drawable.ic_flip, "случайная анимация",
                v -> renderer.requestRandomMotion()));
        mirrorButton = labeledButton(R.drawable.ic_flip, "зеркалить", v -> toggleMirror());
        settingsLine1.addView(mirrorButton);
        settingsLine2.addView(labeledButton(R.drawable.ic_check, "самопроверка",
                v -> runSelfTest()));
        armButton = labeledButton(R.drawable.ic_hand, "руки: вверх", v -> toggleArmDirection());
        settingsLine2.addView(armButton);
        hideButton = labeledButton(R.drawable.ic_eye, "спрятать интерфейс", v -> toggleUi());
        settingsLine2.addView(hideButton);
        settingsRow.addView(settingsLine1);
        settingsRow.addView(settingsLine2);
        modelPanel.addView(settingsRow);

        sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.VERTICAL);
        sliderRow.setPadding(0, dp(8), 0, 0);
        sliderRow.addView(slider("Размер модели", 40, 240, 100, value -> {
            renderer.setModelScale(value / 100.0f);
            saveSetting("scale", value);
        }));
        sliderRow.addView(slider("Смещение", -200, 200, 0, value -> {
            renderer.setModelOffsetY(value / 200.0f);
            saveSetting("offsetY", value);
        }));
        modelPanel.addView(sliderRow);

        final TextView version = new TextView(this);
        version.setTextColor(0x88FFFFFF);
        version.setTextSize(11);
        version.setText("версия " + appVersion() + " · только камера, микрофон не используется");
        version.setPadding(0, dp(10), 0, dp(4));
        modelPanel.addView(version);

        return overlayPanel;
    }

    /** Версия приложения: по ней видно, встало ли обновление поверх прошлой сборки. */
    private String appVersion() {
        try {
            final android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable error) {
            return "неизвестна";
        }
    }

    private TextView sectionTitle(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFB388FF);
        view.setTextSize(13);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    /**
     * Открывает окно персонажей.
     *
     * @param modelsFirst true — показать список моделей сверху; false — прокрутить к настройкам
     *                    (на это ведёт кнопка «Ещё»)
     */
    private void showOverlay(boolean modelsFirst) {
        modelsPanelVisible = true;
        buildModelPanel();
        if (overlayPanel != null) {
            overlayPanel.setVisibility(View.VISIBLE);
        }
        highlight(modelsButton, modelsFirst);
        highlight(moreButton, !modelsFirst);
        // Нижняя панель уступает место кнопке закрытия: она во всю ширину и всегда на виду.
        if (actionBar != null) {
            actionBar.setVisibility(View.GONE);
        }
        if (closeBar != null) {
            closeBar.setVisibility(View.VISIBLE);
        }
        if (overlayScroll != null) {
            overlayScroll.post(() -> overlayScroll.fullScroll(
                    modelsFirst ? ScrollView.FOCUS_UP : ScrollView.FOCUS_DOWN));
        }
    }

    private void hideOverlay() {
        modelsPanelVisible = false;
        if (overlayPanel != null) {
            overlayPanel.setVisibility(View.GONE);
        }
        highlight(modelsButton, false);
        highlight(moreButton, false);
        if (actionBar != null) {
            actionBar.setVisibility(uiHidden ? View.GONE : View.VISIBLE);
        }
        if (closeBar != null) {
            closeBar.setVisibility(View.GONE);
        }
    }

    /** Список всех моделей сборки: строки, а не лента, где видно только первую кнопку. */
    private void populateModels() {
        final List<ModelCatalog.ModelSpec> all = ModelCatalog.available(getAssets());
        if (modelRow != null) {
            modelRow.removeAllViews();
            for (int i = 0; i < all.size(); i++) {
                final ModelCatalog.ModelSpec spec = all.get(i);
                final boolean active = spec.id.equals(modelId);
                final LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                // Края до края: подсветка выбранной строки тянется по всей ширине экрана.
                row.setPadding(dp(6), dp(10), dp(6), dp(10));
                row.setBackground(rounded(active ? 0xCC5E35B1 : 0x33FFFFFF, 0));
                row.setOnClickListener(v -> selectModel(spec.id));

                final TextView emoji = new TextView(this);
                emoji.setText(spec.emoji);
                emoji.setTextSize(24);
                emoji.setPadding(0, 0, dp(10), 0);
                row.addView(emoji);

                final LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                final TextView name = new TextView(this);
                name.setText(spec.title);
                name.setTextColor(COLOR_TEXT);
                name.setTextSize(15);
                texts.addView(name);
                final TextView blurb = new TextView(this);
                blurb.setText(spec.blurb);
                blurb.setTextColor(0xFFB9AED6);
                blurb.setTextSize(11);
                texts.addView(blurb);
                row.addView(texts);

                if (active) {
                    final TextView mark = new TextView(this);
                    mark.setText("\u2713 выбрано");
                    mark.setTextColor(COLOR_TEXT);
                    mark.setTextSize(11);
                    row.addView(mark);
                }

                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, dp(3), 0, dp(3));
                row.setLayoutParams(params);
                modelRow.addView(row);
            }
        }

        final ModelCatalog.ModelSpec current = ModelCatalog.byId(modelId);
        if (modelSummary != null) {
            modelSummary.setText("На экране: " + current.emoji + " " + current.title
                    + " \u00b7 в APK " + all.size() + " из " + ModelCatalog.all().size()
                    + " моделей");
        }
        if (previewWindowButton != null) {
            previewWindowButton.setText(previewFullScreen
                    ? "камера: во весь экран" : "камера: окошко в углу");
        }
        if (calibrateButton != null) {
            calibrateButton.setText("калибровка");
        }
        updateTurnButtonText();
        updateFlipButtonText();
    }

    private void selectModel(String id) {
        modelId = id;
        saveSetting(ModelCatalog.PREF_KEY, id);
        renderer.requestModel(id);
        populateModels();
        populateExpressions();
        toast(ModelCatalog.byId(id).title + ": " + ModelCatalog.byId(id).blurb);
        EchidnaLog.i("APP", "выбрана модель " + id);
        // Окно остаётся открытым: видно, что модель сменилась и какая теперь выбрана.
    }

    /** Выражения текущей модели: у Нахиды их тринадцать, у объёмного персонажа восемнадцать. */
    private void populateExpressions() {
        if (expressionRow == null) {
            return;
        }
        expressionRow.removeAllViews();
        final List<String> names = stage.knownExpressions();
        expressionRow.setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            expressionRow.addView(labeledButton(0, name, v -> {
                stage.playExpression(name);
                toast("Выражение: " + name);
            }));
        }
    }

    // ------------------------------------------------------- камера

    /** Переключает окно камеры между углом и полным экраном. */
    private void togglePreviewWindow() {
        previewFullScreen = !previewFullScreen;
        renderer.setPreviewFullScreen(previewFullScreen);
        saveSetting("preview-fullscreen", previewFullScreen);
        previewWindowButton.setText(previewFullScreen
                ? "камера: во весь экран" : "камера: окошко в углу");
        toast(previewFullScreen ? "Камера на весь экран" : "Камера окошком в углу");
    }

    /**
     * Калибровка: приложение запоминает, как человек сидит сейчас, и считает это положение
     * нейтральным. Персонаж перестаёт быть повёрнутым из-за того, что пользователь сидит боком.
     */
    private void calibrate() {
        if (!cameraMode) {
            toast("Сначала включи режим камеры");
            return;
        }
        stage.mapper().calibrate();
        toast("Нейтраль снята: теперь это твоё «прямо»");
        EchidnaLog.i("APP", "калибровка: нейтраль снята кнопкой");
    }

    private void updateTurnButtonText() {
        if (turnButton == null) {
            return;
        }
        turnButton.setText(turnInverted ? "поворот: наоборот" : "поворот: обычно");
    }

    /**
     * Направление поворота головы.
     *
     * <p>У камер телефонов и у разных версий трекера знак поворота отличается: кто-то сидит перед
     * камерой так, что поворот головы уходит в другую сторону. Кнопка меняет направление, не трогая
     * зеркалирование превью, и запоминается.</p>
     */
    private void toggleTurnDirection() {
        turnInverted = !turnInverted;
        stage.mapper().setTurnInverted(turnInverted);
        saveSetting("turn-invert", turnInverted);
        updateTurnButtonText();
        toast(turnInverted ? "Поворот головы: в другую сторону" : "Поворот головы: как обычно");
    }

    /**
     * Поворот самой картинки камеры.
     *
     * <p>Если превью и распознавание видят человека вверх ногами, кнопка перебирает 0, 90, 180 и 270
     * градусов: производители по-разному вешают фронтальный сенсор, и правило
     * {@code SENSOR_ORIENTATION} описывает не все телефоны. Выбор запоминается.</p>
     */
    /**
     * Ручной поворот кадра на 180 градусов.
     *
     * <p>Заодно отменяется автоматический доворот: если приложение уже выучило, что кадр надо
     * повернуть, ручное нажатие начинает с чистого листа, а не спорит с ним.</p>
     */
    private void flipCamera() {
        if (hub != null) {
            hub.forgetLearnedRotation();
        }
        final int next = (hub.camera().extraRotation() + 180) % 360;
        hub.camera().setExtraRotation(next);
        saveSetting("camera-rotation", next);
        updateFlipButtonText();
        toast(next == 0 ? "Камера: обычное положение" : "Камера перевёрнута");
    }

    private void updateFlipButtonText() {
        if (flipCameraButton == null) {
            return;
        }
        final int rotation = hub == null ? 0 : hub.camera().extraRotation();
        flipCameraButton.setText(rotation == 0
                ? "камера: перевернуть" : "камера: вернуть (" + rotation + "\u00b0)");
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
        hintText.setText("Тяни по экрану — модель смотрит за пальцем");
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
        actionBar.setVisibility(visibility);
        bottomPanel.setVisibility(visibility);
        if (uiHidden) {
            hideOverlay();
        }
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
        modelId = prefs.getString(ModelCatalog.PREF_KEY, ModelCatalog.DEFAULT_ID);
        previewFullScreen = prefs.getBoolean("preview-fullscreen", false);
        turnInverted = prefs.getBoolean("turn-invert", false);
        stage.mapper().setTurnInverted(turnInverted);
        // Автоматическая проверка положения кадра раньше могла сохранить лишний поворот на 180
        // градусов, и камера оставалась перевёрнутой навсегда. Поэтому сохранённый поворот от
        // прежних версий сбрасывается один раз, а дальше запоминается только явное нажатие кнопки.
        if (!prefs.getBoolean("camera-rotation-v2", false)) {
            prefs.edit().putBoolean("camera-rotation-v2", true).remove("camera-rotation").apply();
        }
        hub.camera().setExtraRotation(prefs.getInt("camera-rotation", 0));
        renderer.setPreviewFullScreen(previewFullScreen);
        renderer.requestModel(modelId);
        final int scale = prefs.getInt("scale", 100);
        final int offset = prefs.getInt("offsetY", 0);
        renderer.setBackground(backgroundCursor);
        renderer.setPreviewMirror(previewMirror);
        renderer.setModelScale(scale / 100.0f);
        renderer.setModelOffsetY(offset / 200.0f);
        stage.mapper().setMirrored(previewMirror);
        armInverted = prefs.getBoolean("arm-invert", false);
        if (armButton != null) {
            updateArmButtonText();
        }
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
        handleIntentExtras(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.stop();
        glView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        hub.release();
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
        hideOverlay();
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
        highlight(cameraButton, true);
        if (previewWindowButton != null) {
            previewWindowButton.setVisibility(View.VISIBLE);
        }
        if (calibrateButton != null) {
            calibrateButton.setVisibility(View.VISIBLE);
        }
        if (turnButton != null) {
            turnButton.setVisibility(View.VISIBLE);
            updateTurnButtonText();
        }
        if (flipCameraButton != null) {
            flipCameraButton.setVisibility(View.VISIBLE);
            updateFlipButtonText();
        }
        // Направление рук берётся из настроек: у разных ригов каналы вращаются в разные стороны,
        // и одно нажатие кнопки "руки: вверх/вниз" это исправляет.
        stage.mapper().setArmInverted(armInverted);
        toast(ModelCatalog.byId(modelId).title
                + " повторяет мимику, повороты тела и жесты рукой");
        EchidnaLog.i("APP", "режим камеры включён, трекер " + hub.trackerName());
    }

    /** Обновляет строку эмоции, пока распознавание загружается: раз в четверть секунды. */
    private final Runnable moodWatcher = new Runnable() {
        @Override
        public void run() {
            updateMoodText();
            if (cameraMode && hub != null && !hub.trackersReady()) {
                handler.postDelayed(this, 250);
            }
        }
    };

    private void disableCameraMode() {
        cameraMode = false;
        handler.removeCallbacks(moodWatcher);
        updateMoodText();
        hub.stop();
        renderer.setPreviewEnabled(false);
        renderer.requestIdle();
        highlight(cameraButton, false);
        if (previewWindowButton != null) {
            previewWindowButton.setVisibility(View.GONE);
        }
        if (calibrateButton != null) {
            calibrateButton.setVisibility(View.GONE);
        }
        if (turnButton != null) {
            turnButton.setVisibility(View.GONE);
        }
        if (flipCameraButton != null) {
            flipCameraButton.setVisibility(View.GONE);
        }
        toast("Режим камеры выключен");
    }

    private boolean startCamera() {
        final boolean started = hub.start(false);
        // Камера включается сразу, распознавание - следом за ней: строка об эмоции говорит, что
        // происходит, и обновляется сама, пока модели не поднимутся.
        handler.removeCallbacks(moodWatcher);
        handler.post(moodWatcher);
        if (!started) {
            toast("Камера не запустилась: " + hub.camera().lastError());
            permissionText.setVisibility(CameraController.hasPermission(this) ? View.GONE : View.VISIBLE);
        } else {
            permissionText.setVisibility(View.GONE);
        }
        return started;
    }

    /**
     * Меняет направление каналов рук.
     *
     * <p>Разные риги вращают плечо в разные стороны, и угадать это по файлу модели нельзя - можно
     * только посмотреть. Поэтому у кнопки два положения, и одно нажатие ставит руку так, как надо
     * именно этой модели.</p>
     */
    private void toggleArmDirection() {
        armInverted = !armInverted;
        saveSetting("arm-invert", armInverted);
        stage.mapper().setArmInverted(armInverted);
        updateArmButtonText();
        toast(armInverted ? "Руки: обратное направление" : "Руки: прямое направление");
    }

    private void updateArmButtonText() {
        if (armButton != null) {
            armButton.setText(armInverted ? "руки: вниз" : "руки: вверх");
        }
    }

    /** Один раз рассказывает, где выбрать модель: без этого список моделей никто не находил. */
    private void showFirstRunHint() {
        if (prefs == null || prefs.getBoolean("hint-shown", false)) {
            return;
        }
        prefs.edit().putBoolean("hint-shown", true).apply();
        toast("Персонажи — кнопка внизу: " + ModelCatalog.available(getAssets()).size()
                + " модели на выбор");
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
        if (mirrorButton != null) {
            mirrorButton.setText(previewMirror ? "зеркалить: да" : "зеркалить: нет");
        }
        toast(previewMirror ? "Зеркально, как в зеркале" : "Без зеркала");
    }

    private void toggleGallery() {
        hideOverlay();
        if (galleryPanel.getVisibility() == View.VISIBLE) {
            galleryPanel.setVisibility(View.GONE);
            highlight(galleryButton, false);
        } else {
            showGallery();
        }
    }

    private void showGallery() {
        if (galleryPanel.getChildCount() == 0) {
            populateGallery();
        }
        galleryPanel.setVisibility(View.VISIBLE);
        highlight(galleryButton, true);
    }

    private void populateGallery() {
        final List<String> motions = stage.knownMotions();
        if (motions.isEmpty()) {
            // Объёмный персонаж и риг вроде Нахиды не имеют файлов движений: всё, что они умеют, -
            // это мимика, и галерея должна показывать именно её, а не пустой список.
            populateExpressionGallery(stage.knownExpressions());
            return;
        }
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

    /** Галерея модели без движений: все выражения лица её рига, включая объёмного персонажа. */
    private void populateExpressionGallery(List<String> expressions) {
        final TextView title = new TextView(this);
        title.setTextColor(0xFFB388FF);
        title.setTextSize(12);
        title.setPadding(0, dp(6), 0, dp(4));
        if (expressions.isEmpty()) {
            title.setText("у этой модели нет ни движений, ни выражений");
            galleryPanel.addView(title);
            EchidnaLog.i("APP", "галерея: у модели нет ни движений, ни выражений");
            return;
        }
        title.setText("Выражения · " + expressions.size());
        galleryPanel.addView(title);

        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(wrap);
        for (int i = 0; i < expressions.size(); i++) {
            final String name = expressions.get(i);
            final Button chip = new Button(this);
            chip.setText(name.replace('_', ' '));
            chip.setAllCaps(false);
            chip.setTextSize(11);
            chip.setTextColor(0xFFF3ECFF);
            chip.setBackground(rounded(0x44FFFFFF, 14));
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            chip.setOnClickListener(v -> {
                stage.playExpression(name);
                toast("Выражение: " + name);
            });
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, dp(6), 0);
            chip.setLayoutParams(params);
            wrap.addView(chip);
        }
        galleryPanel.addView(scroll);
        EchidnaLog.i("APP", "галерея: " + expressions.size() + " выражений");
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
    /**
     * Разбирает движения ВСЕХ моделей сборки, а не одной Ехидны.
     *
     * <p>Сначала путь был жёстко прописан на её папку, поэтому файлы остальных персонажей не
     * проверялись вовсе. Теперь проверка идёт по каталогу каждой модели, и в логе видно, сколько
     * движений прочитано у каждой: это и есть ответ на вопрос «собраны ли мои модели целиком».</p>
     */
    private void parseAllMotions() {
        final StringBuilder broken = new StringBuilder();
        final StringBuilder perModel = new StringBuilder();
        int total = 0;
        int failed = 0;
        try {
            final java.util.List<ModelCatalog.ModelSpec> specs = ModelCatalog.available(getAssets());
            for (int m = 0; m < specs.size(); m++) {
                final ModelCatalog.ModelSpec spec = specs.get(m);
                final String dir = "live2d/" + spec.id + "/motions";
                final String[] files = getAssets().list(dir);
                int here = 0;
                if (files != null) {
                    java.util.Arrays.sort(files);
                    for (String file : files) {
                        if (!file.endsWith(".motion3.json")) {
                            continue;
                        }
                        total++;
                        here++;
                        try {
                            final byte[] data = readAsset(dir + "/" + file);
                            CubismMotion.create(data);
                        } catch (Throwable error) {
                            failed++;
                            if (failed <= 6) {
                                broken.append(broken.length() == 0 ? "" : ", ")
                                      .append(spec.id).append('/').append(file)
                                      .append(" (").append(error.getClass().getSimpleName()).append(')');
                            }
                        }
                    }
                }
                if (perModel.length() > 0) {
                    perModel.append(", ");
                }
                perModel.append(spec.id).append(' ').append(here);
            }
        } catch (Throwable error) {
            motionsLoadReport = "проверка движений сорвалась: " + error;
            return;
        }
        final String report = failed == 0
            ? "движения всех моделей прочитаны: " + total + "/" + total + " (" + perModel + ")"
            : "движения прочитаны: " + (total - failed) + "/" + total + " (" + perModel
              + "), ошибок " + failed + ": " + broken;
        motionsLoadReport = report;
        EchidnaLog.i("MOTION", report);
    }

    /** Читает текстовый файл из assets: нужен для разбора model3.json в самопроверке. */
    private String readText(String path) throws java.io.IOException {
        final byte[] data = readAsset(path);
        return new String(data, java.nio.charset.Charset.forName("UTF-8"));
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
                    highlight(cameraButton, false);
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

                /**
                 * Отчёт о возможностях на этом телефоне: какие модели упакованы, поднялся ли трекер
                 * тела, есть ли 3D. Если у пользователя что-то не так — этот текст всё объясняет.
                 */
                @Override
                public String capabilitiesReport() {
                    final StringBuilder out = new StringBuilder();
                    try {
                        final List<ModelCatalog.ModelSpec> packed = ModelCatalog.available(getAssets());
                        out.append("моделей ").append(packed.size()).append('(');
                        for (int i = 0; i < packed.size(); i++) {
                            if (i > 0) {
                                out.append(", ");
                            }
                            out.append(packed.get(i).id);
                        }
                        out.append(")");
                    } catch (Throwable error) {
                        out.append("моделей ?");
                    }
                    // Сколько текстур обещают файлы моделей и все ли они на месте: именно здесь
                    // ломалась загрузка чужих моделей.
                    int textures = 0;
                    try {
                        final java.util.List<ModelCatalog.ModelSpec> packed =
                                ModelCatalog.available(getAssets());
                        for (int m = 0; m < packed.size(); m++) {
                            final ModelCatalog.ModelSpec spec = packed.get(m);
                            final String setting = readText(spec.assetDir + spec.modelJson);
                            final org.json.JSONObject json = new org.json.JSONObject(setting);
                            final org.json.JSONArray list = json.getJSONObject("FileReferences")
                                    .optJSONArray("Textures");
                            if (list == null) {
                                continue;
                            }
                            for (int t = 0; t < list.length(); t++) {
                                final String path = spec.assetDir + list.getString(t);
                                try {
                                    getAssets().open(path).close();
                                    textures++;
                                } catch (Exception missing) {
                                    EchidnaLog.e("MODEL", "текстуры нет в сборке: " + path);
                                }
                            }
                        }
                    } catch (Throwable error) {
                        EchidnaLog.w("MODEL", "проверка текстур: " + error);
                    }
                    out.append("; текстуры: ").append(textures);
                    out.append("; лицо: ").append(hub.trackerName());
                    out.append("; тело: ").append(
                            com.echidna.studio.track.MediaPipePoseTracker.assetAvailable(getBaseContext())
                                    ? "есть" : "нет");
                    // Кисть: и модель в сборке, и живой трекер. По ним модель двигает руками и
                    // кивает столько раз, сколько пальцев показал человек.
                    out.append("; кисть: ").append(
                            com.echidna.studio.track.MediaPipeHandTracker.assetAvailable(getBaseContext())
                                    ? (hub.handsAvailable() ? "есть" : "модель есть, трекер нет") : "нет");
                    out.append("; каналы рук у модели: ").append(renderer.armChannels());
                    out.append("; каналы мимики: ").append(renderer.emotionChannels());
                    out.append("; распознавание: ").append(hub.trackersReady() ? "готово" : "загружается");
                    // Сколько раз кадр доворачивался сам: по этому видно, врал ли производитель с
                    // углом сенсора.
                    out.append("; доворотов по лицу: ").append(hub.cameraFlips());
                    out.append("; микрофон: не используется");
                    out.append("; сенсор камеры ").append(hub.camera().sensorOrientation())
                            .append("°");
                    out.append("; поворот кадра ").append(hub.camera().frameRotation())
                            .append("° (ручной доворот ").append(hub.camera().extraRotation())
                            .append("°)");
                    out.append("; версия ").append(appVersion());
                    out.append("; Android ").append(android.os.Build.VERSION.SDK_INT);
                    out.append("; ").append(android.os.Build.SUPPORTED_ABIS.length > 0
                            ? android.os.Build.SUPPORTED_ABIS[0] : "?");
                    return out.toString();
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
        highlight(cameraButton, true);
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
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (Manifest.permission.CAMERA.equals(permissions[i])) {
                cameraGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        permissionText.setVisibility(cameraGranted ? View.GONE : View.VISIBLE);
        if (cameraGranted) {
            // Разрешение только что выдано: модели распознавания поднимаются сразу, чтобы первое
            // нажатие «камера» сработало без задержки.
            hub.warmUp();
        }
        if (cameraGranted && cameraMode) {
            startCamera();
        }
        EchidnaLog.i("APP", "разрешения: камера=" + cameraGranted);
    }

    // ------------------------------------------------------- renderer bridge

    private void onSignals(FaceSignals signals) {
        stage.setSignals(signals);
        updateHandText(signals);
        // Эмоция появляется только после того, как кадр обработан, поэтому строку обновляем здесь,
        // а не в тике: так она меняется ровно тогда, когда меняется лицо.
        handler.post(this::updateMoodText);
        final int gesture = signals.handsSeen ? signals.fingers : -1;
        if (gesture != shownGesture) {
            shownGesture = gesture;
            handler.post(() -> showGesture(gesture));
        }
        // Смена распознанной эмоции показывается плашкой: человек должен видеть, что приложение
        // поняло его лицо. Порог низкий - сдержанное выражение тоже считается.
        final int emotion = stage.emotion();
        final float intensity = stage.emotionIntensity();
        if (emotion != shownEmotion) {
            shownEmotion = emotion;
            if (emotion != com.echidna.studio.track.EmotionDetector.NEUTRAL && intensity > 0.33f) {
                handler.post(() -> showEmotion(emotion));
            }
        }
        // Сторож камеры: если кадры перестали приходить, сессия пересобирается. Без него человек
        // видел пустое окошко и надпись "камера", а приложение считало, что всё в порядке.
        if (cameraMode && hub.isRunning()
                && hub.camera().msSinceLastFrame() > 2500
                && android.os.SystemClock.elapsedRealtime() - lastCameraRestartAt > 8000) {
            lastCameraRestartAt = android.os.SystemClock.elapsedRealtime();
            handler.post(this::restartCameraAfterStall);
        }
    }

    /**
     * Показывает в шапке, что известно про руки.
     *
     * <p>Человек двигает рукой и ждёт, что модель повторит. Если этого не происходит, строка сразу
     * объясняет причину: кисть не видно, рука слишком далеко или у модели нет каналов рук.</p>
     */
    private void updateHandText(FaceSignals signals) {
        if (handText == null) {
            return;
        }
        if (!signals.handsSeen) {
            handText.setText(cameraMode
                    ? "рука: не видна (подними ладонь в кадр, телефон держи дальше от лица)"
                    : "рука: камера выключена");
            return;
        }
        final int percent = Math.round(Math.max(signals.handUpLeft, signals.handUpRight) * 100.0f);
        final int fingers = signals.fingers;
        handText.setText("рука: видна, пальцев " + fingers + ", подъём " + percent + "%"
                + (signals.chinTouch > 0.5f ? ", у подбородка" : ""));
    }

    /**
     * Показывает распознанную эмоцию.
     *
     * <p>Эмоция считается в потоке разбора, поэтому строка обновляется вместе с кадрами: так видно,
     * что приложение не просто повторяет повороты головы, а понимает выражение лица. Если
     * распознавание ещё загружается, строка говорит об этом прямо.</p>
     */
    private void updateMoodText() {
        if (moodText == null) {
            return;
        }
        if (!cameraMode) {
            moodText.setText("эмоция: камера выключена");
            return;
        }
        if (hub == null || !hub.trackersReady()) {
            moodText.setText("распознавание: загружается…");
            return;
        }
        if (!stage.emotional()) {
            moodText.setText("эмоция: спокойствие · мимика включена");
            return;
        }
        moodText.setText("эмоция: " + stage.emotionName() + " "
                + Math.round(stage.emotionIntensity() * 100.0f) + "%");
    }

    /**
     * Показывает всплывающую плашку с распознанной эмоцией.
     *
     * <p>Строка в шапке отвечает на вопрос «что видит приложение сейчас», а плашка - на вопрос «оно
     * вообще меня понимает?». Появляется на смене эмоции и сама исчезает.</p>
     */
    private void showEmotion(int emotion) {
        if (gestureBadge == null) {
            return;
        }
        gestureBadge.setText(com.echidna.studio.track.EmotionDetector.name(emotion).toUpperCase()
                + " · " + Math.round(stage.emotionIntensity() * 100.0f) + "%");
        gestureBadge.setVisibility(View.VISIBLE);
        gestureShownAt = android.os.SystemClock.elapsedRealtime();
        handler.removeCallbacks(hideGestureBadge);
        handler.postDelayed(hideGestureBadge, 1400);
    }

    private void showGesture(int fingers) {
        if (gestureBadge == null) {
            return;
        }
        if (fingers < 0) {
            gestureBadge.setVisibility(View.GONE);
            return;
        }
        gestureBadge.setText(fingers == 0
                ? "КУЛАК · 0 ПАЛЬЦЕВ"
                : "ПОКАЗАНО ПАЛЬЦЕВ: " + fingers);
        gestureBadge.setVisibility(View.VISIBLE);
        gestureShownAt = android.os.SystemClock.elapsedRealtime();
        handler.removeCallbacks(hideGestureBadge);
        handler.postDelayed(hideGestureBadge, 1600);
    }

    /** Камера перестала отдавать кадры: перезапускаем сессию и говорим об этом вслух. */
    private void restartCameraAfterStall() {
        EchidnaLog.w("APP", "кадры камеры пропали, перезапускаю сессию");
        if (hub.restartCamera()) {
            toast("Камера перезапущена");
        }
    }

    private void onPreview(Bitmap frame, int serial) {
        renderer.setPreviewFrame(frame, serial);
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
        runOnUiThread(() -> fpsText.setText(lastFps + " · " + ModelCatalog.byId(modelId).title
                + " · " + hub.trackerName()
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
