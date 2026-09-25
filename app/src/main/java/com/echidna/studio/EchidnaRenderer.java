package com.echidna.studio;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;

import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;
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

    private EchidnaModel model;
    private final CubismMatrix44 projection = CubismMatrix44.create();
    private CubismId idTouchAngleX;
    private CubismId idTouchAngleY;
    private CubismId idTouchEyeX;
    private CubismId idTouchEyeY;

    private StatusListener statusListener;
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

    private volatile BackgroundStyle background = BackgroundStyle.NIGHT;
    private volatile float modelScale = 1.0f;
    private volatile float modelOffsetY = 0.0f;
    private volatile boolean lookAtTouch;
    private volatile float touchX;
    private volatile float touchY;

    private volatile String parameterSummary = "модель не загружена";
    private long lastParameterSummaryNanos;

    private long lastFrameNanos;
    private float fpsAccumulator;
    private int fpsFrames;
    private float lastFps;
    private int surfaceWidth = 1;
    private int surfaceHeight = 1;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public EchidnaRenderer(AssetManager assets, ModelStage stage) {
        this.assets = assets;
        this.stage = stage;
    }

    public void setStatusListener(StatusListener listener) {
        statusListener = listener;
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

    public void requestCamera() {
        pendingCommand = CMD_CAMERA;
    }

    public void requestIdle() {
        pendingCommand = CMD_IDLE;
    }

    public void setBackground(BackgroundStyle style) {
        background = style;
    }

    public void setModelScale(float scale) {
        modelScale = scale;
    }

    public void setModelOffsetY(float offset) {
        modelOffsetY = offset;
    }

    public void setPreviewFrame(Bitmap frame) {
        previewFrame = frame;
    }

    public void setPreviewEnabled(boolean enabled) {
        previewEnabled = enabled;
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

    // ------------------------------------------------------------------- GL

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        CubismFramework.initialize();
        CubismRendererAndroid.setExtShaderMode(false, false);
        final CubismIdManager ids = CubismFramework.getIdManager();
        idTouchAngleX = ids.getId("ParamAngleX");
        idTouchAngleY = ids.getId("ParamAngleY");
        idTouchEyeX = ids.getId("ParamEyeBallX");
        idTouchEyeY = ids.getId("ParamEyeBallY");

        if (model != null) {
            // The context was recreated: drop the previous model before building a new one.
            releaseModel();
        }
        model = new EchidnaModel(assets);
        try {
            model.load();
            stage.attach(model, null);
            ready.set(true);
            EchidnaLog.i("GL", "модель готова: " + model.loadReport());
            if (statusListener != null) {
                statusListener.onModelReady(model.loadReport());
            }
            // Start with something alive on screen instead of a frozen pose.
            stage.startShow(com.echidna.studio.anim.ShowLibrary.byId(
                    com.echidna.studio.anim.ShowLibrary.ID_GREET));
        } catch (Throwable t) {
            ready.set(false);
            EchidnaLog.e("GL", "модель не загрузилась: " + t, t);
            if (statusListener != null) {
                statusListener.onModelFailed(String.valueOf(t));
            }
        }
        initPreview();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        final long now = System.nanoTime();
        float dt = (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        if (dt <= 0.0f || dt > 1.0f) {
            dt = 1.0f / 60.0f;
        }

        applyCommands();

        final BackgroundStyle bg = background;
        GLES20.glClearColor(bg.r, bg.g, bg.b, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawPreview();

        if (ready.get() && model != null && model.getModel() != null) {
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
            default:
                break;
        }
    }

    private void drawModel() {
        projection.loadIdentity();

        final float canvasAspect = model.getModel().getCanvasWidth()
                / Math.max(0.001f, model.getModel().getCanvasHeight());
        final float viewAspect = (float) surfaceWidth / Math.max(1.0f, (float) surfaceHeight);

        if (canvasAspect > viewAspect) {
            projection.scale(1.0f, viewAspect / canvasAspect);
        } else {
            projection.scale(canvasAspect / viewAspect, 1.0f);
        }
        projection.scale(modelScale, modelScale);
        projection.translate(0.0f, modelOffsetY);

        final CubismModelMatrix matrix = model.getModelMatrix();
        projection.multiplyByMatrix(matrix);
        model.draw(projection);
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

    /** Releases the model - called from the activity when the surface goes away. */
    public void releaseModel() {
        if (model != null) {
            model.release();
            model = null;
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

    private void drawPreview() {
        final Bitmap frame = previewFrame;
        if (!previewEnabled || frame == null || previewProgram == 0) {
            return;
        }
        GLES20.glUseProgram(previewProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previewTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0);

        final float[] vertices = previewMirror
                ? new float[]{-1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1}
                : new float[]{-1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1};
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
