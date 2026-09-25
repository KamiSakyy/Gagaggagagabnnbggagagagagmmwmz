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
    public static final String MODEL_DIR = "live2d/Echidna/";
    public static final String MODEL_JSON = "Echidna.model3.json";

    private final AssetManager assets;
    private final Map<String, Integer> motionIndex = new HashMap<String, Integer>();
    private final Map<String, CubismMotion> motionCache = new HashMap<String, CubismMotion>();
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

    public EchidnaModel(AssetManager assets) {
        this.assets = assets;
        mocConsistency = true;

        final CubismIdManager ids = CubismFramework.getIdManager();
        idAngleX = ids.getId(ParameterId.ANGLE_X.getId());
        idAngleY = ids.getId(ParameterId.ANGLE_Y.getId());
        idAngleZ = ids.getId(ParameterId.ANGLE_Z.getId());
        idBodyX = ids.getId(ParameterId.BODY_ANGLE_X.getId());
        idBodyY = ids.getId(ParameterId.BODY_ANGLE_Y.getId());
        idBodyZ = ids.getId(ParameterId.BODY_ANGLE_Z.getId());
        idEyeLOpen = ids.getId(ParameterId.EYE_L_OPEN.getId());
        idEyeROpen = ids.getId(ParameterId.EYE_R_OPEN.getId());
        idEyeLSmile = ids.getId(ParameterId.EYE_L_SMILE.getId());
        idEyeRSmile = ids.getId(ParameterId.EYE_R_SMILE.getId());
        idEyeBallX = ids.getId(ParameterId.EYE_BALL_X.getId());
        idEyeBallY = ids.getId(ParameterId.EYE_BALL_Y.getId());
        idMouthOpenY = ids.getId(ParameterId.MOUTH_OPEN_Y.getId());
        idMouthForm = ids.getId(ParameterId.MOUTH_FORM.getId());
        idBrowLY = ids.getId(ParameterId.BROW_L_Y.getId());
        idBrowRY = ids.getId(ParameterId.BROW_R_Y.getId());
        idCheek = ids.getId("ParamCheek");
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

        final byte[] settingBuffer = readAsset(MODEL_DIR + MODEL_JSON);
        modelSetting = new CubismModelSettingJson(settingBuffer);

        loadModel(readAsset(MODEL_DIR + modelSetting.getModelFileName()));
        if (model == null) {
            throw new IOException("moc3 не загрузился");
        }

        // Physics and pose are absent for this model, but loading them defensively costs nothing.
        final String physicsFile = modelSetting.getPhysicsFileName();
        if (physicsFile != null && !physicsFile.isEmpty()) {
            loadPhysics(readAsset(MODEL_DIR + physicsFile));
        }
        final String poseFile = modelSetting.getPoseFileName();
        if (poseFile != null && !poseFile.isEmpty()) {
            loadPose(readAsset(MODEL_DIR + poseFile));
            poseEffect = CubismPose.create(readAsset(MODEL_DIR + poseFile));
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
                CubismFramework.getIdManager().getId(ParameterId.BREATH.getId()), 0.5f, 0.5f, 3.2345f, 0.5f));
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

        // Renderer and textures need a current GL context.
        final CubismRenderer renderer = CubismRendererAndroid.create();
        setupRenderer(renderer);
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
            final int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            textureIds.add(id[0]);
            this.<CubismRendererAndroid>getRenderer().bindTexture(i, id[0]);
            this.<CubismRendererAndroid>getRenderer().isPremultipliedAlpha(false);
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
        matrix.multiplyByMatrix(modelMatrix);
        this.<CubismRendererAndroid>getRenderer().setMvpMatrix(matrix);
        this.<CubismRendererAndroid>getRenderer().drawModel();
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
