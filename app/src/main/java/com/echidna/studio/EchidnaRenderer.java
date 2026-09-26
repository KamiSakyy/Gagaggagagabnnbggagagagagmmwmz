package com.echidna.studio;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;

import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;
import com.echidna.studio.three.Model3DStage;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.id.CubismId;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.math.CubismModelMatrix;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL surface of the app: loads the model, drives the stage and draws the frame.
 *
 * <p>Everything that touches the model happens on the GL thread. The UI thread only sets volatile
 * flags, which are collected at the beginning of the frame.</p>
 */
public final class EchidnaRenderer implements GLSurfaceView.Renderer {
    public interface StatusListener {
        void onModelReady(String report);

        void onModelFailed(String message);

        void onFps(float fps);
    }

    private final AssetManager assets;
    private final ModelStage stage;
    private final android.content.Context appContext;

    private EchidnaModel model;
    private volatile ModelCatalog.ModelSpec pendingModel;
    /** Выбранный персонаж: применяется и при загрузке модели, и при перезагрузке. */
    private volatile ModelCatalog.ModelSpec selectedModel = ModelCatalog.defaultModel();
    private final CubismMatrix44 projection = CubismMatrix44.create();

    /**
     * Кадр, под который подгоняется персонаж: {центр X, центр Y, ширина, высота} в единицах
     * модели. Считается по вершинам мешей, поэтому не зависит от огромного пустого холста модели.
     */
    private float[] framing;
    private float[] latestBounds;
    private float framingAge;
    private int framingSamples;
    private CubismId idTouchAngleX;
    private CubismId idTouchAngleY;
    private CubismId idTouchEyeX;
    private CubismId idTouchEyeY;

    private StatusListener statusListener;
    private ModelStage.Listener stageListener;
    private volatile boolean modelRequested = true;
    private volatile String pendingShowId;
    private volatile String pendingMotion;
    private volatile int pendingCommand;
    private volatile boolean stopRequested;

    // camera preview behind the model
    private int previewTexture;
    private int previewProgram;
    private int previewPositionHandle;
    private int previewTexCoordHandle;
    private FloatBuffer previewQuad;
    private volatile Bitmap previewFrame;
    private volatile boolean previewEnabled;
    private volatile boolean previewMirror = true;
    /** Куда рисовать себя: маленьким окошком в углу или на весь экран. */
    private volatile boolean previewFullScreen;
    private int previewCorner = PREVIEW_CORNER_TOP_RIGHT;

    /** Сколько секунд анимации усредняется, прежде чем кадр фиксируется. */
    private static final float FRAMING_SETTLE_SECONDS = 5.0f;

    private static final int PREVIEW_CORNER_TOP_RIGHT = 0;
    private static final int PREVIEW_CORNER_TOP_LEFT = 1;
    private static final int PREVIEW_CORNER_BOTTOM_RIGHT = 2;
    private static final int PREVIEW_CORNER_BOTTOM_LEFT = 3;

    private volatile BackgroundStyle background = BackgroundStyle.NIGHT;
    private volatile float modelScale = 1.0f;
    private volatile float modelOffsetX = 0.0f;
    private volatile float modelOffsetY = 0.0f;
    private volatile boolean lookAtTouch;
    private volatile float touchX;
    private volatile float touchY;

    private volatile String parameterSummary = "модель не загружена";
    private volatile String lastError = "";
    private int consecutiveErrors;
    private long lastParameterSummaryNanos;

    private long lastFrameNanos;
    private float fpsAccumulator;
    private int fpsFrames;
    private float lastFps;
    private int surfaceWidth = 1;
    private int surfaceHeight = 1;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public EchidnaRenderer(android.content.Context context, ModelStage stage) {
        this.appContext = context.getApplicationContext();
        this.assets = this.appContext.getAssets();
        this.stage = stage;
    }

    public void setStatusListener(StatusListener listener) {
        statusListener = listener;
    }

    /** The stage reports its state to the UI through this. */
    public void setStageListener(ModelStage.Listener listener) {
        stageListener = listener;
    }

    // ---------------------------------------------------------------- commands

    public void requestShow(String showId) {
        pendingShowId = showId;
        pendingCommand = CMD_SHOW;
    }

    public void requestMotion(String name) {
        pendingMotion = name;
        pendingCommand = CMD_MOTION;
    }

    public void requestRandomMotion() {
        pendingCommand = CMD_RANDOM;
    }

