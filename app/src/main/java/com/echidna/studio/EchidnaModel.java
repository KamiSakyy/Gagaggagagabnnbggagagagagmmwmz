package com.echidna.studio;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId.ParameterId;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.effect.CubismBreath;
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink;
import com.live2d.sdk.cubism.framework.effect.CubismPose;
import com.live2d.sdk.cubism.framework.id.CubismId;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Echidna model: loading from the assets, playback of motion files and the procedural pose
 * layer of the app.
 *
 * <p>The parameter set of this model (the "VTube" set) is used directly, so the pure animation
 * layer only deals with degrees and normalised values.</p>
 */
public class EchidnaModel extends CubismUserModel implements AvatarBridge {
    /** Пути по умолчанию: модель Ехидны, если персонажа не выбрали. */
    public static final String MODEL_DIR = "live2d/echidna/";
    public static final String MODEL_JSON = "model3.json";

    private final AssetManager assets;
    private final ModelCatalog.ModelSpec spec;
    private final String modelDir;
    private final String modelJson;

    /** Выражения лица модели: имя -> файл exp3.json. */
    private final Map<String, String> expressionIndex = new java.util.LinkedHashMap<String, String>();
    private final Map<String, Integer> motionIndex = new HashMap<String, Integer>();
    private final Map<String, CubismMotion> motionCache = new HashMap<String, CubismMotion>();
    private final Map<String, com.live2d.sdk.cubism.framework.motion.ACubismMotion> expressionCache =
            new HashMap<String, com.live2d.sdk.cubism.framework.motion.ACubismMotion>();
    /** Выражения лица живут отдельно от движений: у них свои правила смешивания. */
    private final com.live2d.sdk.cubism.framework.motion.CubismMotionManager expressionManager =
            new com.live2d.sdk.cubism.framework.motion.CubismMotionManager();
    private final List<Integer> textureIds = new ArrayList<Integer>();
    private final List<CubismId> lipSyncIds = new ArrayList<CubismId>();
    private final List<CubismId> eyeBlinkIdList = new ArrayList<CubismId>();

    private final CubismId idAngleX;
    private final CubismId idAngleY;
    private final CubismId idAngleZ;
    private final CubismId idBodyX;
    private final CubismId idBodyY;
    private final CubismId idBodyZ;
    private final CubismId idEyeLOpen;
    private final CubismId idEyeROpen;
    private final CubismId idEyeLSmile;
    private final CubismId idEyeRSmile;
    private final CubismId idEyeBallX;
    private final CubismId idEyeBallY;
    private final CubismId idMouthOpenY;
    private final CubismId idMouthForm;
    private final CubismId idBrowLY;
    private final CubismId idBrowRY;
    private final CubismId idCheek;

    private ICubismModelSetting modelSetting;
    private CubismPose poseEffect;
    private String currentMotionName;
    private String loadReport = "не загружено";

    /**
     * Looks an identifier up in the framework's manager, falling back to a plain id object when the
     * manager is not available: a missing manager must never take the model down.
     */
    private static CubismId id(String name) {
        final CubismIdManager manager = CubismFramework.getIdManager();
        return manager != null ? manager.getId(name) : new CubismId(name);
    }

    public EchidnaModel(AssetManager assets) {
        this(assets, ModelCatalog.defaultModel());
    }

    public EchidnaModel(AssetManager assets, ModelCatalog.ModelSpec spec) {
        this.assets = assets;
        this.spec = spec == null ? ModelCatalog.defaultModel() : spec;
        this.modelDir = this.spec.assetDir;
        this.modelJson = this.spec.modelJson;
        mocConsistency = true;

        idAngleX = id(ParameterId.ANGLE_X.getId());
        idAngleY = id(ParameterId.ANGLE_Y.getId());
        idAngleZ = id(ParameterId.ANGLE_Z.getId());
        idBodyX = id(ParameterId.BODY_ANGLE_X.getId());
        idBodyY = id(ParameterId.BODY_ANGLE_Y.getId());
        idBodyZ = id(ParameterId.BODY_ANGLE_Z.getId());
        idEyeLOpen = id(ParameterId.EYE_L_OPEN.getId());
        idEyeROpen = id(ParameterId.EYE_R_OPEN.getId());
        idEyeLSmile = id(ParameterId.EYE_L_SMILE.getId());
        idEyeRSmile = id(ParameterId.EYE_R_SMILE.getId());
        idEyeBallX = id(ParameterId.EYE_BALL_X.getId());
        idEyeBallY = id(ParameterId.EYE_BALL_Y.getId());
        idMouthOpenY = id(ParameterId.MOUTH_OPEN_Y.getId());
        idMouthForm = id(ParameterId.MOUTH_FORM.getId());
        idBrowLY = id(ParameterId.BROW_L_Y.getId());
        idBrowRY = id(ParameterId.BROW_R_Y.getId());
        idCheek = id("ParamCheek");
    }

