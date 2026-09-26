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
    /**
     * Куда смотрит конкретная модель.
     *
     * <p>Здесь был статический {@code MODEL_DIR = "live2d/echidna/"} — и все персонажи читали
     * текстуры и файлы движений из папки Ехидны: у Валентины не находилась вторая текстура, а
     * Эмилии играли чужие движения. Путь обязан быть полем экземпляра.</p>
     */
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
    /** Сколько текстур обещает model3.json и какие из них не открылись. */
    private int texturesExpected;
    private final List<String> texturesMissing = new ArrayList<String>();
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
    /**
     * Каналы рук: у ригов, где руки нарисованы отдельными слоями, есть плечо, предплечье и кисть.
     * Список заполняется один раз после загрузки, у моделей без рук он остаётся пустым, и тогда
     * жест ничего не ломает.
     */
    private final Map<String, CubismId> armIds = new java.util.LinkedHashMap<String, CubismId>();
    private boolean armResolved;

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
                .append(textureIds.size()).append("/").append(texturesExpected).append(" текстур")
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
        texturesExpected = modelSetting.getTextureCount();
        for (int i = 0; i < texturesExpected; i++) {
            final String file = modelSetting.getTextureFileName(i);
            if (file == null || file.isEmpty()) {
                continue;
            }
            // Путь строится от папки ЭТОЙ модели: у каждой свои текстуры.
            final String path = modelDir + file;
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
                // Без текстуры модель выглядит белой: об этом надо кричать в лог, а не молчать.
                texturesMissing.add(path);
                EchidnaLog.e("MODEL", "текстура не декодировалась: " + path);
                continue;
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            final int[] glTexture = new int[1];
            GLES20.glGenTextures(1, glTexture, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTexture[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            // Текстуры этих моделей 1500x1500, то есть не степень двойки. В OpenGL ES 2 мипмапы
            // для таких текстур запрещены: glGenerateMipmap ставит ошибку, текстура остаётся
            // неполной и модель рисуется белой. Мипмапы включаются только для степени двойки.
            final boolean powerOfTwo = isPowerOfTwo(bitmap.getWidth()) && isPowerOfTwo(bitmap.getHeight());
            if (powerOfTwo) {
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_LINEAR_MIPMAP_LINEAR);
            } else {
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_LINEAR);
            }
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
        if (!texturesMissing.isEmpty()) {
            EchidnaLog.w("MODEL", "модель " + spec.id + ": нет текстур " + texturesMissing);
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    /** Frees the model. Must run on the GL thread. */
    public void release() {
        for (int i = 0; i < textureIds.size(); i++) {
            final int[] id = {textureIds.get(i)};
            GLES20.glDeleteTextures(1, id, 0);
        }
        textureIds.clear();
        emotionIds.clear();
        emotionResolved = false;
        poseRanges.clear();
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

    /** Текстуры, которые модель3.json обещает, но которых нет в сборке. */
    public List<String> missingTextures() {
        return new ArrayList<String>(texturesMissing);
    }

    /** Путь к папке этой модели; по нему самопроверка ищет её файлы. */
    public String assetDirectory() {
        return modelDir;
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
                motion = loadMotion(readAsset(modelDir + file));
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

    /**
     * Настоящие диапазоны параметров позы у этой модели.
     *
     * <p>Ключ - идентификатор параметра, значения: минимум, максимум. Приложение считает позу в
     * условных единицах (±30 градусов у головы), а у рига они могут быть другими: у Эмилии-кролика
     * свои границы у каждого канала. Здесь эти границы читаются у самой модели и поза пересчитывается
     * в них, поэтому движения доходят до краёв ровно так, как задумал автор рига, и ничего не
     * обрезается посередине.</p>
     */
    private final Map<CubismId, float[]> poseRanges = new HashMap<CubismId, float[]>();
    /** Имена параметров позы, для которых берутся границы модели. */
    private static final String[] POSE_RANGE_NAMES = {
            "ParamAngleX", "ParamAngleY", "ParamAngleZ",
            "ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ",
            "ParamEyeBallX", "ParamEyeBallY",
            "ParamMouthOpenY", "ParamMouthForm", "ParamEyeLOpen", "ParamEyeROpen", "ParamCheek",
    };
    /** Сколько каналов позы получили границы модели: видно в отчёте. */
    public int poseRangeCount() {
        return poseRanges.size();
    }

    /** Собирает границы параметров позы у загруженной модели. */
    private void resolvePoseRanges() {
        if (!poseRanges.isEmpty() || model == null) {
            return;
        }
        final int count = model.getParameterCount();
        for (int i = 0; i < POSE_RANGE_NAMES.length; i++) {
            final CubismId candidate = id(POSE_RANGE_NAMES[i]);
            final int index = model.getParameterIndex(candidate);
            if (index >= 0 && index < count) {
                poseRanges.put(candidate, new float[]{
                        model.getParameterMinimumValue(index),
                        model.getParameterMaximumValue(index)});
            }
        }
        EchidnaLog.i("MODEL", "границы позы у модели: " + poseRanges.size() + " каналов");
    }

    /**
     * Пересчитывает значение позы в границы канала модели.
     *
     * <p>Приложение считает позу в своих единицах (угол головы ±30 градусов, раскрытие глаза 0..1).
     * Здесь значение переносится в диапазон модели: середина остаётся серединой, край - краем.</p>
     */
    private float mapped(CubismId idParam, float value, float sourceMin, float sourceMax) {
        final float[] range = poseRanges.get(idParam);
        if (range == null || sourceMax <= sourceMin) {
            return value;
        }
        final float min = range[0];
        final float max = range[1];
        final float t = (value - sourceMin) / (sourceMax - sourceMin);
        final float out = min + t * (max - min);
        return out < min ? min : (out > max ? max : out);
    }

    /** Найденные у модели каналы мимики: брови, глаза, слёзы, бледность. */
    private final Map<String, Channel> emotionIds = new HashMap<String, Channel>();
    private boolean emotionResolved;

    /** Сколько каналов позы настроено по границам самой модели (для отчёта). */
    public String poseRangeReport() {
        return poseRanges.isEmpty() ? "нет" : poseRanges.size() + " каналов по границам модели";
    }

    /** Список каналов мимики этой модели: видно в отчёте самопроверки. */
    public String emotionChannels() {
        return emotionIds.isEmpty() ? "нет" : emotionIds.keySet().toString();
    }

    /** Какие каналы рук существуют у этой модели: видно в отчёте и в самопроверке. */
    public int armChannelCount() {
        return armIds.size();
    }

    public String armChannels() {
        return armIds.isEmpty() ? "нет" : armIds.keySet().toString();
    }

    /** Ищет каналы рук у загруженной модели: у каждой модели свой набор, чего-то может не быть. */
    private void resolveArmChannels() {
        if (armResolved || model == null) {
            return;
        }
        armResolved = true;
        // Важная тонкость: getParameterIndex() никогда не возвращает -1 - для несуществующего
        // параметра движок заводит "мнимый" индекс за пределами настоящих параметров. Поэтому
        // проверка именно такая: настоящий канал имеет индекс меньше числа параметров модели.
        // Раньше считалось, что каналы рук есть у всех моделей, и у Ехидны с Нахидой запись шла в
        // пустоту - руки не двигались.
        final int count = model.getParameterCount();
        for (int i = 0; i < ARM_PARAMETER_NAMES.length; i++) {
            final CubismId candidate = id(ARM_PARAMETER_NAMES[i]);
            final int index = model.getParameterIndex(candidate);
            if (index >= 0 && index < count) {
                armIds.put(ARM_PARAMETER_NAMES[i], candidate);
            }
        }
        if (!armIds.isEmpty()) {
            com.echidna.studio.EchidnaLog.i("MODEL", "каналы рук: " + armIds.keySet());
        } else {
            com.echidna.studio.EchidnaLog.i("MODEL", "у модели нет каналов рук: жест показывается лицом");
        }
    }

    /**
     * Двигает руками по тому, что делает человек.
     *
     * <p>Каждая рука ведёт себя отдельно: поднял правую - двигается правая рука персонажа, левая
     * остаётся в позе модели. Когда рука идёт к подбородку, сгибается локоть и кисть поднимается к
     * лицу; когда человек раскрывает ладонь, кисть разворачивается. Каналы выбираются по названию:
     * плечо, предплечье, кисть и «переключатель» руки (ноль - опущена, четыре - поднята), который
     * есть у ригов Эмилии.</p>
     */
    private void applyArms(Pose pose, float weight) {
        if (pose == null || armIds.isEmpty()) {
            return;
        }
        final boolean anySide = pose.handSeenLeft || pose.handSeenRight || pose.handsSeen
                || pose.chinTouch > 0.001f || pose.armY > 0.001f;
        if (!anySide) {
            return;
        }
        final float direction = pose.armInverted ? -1.0f : 1.0f;
        for (Map.Entry<String, CubismId> entry : armIds.entrySet()) {
            final String name = entry.getKey();
            final boolean right = isRightSide(name);
            float arm = right ? pose.armRight : pose.armLeft;
            float open = right ? pose.handOpenRight : pose.handOpenLeft;
            float chin = right ? (pose.chinRight ? pose.chinTouchRight : 0.0f)
                               : (pose.chinLeft ? pose.chinTouchLeft : 0.0f);
            if (arm < 0.0f && pose.handsSeen) {
                // Запасной путь: сторона не определена, но рука видна - ведём обе руки одинаково.
                arm = ParamLimits.unit(pose.armY);
                open = ParamLimits.unit(pose.handOpen);
                chin = ParamLimits.unit(pose.chinTouch);
            }
            if (arm < 0.0f) {
                continue;
            }
            final float lift = ParamLimits.unit(arm);
            final float touch = ParamLimits.unit(chin);
            final float openness = open < 0.0f ? 0.5f : ParamLimits.unit(open);
            if (name.startsWith("ParamUpperArm")) {
                blend(entry.getValue(), -LIFT_DEGREES * lift * direction, weight);
            } else if (name.startsWith("ParamShoulder")) {
                blend(entry.getValue(), SHOULDER_DEGREES * lift * direction, weight);
            } else if (name.startsWith("ParamForeArm")) {
                blend(entry.getValue(),
                        (CHIN_DEGREES * touch + FOREARM_LIFT_DEGREES * lift) * direction, weight);
            } else if (name.startsWith("ParamHand")) {
                blend(entry.getValue(),
                        (openness - 0.5f) * 2.0f * HAND_DEGREES - HAND_DEGREES * 0.3f * touch,
                        weight);
            } else if (name.startsWith("ParamArm")) {
                // Переключатель позы руки: поднимаем только вверх и никогда не опускаем ниже того,
                // что нарисовала анимация, иначе рука дёргалась бы в такт каждому кадру.
                raiseOnly(entry.getValue(), SELECTOR_RANGE * lift, weight);
            }
        }
    }

    /** Поднимает параметр, но не опускает его: анимация остаётся в силе, пока рука не поднята. */
    private void raiseOnly(CubismId id, float target, float weight) {
        final float current = model.getParameterValue(id);
        if (target <= current) {
            return;
        }
        blend(id, target, weight);
    }

    /** Сторона канала по его имени: L - левая рука, R - правая. */
    static boolean isRightSide(String name) {
        final int base = name.startsWith("ParamUpperArm") ? "ParamUpperArm".length()
                : name.startsWith("ParamForeArm") ? "ParamForeArm".length()
                : name.startsWith("ParamShoulder") ? "ParamShoulder".length()
                : name.startsWith("ParamHand") ? "ParamHand".length()
                : "ParamArm".length();
        final String suffix = name.substring(base);
        return !suffix.contains("L");
    }

    /** Blends the procedural pose on top of what the motions and the effects produced. */
    private void applyPose(Pose pose) {
        if (pose == null) {
            return;
        }
        final float weight = ParamLimits.unit(pose.weight);
        if (weight > 0.0001f) {
            resolvePoseRanges();
            // Значения пересчитываются в границы самой модели: если у неё угол головы доходит до
            // 45 градусов, движение будет таким же, как в её собственных анимациях.
            blend(idAngleX, mapped(idAngleX, pose.angleX,
                    ParamLimits.ANGLE_X_MIN, ParamLimits.ANGLE_X_MAX), weight);
            blend(idAngleY, mapped(idAngleY, pose.angleY,
                    ParamLimits.ANGLE_Y_MIN, ParamLimits.ANGLE_Y_MAX), weight);
            blend(idAngleZ, mapped(idAngleZ, pose.angleZ,
                    ParamLimits.ANGLE_Z_MIN, ParamLimits.ANGLE_Z_MAX), weight);
            blend(idBodyX, mapped(idBodyX, pose.bodyX,
                    ParamLimits.BODY_X_MIN, ParamLimits.BODY_X_MAX), weight);
            blend(idBodyY, mapped(idBodyY, pose.bodyY,
                    ParamLimits.BODY_Y_MIN, ParamLimits.BODY_Y_MAX), weight);
            blend(idBodyZ, mapped(idBodyZ, pose.bodyZ,
                    ParamLimits.BODY_Z_MIN, ParamLimits.BODY_Z_MAX), weight);
            blend(idEyeBallX, mapped(idEyeBallX, pose.eyeBallX,
                    ParamLimits.EYE_BALL_X_MIN, ParamLimits.EYE_BALL_X_MAX), weight);
            blend(idEyeBallY, mapped(idEyeBallY, pose.eyeBallY,
                    ParamLimits.EYE_BALL_Y_MIN, ParamLimits.EYE_BALL_Y_MAX), weight);
            blend(idEyeLSmile, ParamLimits.unit(pose.eyeLSmile), weight);
            blend(idEyeRSmile, ParamLimits.unit(pose.eyeRSmile), weight);
            blend(idMouthOpenY, mapped(idMouthOpenY, pose.mouthOpenY, 0.0f, 1.0f), weight);
            blend(idMouthForm, mapped(idMouthForm, pose.mouthForm, -1.0f, 1.0f), weight);
            blend(idBrowLY, pose.browLY, weight);
            blend(idBrowRY, pose.browRY, weight);
            blend(idCheek, ParamLimits.unit(pose.cheek), weight);
        }
        final float eyeWeight = ParamLimits.unit(pose.eyeWeight);
        if (eyeWeight > 0.0001f) {
            blend(idEyeLOpen, ParamLimits.eyeOpen(pose.eyeLOpen), eyeWeight);
            blend(idEyeROpen, ParamLimits.eyeOpen(pose.eyeROpen), eyeWeight);
        }
        resolveArmChannels();
        applyArms(pose, weight);
        resolveEmotionChannels();
        applyEmotion(pose, weight);
    }

    // ------------------------------------------------------------------ эмоции лица

    /**
     * Каналы мимики, по которым у моделей расходятся названия.
     *
     * <p>Набор у каждой модели свой: у Ехидны и Нахиды есть наклон и форма бровей, у Нахиды ещё
     * «злое лицо» и бледность, у Эмилии - злые глаза и слёзы. Поэтому каналы не назначаются
     * заранее, а ищутся у загруженной модели тем же правилом, что и каналы рук: настоящий параметр
     * имеет индекс меньше числа параметров. Чего у модели нет - то просто не двигается.</p>
     */
    private static final String[] EMOTION_PARAMETER_NAMES = {
            // Брови: наклон, форма, сдвиг и высота. У Эмилии-кролика есть все четыре.
            "ParamBrowLAngle", "ParamBrowRAngle", "ParamBrowLForm", "ParamBrowRForm",
            "ParamBrowLX", "ParamBrowRX", "ParamBrowLY", "ParamBrowRY",
            "ParamBrowLAngle2", "ParamBrowRAngle2",
            // «Бровь» по-японски: отдельный канал рига.
            "mayu",
            // Глаза: злой взгляд, слёзы, зрачок «в кучку», прикрытые веки.
            "ParamEYEL_ikaru", "ParamEYER_ikaru", "ParamBxNamidaL", "ParamBxNamidaR",
            "ParamEyeBallYorime", "ParamEYEL_Y", "ParamEYER_Y",
            "ParamEyeLSmile", "ParamEyeRSmile",
            // Лицо: злость, бледность, румянец.
            "ParamAngry", "ParamAngry2", "anger", "ParamPale", "ParamCheek", "ParamCheek2",
            // Рот: поджатые губы и форма рта.
            "ParamMouthForm", "ParamMouthForm2", "ParamMouthForm3",
    };

    /**
     * Канал модели: индекс, а главное - собственные границы параметра.
     *
     * <p>Это и есть «точечная» настройка: у каждой модели свои единицы измерения (угол брови может
     * быть от минус единицы до единицы, а может быть в градусах, от минус тридцати до тридцати).
     * Раньше значения задавались «на глаз», и каналы либо не доходили до края, либо упирались в
     * него и обрезались. Здесь каждое значение считается как доля собственного диапазона
     * параметра: минус единица - это его минимум, плюс единица - максимум, ноль - значение по
     * умолчанию. Поэтому одна и та же эмоция выглядит одинаково сильно на любом риге.</p>
     */
    private static final class Channel {
        final CubismId id;
        final float min;
        final float max;
        final float def;

        Channel(CubismId id, float min, float max, float def) {
            this.id = id;
            this.min = min;
            this.max = max;
            this.def = def;
        }

        /** Значение канала для доли от минус единицы до единицы. */
        float value(float amount) {
            final float a = amount < -1.0f ? -1.0f : (amount > 1.0f ? 1.0f : amount);
            return a >= 0.0f ? def + (max - def) * a : def + (def - min) * a;
        }
    }

    /** Насколько сдвигается наклон бровей: заметно, но не карикатурно. */
    private static final float BROW_ANGLE_DEGREES = 12.0f;
    /** Размах формы бровей: складка между ними. */
    private static final float BROW_FORM_DEGREES = 8.0f;
    /** Сдвиг бровей в сторону: вместе с наклоном это даёт «домик» над переносицей. */
    private static final float BROW_SIDE = 0.5f;

    private void resolveEmotionChannels() {
        if (emotionResolved || model == null) {
            return;
        }
        emotionResolved = true;
        final int count = model.getParameterCount();
        for (int i = 0; i < EMOTION_PARAMETER_NAMES.length; i++) {
            final CubismId candidate = id(EMOTION_PARAMETER_NAMES[i]);
            final int index = model.getParameterIndex(candidate);
            if (index >= 0 && index < count) {
                emotionIds.put(EMOTION_PARAMETER_NAMES[i], new Channel(candidate,
                        model.getParameterMinimumValue(index),
                        model.getParameterMaximumValue(index),
                        model.getParameterDefaultValue(index)));
            }
        }
        if (!emotionIds.isEmpty()) {
            com.echidna.studio.EchidnaLog.i("MODEL", "каналы мимики: " + emotionIds.keySet());
        }
    }

    /**
     * Раскладывает распознанную эмоцию по каналам модели.
     *
     * <p>Брови получают наклон и форму, глаза - «злой взгляд» или зрачок в кучку, лицо - бледность
     * или румянец, к глазам подступают слёзы. Каналы, которых у модели нет, пропускаются, поэтому
     * одна и та же эмоция работает и на Нахиде, и на Ехидне, и на Эмилии.</p>
     */
    private void applyEmotion(Pose pose, float weight) {
        if (emotionIds.isEmpty() || pose == null) {
            return;
        }
        final float mood = ParamLimits.unit(pose.emotionWeight);
        final float angle = ParamLimits.unitSign(pose.browAngle);
        final float form = ParamLimits.unitSign(pose.browForm);
        final float side = ParamLimits.unitSign(pose.browX) * BROW_SIDE;
        // Каждому каналу задаётся доля от его собственного диапазона: минус единица - минимум
        // параметра, плюс единица - максимум. Так одна и та же эмоция одинаково сильно выглядит на
        // любой модели, чем бы ни измерялся её канал.
        for (Map.Entry<String, Channel> entry : emotionIds.entrySet()) {
            final String name = entry.getKey();
            float amount = 0.0f;
            boolean active = true;
            if (name.startsWith("ParamBrowL") || name.startsWith("ParamBrowR")) {
                if (name.contains("Angle")) {
                    // Внутренние концы бровей вверх - это грусть и мольба, вниз - злость.
                    amount = -angle;
                } else if (name.contains("Form")) {
                    amount = form;
                } else if (name.contains("X")) {
                    amount = side;
                } else if (name.contains("Y")) {
                    // Высота бровей: та же величина, что уходит в модель как высота брови.
                    amount = pose.browLY;
                } else {
                    active = false;
                }
                active = active && (mood > 0.03f || Math.abs(amount) > 0.03f);
            } else if (name.equals("mayu")) {
                // Отдельный канал бровей рига: двигается вместе с их высотой и наклоном.
                amount = (pose.browLY + form * 0.5f - angle * 0.5f) * 0.5f;
                active = Math.abs(amount) > 0.03f;
            } else if (name.startsWith("ParamEYEL_ikaru") || name.startsWith("ParamEYER_ikaru")) {
                // «Злые глаза»: взгляд исподлобья вместе с силой злости.
                amount = pose.glareL;
                active = amount > 0.01f;
            } else if (name.startsWith("ParamBxNamida")) {
                amount = pose.tears;
                active = amount > 0.02f;
            } else if (name.equals("ParamEyeBallYorime")) {
                amount = pose.eyeYorime;
                active = amount > 0.02f;
            } else if (name.startsWith("ParamEyeLSmile") || name.startsWith("ParamEyeRSmile")) {
                // Улыбка глазами: у Эмилии это отдельные каналы, и без них радость не читалась.
                amount = pose.eyeLSmile;
                active = amount > 0.02f;
            } else if (name.startsWith("ParamEYEL_Y") || name.startsWith("ParamEYER_Y")) {
                // Прикрытые веки: усталость и полуприкрытый взгляд.
                amount = Pose.clamp(1.0f - pose.eyeLOpen, 0.0f, 1.0f) * 0.9f;
                active = amount > 0.02f;
            } else if (name.equals("ParamPale")) {
                amount = pose.pale;
                active = amount > 0.02f;
            } else if (name.equals("ParamAngry") || name.equals("ParamAngry2") || name.equals("anger")) {
                amount = pose.angryFace;
                active = amount > 0.02f;
            } else if (name.startsWith("ParamCheek")) {
                amount = pose.cheek;
                active = amount > 0.02f;
            } else if (name.startsWith("ParamMouthForm")) {
                amount = pose.mouthTension;
                active = Math.abs(amount) > 0.02f;
            } else {
                active = false;
            }
            if (active) {
                blend(entry.getValue().id, entry.getValue().value(amount), weight);
            }
        }
    }

    /** Плечо, предплечье, кисть и переключатель позы руки; у каждой руки и её слоёв. */
    private static final String[] ARM_PARAMETER_NAMES = {
            "ParamUpperArmL", "ParamUpperArmR",
            "ParamForeArmL", "ParamForeArmR",
            "ParamHandL", "ParamHandR",
            "ParamUpperArmBL", "ParamUpperArmBR",
            "ParamForeArmLB", "ParamForeArmRB",
            "ParamHandLB", "ParamHandRB",
            "ParamShoulderL", "ParamShoulderR",
            "ParamArmL", "ParamArmR",
    };

    /** На сколько градусов поднимается плечо при поднятой руке: видно даже на маленьком экране. */
    private static final float LIFT_DEGREES = 30.0f;
    /** На сколько сгибается локоть, когда рука тянется к подбородку. */
    private static final float CHIN_DEGREES = 30.0f;
    /** Добавка к сгибу локтя при поднятой руке. */
    private static final float FOREARM_LIFT_DEGREES = 12.0f;
    /** Размах поворота кисти между кулаком и открытой ладонью. */
    private static final float HAND_DEGREES = 18.0f;
    /** Подъём плеча: ключица тоже участвует в движении руки. */
    private static final float SHOULDER_DEGREES = 10.0f;
    /** Верх «переключателя» позы руки: у ригов Эмилии он идёт от нуля до четырёх. */
    private static final float SELECTOR_RANGE = 4.0f;

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

    /** Останавливает текущее движение: при входе в режим камеры ничего не должно доигрывать. */
    @Override
    public void stopMotions() {
        if (model == null) {
            return;
        }
        motionManager.stopAllMotions();
        currentMotionName = null;
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