    /** Сцена объёмного персонажа: у приложения есть и 2D, и 3D модели. */
    private Model3DStage model3d;

    public void requestCamera() {
        pendingCommand = CMD_CAMERA;
    }

    public void requestIdle() {
        pendingCommand = CMD_IDLE;
    }

    /** Переключает персонажа: модель перезагружается на GL-потоке. */
    public void requestModel(String modelId) {
        final ModelCatalog.ModelSpec spec = ModelCatalog.byId(modelId);
        selectedModel = spec;
        pendingModel = spec;
        if (ready.get()) {
            pendingCommand = CMD_MODEL;
        }
    }

    /** Идентификатор текущего персонажа. */
    public String currentModelId() {
        return model == null ? ModelCatalog.defaultModel().id : model.spec().id;
    }

    public void setBackground(BackgroundStyle style) {
        background = style;
    }

    public void setModelScale(float scale) {
        modelScale = scale;
    }

    public float modelScale() {
        return modelScale;
    }

    public void setModelOffsetY(float offset) {
        modelOffsetY = offset;
    }

    public void setModelOffsetX(float offset) {
        modelOffsetX = offset;
    }

    public float modelOffsetX() {
        return modelOffsetX;
    }

    public float modelOffsetY() {
        return modelOffsetY;
    }

    public void setPreviewFrame(Bitmap frame) {
        previewFrame = frame;
    }

    public void setPreviewEnabled(boolean enabled) {
        previewEnabled = enabled;
    }

    /** True: камера на весь экран фоном. False: только модель, а камера - окошком в углу. */
    public void setPreviewFullScreen(boolean fullScreen) {
        previewFullScreen = fullScreen;
    }

    public boolean isPreviewFullScreen() {
        return previewFullScreen;
    }

    public void setPreviewCorner(int corner) {
        previewCorner = corner;
    }

    public void setPreviewMirror(boolean mirror) {
        previewMirror = mirror;
    }

    public boolean isReady() {
        return ready.get();
    }

    public float currentFps() {
        return lastFps;
    }

    /** The parameters of the native model as of the last published frame. */
    public String parameterSummary() {
        return parameterSummary;
    }

    private static final int CMD_NONE = 0;
    private static final int CMD_SHOW = 1;
    private static final int CMD_MOTION = 2;
    private static final int CMD_RANDOM = 3;
    private static final int CMD_CAMERA = 4;
    private static final int CMD_IDLE = 5;
    private static final int CMD_RELOAD = 6;
    private static final int CMD_MODEL = 7;