    public String loadReport() {
        return loadReport;
    }

    public ICubismModelSetting setting() {
        return modelSetting;
    }

    @Override
    public List<String> motionNames() {
        List<String> names = new ArrayList<String>(motionIndex.keySet());
        java.util.Collections.sort(names);
        return names;
    }

    @Override
    public String currentMotionName() {
        return currentMotionName;
    }

    /** Loads the model, its textures and the motion index. Must run on the GL thread. */
    public void load() throws IOException {
        final long started = System.currentTimeMillis();

        final byte[] settingBuffer = readAsset(modelDir + modelJson);
        modelSetting = new CubismModelSettingJson(settingBuffer);

        loadModel(readAsset(modelDir + modelSetting.getModelFileName()));
        if (model == null) {
            throw new IOException("moc3 не загрузился");
        }

        // Physics and pose are absent for this model, but loading them defensively costs nothing.
        final String physicsFile = modelSetting.getPhysicsFileName();
        if (physicsFile != null && !physicsFile.isEmpty()) {
            loadPhysics(readAsset(modelDir + physicsFile));
        }
        final String poseFile = modelSetting.getPoseFileName();
        if (poseFile != null && !poseFile.isEmpty()) {
            loadPose(readAsset(modelDir + poseFile));
            poseEffect = CubismPose.create(readAsset(modelDir + poseFile));
        }

        if (modelSetting.getEyeBlinkParameterCount() > 0) {
            eyeBlink = CubismEyeBlink.create(modelSetting);
        }
        breath = CubismBreath.create();
        List<CubismBreath.BreathParameterData> breathParameters = new ArrayList<CubismBreath.BreathParameterData>();
        breathParameters.add(new CubismBreath.BreathParameterData(idAngleX, 0.0f, 4.0f, 6.5345f, 0.5f));
        breathParameters.add(new CubismBreath.BreathParameterData(idAngleY, 0.0f, 3.0f, 3.5345f, 0.5f));
        breathParameters.add(new CubismBreath.BreathParameterData(idAngleZ, 0.0f, 3.0f, 5.5345f, 0.5f));
        breathParameters.add(new CubismBreath.BreathParameterData(idBodyX, 0.0f, 2.0f, 15.5345f, 0.5f));
        breathParameters.add(new CubismBreath.BreathParameterData(
                id(ParameterId.BREATH.getId()), 0.5f, 0.5f, 3.2345f, 0.5f));
        breath.setParameters(breathParameters);

        for (int i = 0; i < modelSetting.getLipSyncParameterCount(); i++) {
            lipSyncIds.add(modelSetting.getLipSyncParameterId(i));
        }
        for (int i = 0; i < modelSetting.getEyeBlinkParameterCount(); i++) {
            eyeBlinkIdList.add(modelSetting.getEyeBlinkParameterId(i));
        }

        model.saveParameters();

        // Index every motion of the single motion group by its file name, because the app asks for
        // motions by name ("act_egao") and not by the index inside the group.
        for (int g = 0; g < modelSetting.getMotionGroupCount(); g++) {
            final String group = modelSetting.getMotionGroupName(g);
            for (int i = 0; i < modelSetting.getMotionCount(group); i++) {
                final String file = modelSetting.getMotionFileName(group, i);
                motionIndex.put(motionNameOf(file), i);
            }
        }
        motionManager.stopAllMotions();

        // Expressions are the second animation channel of a model: on this rig they are the whole
        // point (a VTuber model ships reactions instead of motion files).
        expressionManager.stopAllMotions();
        expressionIndex.clear();
        for (int i = 0; i < modelSetting.getExpressionCount(); i++) {
            final String name = modelSetting.getExpressionName(i);
            final String file = modelSetting.getExpressionFileName(i);
            if (name != null && file != null && !file.isEmpty()) {
                expressionIndex.put(name, file);
            }
        }
        expressionCache.clear();

        // Renderer and textures need a current GL context.
        final CubismRenderer renderer = CubismRendererAndroid.create();
        if (renderer == null) {
            throw new IOException("не удалось создать GL-рендерер Live2D");
        }
        setupRenderer(renderer);
        if (getRenderer() == null) {
            throw new IOException("рендерер не привязался к модели");
        }
        setupTextures();

        final StringBuilder report = new StringBuilder();
        report.append("moc3 ").append(model.getParameterCount()).append(" параметров, ")
                .append(model.getDrawableCount()).append(" мешей, ")
                .append(motionIndex.size()).append(" мошен, ")
                .append(textureIds.size()).append(" текстур")
                .append(", ").append(System.currentTimeMillis() - started).append(" мс");
        loadReport = report.toString();
        EchidnaLog.i("MODEL", "загружено: " + loadReport);
    }

    /** Name of a motion as used by the app: the file name without folder and suffix. */
    public static String motionNameOf(String file) {
        String name = file;
        final int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".motion3.json")) {
            name = name.substring(0, name.length() - ".motion3.json".length());
        }
        return name;
    }

    private void setupTextures() {
        for (int i = 0; i < modelSetting.getTextureCount(); i++) {
            final String file = modelSetting.getTextureFileName(i);
            if (file == null || file.isEmpty()) {
                continue;
            }
            final String path = MODEL_DIR + file;
            Bitmap bitmap = null;
            InputStream stream = null;
            try {
                stream = assets.open(path);
                bitmap = BitmapFactory.decodeStream(stream);
            } catch (IOException e) {
                EchidnaLog.e("MODEL", "не удалось прочитать текстуру " + path, e);
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }
            if (bitmap == null) {
                continue;
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            final int[] glTexture = new int[1];
            GLES20.glGenTextures(1, glTexture, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTexture[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            textureIds.add(glTexture[0]);
            this.<CubismRendererAndroid>getRenderer().bindTexture(i, glTexture[0]);
            // Android decodes PNGs into premultiplied bitmaps, and the texture is uploaded as is.
            // Telling the renderer otherwise leaves dark fringes around the character.
            this.<CubismRendererAndroid>getRenderer().isPremultipliedAlpha(true);
            bitmap.recycle();
        }
    }

    /** Frees the model. Must run on the GL thread. */
    public void release() {
        for (int i = 0; i < textureIds.size(); i++) {
            final int[] id = {textureIds.get(i)};
            GLES20.glDeleteTextures(1, id, 0);
        }
        textureIds.clear();
        motionCache.clear();
        motionIndex.clear();
        try {
            delete();
        } catch (RuntimeException e) {
            EchidnaLog.w("MODEL", "delete() threw: " + e);
        }
        poseEffect = null;
        modelSetting = null;
    }

    /**
     * Plays one of the motion files.
     *
     * @return true when the motion was found and accepted by the motion manager
     */
    @Override
    public boolean playMotion(String name, float fadeIn, int priority) {
        final Integer index = motionIndex.get(name);
        if (index == null) {
            EchidnaLog.w("MOTION", "нет мошена с именем " + name);
            return false;
        }
        CubismMotion motion = motionCache.get(name);
        if (motion == null) {
            final String group = motionGroupOf(index);
            final String file = modelSetting.getMotionFileName(group, index);
            try {
                motion = loadMotion(readAsset(MODEL_DIR + file));
            } catch (IOException e) {
                EchidnaLog.e("MOTION", "не удалось прочитать " + file, e);
                return false;
            }
            if (motion == null) {
                return false;
            }
            final float fadeInValue = modelSetting.getMotionFadeInTimeValue(group, index);
            if (fadeInValue >= 0.0f) {
                motion.setFadeInTime(fadeInValue);
            }
            final float fadeOutValue = modelSetting.getMotionFadeOutTimeValue(group, index);
            if (fadeOutValue >= 0.0f) {
                motion.setFadeOutTime(fadeOutValue);
            }
            motion.setEffectIds(eyeBlinkIdList, lipSyncIds);
            motionCache.put(name, motion);
        }
        motion.setFadeInTime(Math.max(0.05f, fadeIn));
        if (motionManager.reserveMotion(priority)) {
            motionManager.startMotionPriority(motion, priority);
            currentMotionName = name;
            return true;
        }
        return false;
    }

    private String motionGroupOf(int index) {
        for (int g = 0; g < modelSetting.getMotionGroupCount(); g++) {
            final String group = modelSetting.getMotionGroupName(g);
            if (index < modelSetting.getMotionCount(group)) {
                return group;
            }
        }
        return "";
    }

    @Override
    public boolean isMotionFinished() {
        return motionManager.isFinished();
    }

    /**
     * Advances the model.
     *
     * @param dt        seconds since the last frame
     * @param pose      procedural pose of this frame (shows, tracking or idle)
     * @param autoBlink whether the framework may blink on its own; disabled while the tracker owns
     *                  the eyelids
     */
    public void update(float dt, Pose pose, boolean autoBlink) {
        if (model == null) {
            return;
        }
        float delta = dt;
        if (delta < 0.0f) {
            delta = 0.0f;
        }
        if (delta > 0.1f) {
            delta = 0.1f;
        }

        model.loadParameters();

        boolean motionUpdated = false;
        if (!motionManager.isFinished()) {
            motionUpdated = motionManager.updateMotion(model, delta);
        } else {
            currentMotionName = null;
        }
        model.saveParameters();

        if (autoBlink && eyeBlink != null && !motionUpdated) {
            eyeBlink.updateParameters(model, delta);
        }
        if (breath != null) {
            breath.updateParameters(model, delta);
        }

        // Facial expressions are added on top of the motion of the frame, the way the engine mixes
        // the expression channel.
        updateExpression(delta);

        // The procedural layer (camera tracking, shows, the idle director) is applied before the
        // physics and pose effects, so hair and the body sway react to head movement in the same
        // frame instead of one frame late. The mic lipsync is driven by the same layer through
        // ParamMouthOpenY, which is why nothing happens here for it.
        applyPose(pose);

        if (physics != null) {
            physics.evaluate(model, delta);
        }
        if (poseEffect != null) {
            poseEffect.updateParameters(model, delta);
        }
        model.update();
    }

    /** Blends the procedural pose on top of what the motions and the effects produced. */
    private void applyPose(Pose pose) {
        if (pose == null) {
            return;
        }
        final float weight = ParamLimits.unit(pose.weight);
        if (weight > 0.0001f) {
            blend(idAngleX, ParamLimits.angleX(pose.angleX), weight);
            blend(idAngleY, ParamLimits.angleY(pose.angleY), weight);
            blend(idAngleZ, ParamLimits.angleZ(pose.angleZ), weight);
            blend(idBodyX, ParamLimits.bodyX(pose.bodyX), weight);
            blend(idBodyY, ParamLimits.bodyY(pose.bodyY), weight);
            blend(idBodyZ, ParamLimits.bodyZ(pose.bodyZ), weight);
            blend(idEyeBallX, ParamLimits.eyeBallX(pose.eyeBallX), weight);
            blend(idEyeBallY, ParamLimits.eyeBallY(pose.eyeBallY), weight);
            blend(idEyeLSmile, ParamLimits.unit(pose.eyeLSmile), weight);
            blend(idEyeRSmile, ParamLimits.unit(pose.eyeRSmile), weight);
            blend(idMouthOpenY, ParamLimits.mouthOpen(pose.mouthOpenY), weight);
            blend(idMouthForm, ParamLimits.mouthForm(pose.mouthForm), weight);
            blend(idBrowLY, pose.browLY, weight);
            blend(idBrowRY, pose.browRY, weight);
            blend(idCheek, ParamLimits.unit(pose.cheek), weight);
        }
        final float eyeWeight = ParamLimits.unit(pose.eyeWeight);
        if (eyeWeight > 0.0001f) {
            blend(idEyeLOpen, ParamLimits.eyeOpen(pose.eyeLOpen), eyeWeight);
            blend(idEyeROpen, ParamLimits.eyeOpen(pose.eyeROpen), eyeWeight);
        }
    }

    private void blend(CubismId id, float target, float weight) {
        final float current = model.getParameterValue(id);
        if (weight >= 1.0f) {
            model.setParameterValue(id, target);
        } else {
            model.setParameterValue(id, current + (target - current) * weight);
        }
    }

    public void draw(CubismMatrix44 matrix) {
        if (model == null) {
            return;
        }
        this.<CubismRendererAndroid>getRenderer().setMvpMatrix(matrix);
        this.<CubismRendererAndroid>getRenderer().drawModel();
    }

    /** The character this instance shows. */
    public ModelCatalog.ModelSpec spec() {
        return spec;
    }

    // ------------------------------------------------------------- выражения лица

    /** Names of the facial expressions of the model, empty when it has none. */
    public List<String> expressionNames() {
        return new ArrayList<String>(expressionIndex.keySet());
    }

    /**
     * Plays a facial expression on top of everything else.
     *
     * @return true when the model has an expression with that name
     */
    public boolean playExpression(String name) {
        if (name == null || model == null) {
            return false;
        }
        final String file = expressionIndex.get(name);
        if (file == null) {
            return false;
        }
        com.live2d.sdk.cubism.framework.motion.ACubismMotion expression = expressionCache.get(name);
        if (expression == null) {
            try {
                expression = com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
                        .create(readAsset(modelDir + file));
            } catch (IOException error) {
                EchidnaLog.e("EXPRESSION", "не удалось прочитать " + file, error);
                return false;
            }
            expressionCache.put(name, expression);
        }
        expressionManager.stopAllMotions();
        expressionManager.startMotion(expression, 0.0f);
        return true;
    }

    /** Fades the current expression out. */
    public void stopExpression() {
        expressionManager.stopAllMotions();
    }

    /** Called every frame by {@link #update}; applies the expression with its own fade. */
    private void updateExpression(float dt) {
        if (model == null) {
            return;
        }
        try {
            expressionManager.updateMotion(model, dt);
        } catch (Throwable error) {
            EchidnaLog.w("EXPRESSION", "выражение пропущено: " + error);
            expressionManager.stopAllMotions();
        }
    }

    // ------------------------------------------------------------------- границы

    /**
     * Bounding box of everything that is drawn, in model units: {@code {centerX, centerY, width,
     * height}}.
     *
     * <p>The canvas of a model is much larger than the character standing on it - a 6500x10000
     * canvas holds a girl of roughly 2300 units wide - so framing the screen by the canvas leaves
     * the character small in the middle of a lot of empty space. The renderer frames by these
     * bounds instead. The measurement is taken from the vertices of the meshes, so it is exact for
     * whatever pose the model is in right now.</p>
     */
    public float[] characterBounds() {
        if (model == null) {
            return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        final int drawables = model.getDrawableCount();
        for (int index = 0; index < drawables; index++) {
            if (model.getDrawableOpacity(index) < 0.01f) {
                continue;
            }
            final float[] vertices = model.getDrawableVertexPositions(index);
            if (vertices == null || vertices.length < 2) {
                continue;
            }
            for (int i = 0; i + 1 < vertices.length; i += 2) {
                final float x = vertices[i];
                final float y = vertices[i + 1];
                if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
                    continue;
                }
                if (x < minX) {
                    minX = x;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
        }
        if (minX > maxX || minY > maxY) {
            return new float[]{0.0f, 0.0f, model.getCanvasWidth(), model.getCanvasHeight()};
        }
        final float width = Math.max(0.0001f, maxX - minX);
        final float height = Math.max(0.0001f, maxY - minY);
        return new float[]{(minX + maxX) * 0.5f, (minY + maxY) * 0.5f, width, height};
    }

    /** True when {@code name} is a motion of this model - used by the self test and the gallery. */
    @Override
    public boolean hasMotion(String name) {
        return motionIndex.containsKey(name);
    }

    /**
     * The values of the parameters the app drives, right now.
     *
     * <p>The self test compares two of these reports: if the head angle or the eyelid moved between
     * them, the pose really reached the native model and not just some intermediate object.</p>
     */
    public String parameterSummary() {
        if (model == null) {
            return "нет модели";
        }
        final StringBuilder builder = new StringBuilder();
        final CubismId[] keys = {
                idAngleX, idAngleY, idAngleZ, idBodyX, idEyeLOpen, idEyeROpen,
                idMouthOpenY, idMouthForm, idEyeBallX, idCheek
        };
        final String[] names = {
                "AngleX", "AngleY", "AngleZ", "BodyX", "EyeLOpen", "EyeROpen",
                "MouthOpenY", "MouthForm", "EyeBallX", "Cheek"
        };
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(names[i]).append('=').append(round(model.getParameterValue(keys[i])));
        }
        return builder.toString();
    }

    private static String round(float value) {
        return String.valueOf(Math.round(value * 100.0f) / 100.0f);
    }

    private byte[] readAsset(String path) throws IOException {
        InputStream stream = null;
        try {
            stream = assets.open(path);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, stream.available()));
            final byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    @Override
    public String toString() {
        return "EchidnaModel{" + loadReport + ", motion=" + currentMotionName + "}";
    }
}