    // ------------------------------------------------------------------- GL

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Nothing on a GL thread may ever throw uncaught: an escaping exception kills the whole
        // process, which is exactly how the app used to die instead of showing a message.
        try {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            // startUp() runs in the application; initialize() secures the framework resources.
            if (!CubismFramework.isStarted()) {
                fail("движок Live2D не был запущен при старте приложения");
                return;
            }
            CubismFramework.initialize();
            CubismRendererAndroid.setExtShaderMode(false, false);
            final CubismIdManager ids = CubismFramework.getIdManager();
            if (ids == null) {
                fail("движок Live2D не отдал менеджер идентификаторов");
                return;
            }
            idTouchAngleX = ids.getId("ParamAngleX");
            idTouchAngleY = ids.getId("ParamAngleY");
            idTouchEyeX = ids.getId("ParamEyeBallX");
            idTouchEyeY = ids.getId("ParamEyeBallY");

            if (model != null) {
                // The context was recreated: drop the previous model before building a new one.
                releaseModel();
            }
            initPreview();

            buildSelectedModel();
            if (!ready.get()) {
                return;
            }
            // Start with something alive on screen instead of a frozen pose.
            stage.startShow(com.echidna.studio.anim.ShowLibrary.byId(
                    com.echidna.studio.anim.ShowLibrary.ID_GREET));
        } catch (Throwable error) {
            fail("модель не загрузилась: " + describe(error));
        }
    }

    /** Reports a failure without ever throwing out of a GL callback. */
    private void fail(String message) {
        ready.set(false);
        model = null;
        lastError = message;
        EchidnaLog.e("GL", message);
        if (statusListener != null) {
            try {
                statusListener.onModelFailed(message);
            } catch (Throwable ignored) {
                // The UI is the least important thing when the model is broken.
            }
        }
    }

    private static String describe(Throwable error) {
        final String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** The last model or render error, empty while everything is fine. */
    public String lastError() {
        return lastError;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        try {
            GLES20.glViewport(0, 0, width, height);
            surfaceWidth = Math.max(1, width);
            surfaceHeight = Math.max(1, height);
        } catch (Throwable error) {
            EchidnaLog.e("GL", "ошибка смены размера: " + describe(error));
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        try {
            drawFrame();
        } catch (Throwable error) {
            consecutiveErrors++;
            if (consecutiveErrors == 1 || consecutiveErrors % 60 == 0) {
                EchidnaLog.e("GL", "ошибка кадра (" + consecutiveErrors + "): " + describe(error));
            }
            if (consecutiveErrors == 1) {
                lastError = "ошибка отрисовки: " + describe(error);
                EchidnaLog.saveCrashReport(appContext, error);
                if (statusListener != null) {
                    try {
                        statusListener.onModelFailed(lastError);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
            // Keep drawing: the background and the preview do not depend on the model, and the next
            // frame may well succeed (a lost EGL context does exactly that).
            try {
                GLES20.glClearColor(0.07f, 0.05f, 0.13f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            } catch (Throwable ignored) {
                // nothing left to do
            }
        }
    }

    private void drawFrame() {
        final long now = System.nanoTime();
        float dt = (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        if (dt <= 0.0f || dt > 1.0f) {
            dt = 1.0f / 60.0f;
        }

        applyCommands();
        framingAge += dt;
        if (latestBounds != null && framing != null) {
            latestBounds = null;
        }

        final BackgroundStyle bg = background;
        GLES20.glClearColor(bg.r, bg.g, bg.b, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawPreview();

        if (ready.get() && model3d != null && model3d.isReady()) {
            final Pose pose3d = stopRequested ? null : stage.tick(dt);
            model3d.update(dt, pose3d, stage.isAutoBlink());
            drawModel3d();
            if (now - lastParameterSummaryNanos > 200_000_000L) {
                lastParameterSummaryNanos = now;
                parameterSummary = model3d.report();
            }
        } else if (ready.get() && model != null && model.getModel() != null) {
            if (lookAtTouch && idTouchAngleX != null) {
                // Touch steers the character like a cursor: drag and the head follows.
                model.getModel().setParameterValue(idTouchAngleX, touchX * 30.0f, 0.35f);
                model.getModel().setParameterValue(idTouchAngleY, touchY * 30.0f, 0.35f);
                model.getModel().setParameterValue(idTouchEyeX, touchX, 0.35f);
                model.getModel().setParameterValue(idTouchEyeY, touchY, 0.35f);
            }

            final Pose pose = stopRequested ? null : stage.tick(dt);
            model.update(dt, pose, stage.isAutoBlink());
            drawModel();

            // Published for the self test: if these values move, the whole chain worked.
            if (now - lastParameterSummaryNanos > 200_000_000L) {
                lastParameterSummaryNanos = now;
                parameterSummary = model.parameterSummary();
            }
        }

        countFps(dt);

        // A frame that got here drew everything it had to draw, so an error of an earlier frame is
        // over: the screen must not keep reporting a failure that no longer happens.
        if (consecutiveErrors > 0) {
            final boolean wasReported = !lastError.isEmpty();
            consecutiveErrors = 0;
            lastError = "";
            if (wasReported && ready.get() && model != null) {
                EchidnaLog.i("GL", "отрисовка восстановилась");
                if (statusListener != null) {
                    try {
                        statusListener.onModelReady(model.loadReport());
                    } catch (Throwable ignored) {
                        // The picture is back; a broken listener must not hide it again.
                    }
                }
            }
        }
    }

    private void applyCommands() {
        final int command = pendingCommand;
        pendingCommand = CMD_NONE;
        if (command == CMD_NONE) {
            return;
        }
        switch (command) {
            case CMD_SHOW:
                stage.startShow(pendingShowId);
                break;
            case CMD_MOTION:
                stage.playManualMotion(pendingMotion);
                break;
            case CMD_RANDOM:
                stage.randomMotion();
                break;
            case CMD_CAMERA:
                stage.toCamera();
                break;
            case CMD_IDLE:
                stage.toIdle();
                break;
            case CMD_RELOAD:
                reloadModel();
                break;
            case CMD_MODEL:
                reloadModel();
                break;
            default:
                break;
        }
    }

    /** Drops the current model and builds it again on the GL thread. */
    private void reloadModel() {
        try {
            releaseAll();
            lastError = "";
            consecutiveErrors = 0;
            pendingModel = null;
            buildSelectedModel();
        } catch (Throwable error) {
            fail("повторная загрузка не удалась: " + describe(error));
        }
    }

    /**
     * Loads either a Live2D model or a 3D character, whichever the user picked.
     *
     * <p>Both paths end in the same {@link ModelStage}, which is what keeps the shows, the idle
     * behaviour, the camera tracking and the five animations working for the 3D character exactly as
     * they do for the 2D ones.</p>
     */
    private void buildSelectedModel() throws java.io.IOException {
        if (selectedModel != null && selectedModel.threeD) {
            model = null;
            model3d = new Model3DStage(assets);
            final boolean loaded = model3d.load(selectedModel.assetDir + selectedModel.modelJson);
            if (!loaded) {
                fail(model3d.report());
                return;
            }
            stage.attach(null, stageListener);
            // У объёмной модели нет ни движений, ни AvatarBridge: её выражения сцена получает
            // напрямую, чтобы ряд кнопок в интерфейсе работал и для неё.
            stage.setExpressions(model3d.expressionNames(), model3d::playExpression);
            resetFraming();
            ready.set(true);
            parameterSummary = "3D модель загружена";
            EchidnaLog.MODEL_NOTE = model3d.report();
            if (statusListener != null) {
                statusListener.onModelReady(model3d.report());
            }
            return;
        }
        stage.setExpressions(null, null);
        model = new EchidnaModel(assets, selectedModel);
        model.load();
        stage.attach(model, stageListener);
        resetFraming();
        ready.set(true);
        EchidnaLog.MODEL_NOTE = model.loadReport();
        parameterSummary = "модель загружена";
        EchidnaLog.i("GL", "модель готова: " + model.loadReport());
        if (statusListener != null) {
            statusListener.onModelReady(model.loadReport());
        }
    }

    /** Освобождает и 2D, и 3D модель: переключение персонажа не должно течь. */
    private void releaseAll() {
        if (model != null) {
            releaseModel();
        }
        if (model3d != null) {
            model3d.release();
            model3d = null;
        }
    }

    /** Рисует объёмного персонажа: камера сама подбирает кадр под его габариты. */
    private void drawModel3d() {
        final float zoom = ParamLimits.zoom(stage.viewZoom()) * Math.max(0.05f, modelScale);
        final float offsetY = modelOffsetY + stage.viewOffsetY();
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        model3d.draw(surfaceWidth, surfaceHeight, zoom, modelOffsetX + stage.viewOffsetX(),
                offsetY, previewMirror);
    }

    /**
     * Frames the character on the screen.
     *
     * <p>The projection of a Live2D model used to be scaled by the aspect of its canvas, which
     * squeezed the picture: the vertical scale of a portrait screen is not the horizontal one, so
     * the character came out narrow ("сплющенная"). Here the projection is built from a real scale -
     * pixels per model unit - so both axes always get the same factor and the character never
     * distorts. On top of that the framing follows the bounding box of the character instead of the
     * canvas: the canvas of a model is several times larger than the girl standing on it, so
     * fitting by the canvas left her small in the middle of empty space.</p>
     */
    private void drawModel() {
        final float[] bounds = fitBounds();
        projection.loadIdentity();

        // Pixels per model unit: the character is fitted into the frame and both axes share it.
        final float marginX = surfaceWidth * 0.94f;
        final float marginY = surfaceHeight * 0.96f;
        final float pixelsPerUnit = Math.min(marginX / bounds[2], marginY / bounds[3]);

        final float zoom = ParamLimits.zoom(stage.viewZoom()) * Math.max(0.05f, modelScale);
        final float scale = pixelsPerUnit * zoom;

        // Model units -> NDC. One NDC unit is half of a screen, hence the factor two.
        final float ndcX = 2.0f * scale / Math.max(1.0f, surfaceWidth);
        final float ndcY = 2.0f * scale / Math.max(1.0f, surfaceHeight);
        projection.scale(ndcX, ndcY);

        // The centre of the character goes to the middle of the screen plus whatever the user and
        // the camera mode asked for.
        final float panX = (modelOffsetX + stage.viewOffsetX()) * surfaceWidth;
        final float panY = (modelOffsetY + stage.viewOffsetY()) * surfaceHeight;
        final float centerNdcX = 2.0f * (surfaceWidth * 0.5f + panX) / Math.max(1.0f, surfaceWidth) - 1.0f;
        final float centerNdcY = 2.0f * (surfaceHeight * 0.5f + panY) / Math.max(1.0f, surfaceHeight) - 1.0f;
        projection.translate(centerNdcX - bounds[0] * ndcX, centerNdcY - bounds[1] * ndcY);

        model.draw(projection);
    }

    /**
     * The box the character is framed by.
     *
     * <p>Measuring the meshes every frame would make the picture breathe with every motion, so the
     * box is measured over the first seconds after loading - the shows and the idle sway go through
     * their range there - and only grows afterwards when a pose really leaves it.</p>
     */
    private float[] fitBounds() {
        if (model == null) {
            return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        }
        if (framing == null) {
            framing = model.characterBounds();
            latestBounds = framing;
            return framing;
        }

        // The union of a few seconds of animation, then a slow growth when the pose needs it.
        final float[] current = model.characterBounds();
        latestBounds = current;
        if (framingAge < FRAMING_SETTLE_SECONDS) {
            float minX = Math.min(framing[0] - framing[2] * 0.5f, current[0] - current[2] * 0.5f);
            float maxX = Math.max(framing[0] + framing[2] * 0.5f, current[0] + current[2] * 0.5f);
            float minY = Math.min(framing[1] - framing[3] * 0.5f, current[1] - current[3] * 0.5f);
            float maxY = Math.max(framing[1] + framing[3] * 0.5f, current[1] + current[3] * 0.5f);
            framing = new float[]{(minX + maxX) * 0.5f, (minY + maxY) * 0.5f,
                    Math.max(0.0001f, maxX - minX), Math.max(0.0001f, maxY - minY)};
            framingSamples++;
        } else {
            final float margin = 1.12f;
            if (current[2] > framing[2] * margin || current[3] > framing[3] * margin) {
                framing = current;
                EchidnaLog.i("GL", "кадр расширен под новую позу персонажа");
            }
        }
        return framing;
    }

    /** Forgets the measured box: the next frame measures the character again. */
    private void resetFraming() {
        framing = null;
        framingAge = 0.0f;
        framingSamples = 0;
    }

    private void countFps(float dt) {
        fpsAccumulator += dt;
        fpsFrames++;
        if (fpsAccumulator >= 0.5f) {
            lastFps = fpsFrames / fpsAccumulator;
            fpsAccumulator = 0.0f;
            fpsFrames = 0;
            if (statusListener != null) {
                statusListener.onFps(lastFps);
            }
        }
    }

    /**
     * Queues a fresh attempt at loading the model on the GL thread. Used by the error screen, so a
     * broken first attempt (a lost context, a recycled surface) can be retried without restarting
     * the app.
     */
    public void requestModelReload() {
        modelRequested = true;
        pendingCommand = CMD_RELOAD;
    }

    /** Releases the model - called from the activity when the surface goes away. */
    public void releaseModel() {
        if (model != null) {
            model.release();
            model = null;
        }
        if (model3d != null) {
            model3d.release();
            model3d = null;
        }
        ready.set(false);
        if (previewTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{previewTexture}, 0);
            previewTexture = 0;
        }
        if (previewProgram != 0) {
            GLES20.glDeleteProgram(previewProgram);
            previewProgram = 0;
        }
    }

    // ------------------------------------------------------------ camera preview

    private void initPreview() {
        final float[] quad = {
                -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f,
                1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f
        };
        previewQuad = ByteBuffer.allocateDirect(quad.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        previewQuad.put(quad);
        previewQuad.position(0);

        final int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, PREVIEW_VERTEX_SHADER);
        final int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, PREVIEW_FRAGMENT_SHADER);
        previewProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(previewProgram, vertexShader);
        GLES20.glAttachShader(previewProgram, fragmentShader);
        GLES20.glLinkProgram(previewProgram);
        previewPositionHandle = GLES20.glGetAttribLocation(previewProgram, "aPosition");
        previewTexCoordHandle = GLES20.glGetAttribLocation(previewProgram, "aTexCoord");

        final int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        previewTexture = textures[0];
    }

    /**
     * Draws the camera picture: as a small window in a corner of the screen by default, so the user
     * sees the character and not themselves, or full screen when asked for.
     */
    private void drawPreview() {
        final Bitmap frame = previewFrame;
        if (!previewEnabled || frame == null || previewProgram == 0) {
            return;
        }
        final float[] rect = previewFullScreen ? fullScreenRect() : cornerRect();

        GLES20.glUseProgram(previewProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previewTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0);

        final float left = rect[0];
        final float bottom = rect[1];
        final float right = rect[2];
        final float top = rect[3];
        final float[] vertices = {
                left, bottom, right, bottom, left, top,
                right, bottom, right, top, left, top
        };
        final float[] texCoords = previewMirror
                ? new float[]{1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1}
                : new float[]{0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 0, 1};

        final FloatBuffer vertexBuffer = toBuffer(vertices);
        final FloatBuffer texBuffer = toBuffer(texCoords);

        GLES20.glVertexAttribPointer(previewPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(previewPositionHandle);
        GLES20.glVertexAttribPointer(previewTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
        GLES20.glEnableVertexAttribArray(previewTexCoordHandle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(previewPositionHandle);
        GLES20.glDisableVertexAttribArray(previewTexCoordHandle);
    }

    private float[] fullScreenRect() {
        return new float[]{-1.0f, -1.0f, 1.0f, 1.0f};
    }

    /**
     * The rectangle of the corner window, in NDC.
     *
     * <p>It keeps the aspect of the camera picture, so the user is not stretched in it, and it is
     * small enough not to cover the character.</p>
     */
    private float[] cornerRect() {
        final Bitmap frame = previewFrame;
        final float frameAspect = frame == null || frame.getHeight() == 0
                ? 0.75f
                : (float) frame.getWidth() / frame.getHeight();
        // A quarter of the screen width, but never taller than a quarter of the height.
        float widthNdc = 0.52f;
        float heightNdc = widthNdc * surfaceWidth / Math.max(1.0f, surfaceHeight) / frameAspect;
        final float maxHeight = 0.55f;
        if (heightNdc > maxHeight) {
            heightNdc = maxHeight;
            widthNdc = heightNdc * surfaceHeight / Math.max(1.0f, surfaceWidth) * frameAspect;
        }
        final float margin = 0.04f;
        final boolean leftSide = previewCorner == PREVIEW_CORNER_TOP_LEFT
                || previewCorner == PREVIEW_CORNER_BOTTOM_LEFT;
        final boolean bottomSide = previewCorner == PREVIEW_CORNER_BOTTOM_LEFT
                || previewCorner == PREVIEW_CORNER_BOTTOM_RIGHT;
        final float centerX = leftSide ? (-1.0f + margin + widthNdc * 0.5f)
                                       : (1.0f - margin - widthNdc * 0.5f);
        // The top of the screen in NDC is +1, and the studio keeps its own bars at the very top and
        // bottom, which is why the window sits a little lower than the edge.
        final float centerY = bottomSide ? (-1.0f + margin + heightNdc * 0.5f)
                                         : (1.0f - margin - 0.10f - heightNdc * 0.5f);
        return new float[]{
                centerX - widthNdc * 0.5f,
                centerY - heightNdc * 0.5f,
                centerX + widthNdc * 0.5f,
                centerY + heightNdc * 0.5f
        };
    }

    private static FloatBuffer toBuffer(float[] data) {
        final FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(data);
        buffer.position(0);
        return buffer;
    }

    private static int compileShader(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            EchidnaLog.e("GL", "шейдер превью не собрался: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static final String PREVIEW_VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPosition;\n"
                    + "  vTexCoord = aTexCoord;\n"
                    + "}\n";

    /** Renders the camera with a slight darkening so that the model stays readable on top. */
    private static final String PREVIEW_FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "void main() {\n"
                    + "  vec4 color = texture2D(uTexture, vTexCoord);\n"
                    + "  gl_FragColor = vec4(color.rgb * 0.72, 1.0);\n"
                    + "}\n";

    /** Touch steering of the head, like a cursor in a desktop Live2D viewer. */
    public void onTouch(float normalizedX, float normalizedY, boolean active) {
        lookAtTouch = active;
        touchX = ParamLimits.unit((normalizedX + 1.0f) * 0.5f) * 2.0f - 1.0f;
        touchY = ParamLimits.unit((normalizedY + 1.0f) * 0.5f) * 2.0f - 1.0f;
    }
}
