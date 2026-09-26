package com.echidna.studio.three;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.echidna.studio.EchidnaLog;
import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The 3D stage: draws a live VRM character with OpenGL ES 2.0.
 *
 * <p>It is the third renderer of the app next to the Live2D one: the skeleton, the facial morphs and
 * the hair springs live in {@link Model3D}, and this class only puts them on the screen. Because the
 * camera mode of the app already turns the face and the body of the user into a {@link Pose}, the 3D
 * character is driven by exactly the same signals as the 2D ones: the whole tracking pipeline, the
 * calibration, the five shows and the microphone lipsync work for it without a single change.</p>
 *
 * <p>Skinning is done on the CPU. A phone skins twenty five thousand vertices in a couple of
 * milliseconds, and in exchange the character looks identical on every device, including the ones
 * whose OpenGL ES 2.0 driver lacks float textures - which is exactly the promise of this app.</p>
 */
public final class Model3DStage {

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n"
            + "attribute vec3 aPosition;\n"
            + "attribute vec3 aNormal;\n"
            + "attribute vec2 aUv;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
            + "  vNormal = aNormal;\n"
            + "  vUv = aUv;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
            + "uniform sampler2D uTexture;\n"
            + "uniform float uHasTexture;\n"
            + "uniform vec4 uColor;\n"
            + "uniform vec3 uLight;\n"
            + "uniform float uAlphaCutoff;\n"
            + "uniform float uAlphaBlend;\n"
            + "varying vec3 vNormal;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vec4 albedo = uColor;\n"
            + "  if (uHasTexture > 0.5) {\n"
            + "    albedo = albedo * texture2D(uTexture, vUv);\n"
            + "  }\n"
            + "  if (uAlphaCutoff > 0.0 && albedo.a < uAlphaCutoff) {\n"
            + "    discard;\n"
            + "  }\n"
            + "  vec3 normal = normalize(vNormal);\n"
            + "  float lambert = dot(normal, normalize(uLight));\n"
            + "  // Toon shading: the light is quantised into four steps, which is what makes an anime\n"
            + "  // character look drawn rather than rendered.\n"
            + "  float toon = lambert > 0.72 ? 1.0 : (lambert > 0.34 ? 0.86 : (lambert > 0.02 ? 0.68 : 0.55));\n"
            + "  // A rim of light along the silhouette separates the character from the background.\n"
            + "  float rim = pow(1.0 - max(normal.z, 0.0), 2.4) * 0.22;\n"
            + "  vec3 color = albedo.rgb * (toon + rim);\n"
            + "  color += vec3(0.10, 0.09, 0.14) * (1.0 - albedo.rgb);\n"
            + "  gl_FragColor = vec4(color, albedo.a * uAlphaBlend + (1.0 - uAlphaBlend));\n"
            + "}\n";

    /** One drawable piece of the character. */
    private static final class Piece {
        int mesh;
        int primitive;
        int material = -1;
        int vertexCount;
        int[] indices;
        /** True когда вершин больше 65535 и индексы приходится держать 32-битными. */
        boolean wideIndices;
        FloatBuffer uvBuffer;
        int vertexBuffer;
        int normalBuffer;
        int indexBuffer;
        boolean doubleSided;
        boolean blend;
        float alphaCutoff = -1f;
        int texture = 0;
        float[] color = {1f, 1f, 1f, 1f};
    }

    private final AssetManager assets;
    private final List<Piece> pieces = new ArrayList<Piece>();
    private final List<Integer> textureIds = new ArrayList<Integer>();

    private Model3D model;
    private String report = "3D модель не загружена";
    private int program;
    private int mvpHandle;
    private int textureHandle;
    private int hasTextureHandle;
    private int colorHandle;
    private int lightHandle;
    private int alphaCutoffHandle;
    private int alphaBlendHandle;
    private int positionHandle;
    private int normalHandle;
    private int uvHandle;
    private boolean ready;
    private float[] viewProjection = Mat4.identity();
    private float[] lightDirection = {-0.42f, 0.62f, 0.66f};
    private float clock;

    /** Выражение, включённое вручную из интерфейса, и сколько ему ещё жить. */
    private String manualExpression;
    private float manualExpressionTime;

    private final Camera3D camera = new Camera3D();
    private final float[] scratch = new float[16];

    public Model3DStage(AssetManager assets) {
        this.assets = assets;
    }

    /** Parses a .vrm file and uploads it to the GPU. Must be called on the GL thread. */
    public boolean load(String assetPath) {
        release();
        InputStream stream = null;
        try {
            stream = assets.open(assetPath);
            final Gltf gltf = Gltf.parse(stream);
            model = new Model3D(gltf);
            buildProgram();
            buildPieces();
            ready = true;
            report = "3D: " + (gltf.title == null || gltf.title.isEmpty() ? assetPath : gltf.title)
                    + ", " + gltf.nodes.size() + " костей, " + gltf.meshes.size() + " меша, "
                    + gltf.expressions.size() + " выражений, " + gltf.springs.size() + " цепочек волос";
            EchidnaLog.i("3D", report);
            return true;
        } catch (Throwable error) {
            ready = false;
            report = "3D модель не загрузилась: " + error;
            EchidnaLog.e("3D", report);
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // nothing to do about a stream that refuses to close
                }
            }
        }
    }

    private void buildProgram() {
        final int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        final int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        final int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            throw new IllegalStateException("шейдер 3D не собрался: "
                    + GLES20.glGetProgramInfoLog(program));
        }
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
        hasTextureHandle = GLES20.glGetUniformLocation(program, "uHasTexture");
        colorHandle = GLES20.glGetUniformLocation(program, "uColor");
        lightHandle = GLES20.glGetUniformLocation(program, "uLight");
        alphaCutoffHandle = GLES20.glGetUniformLocation(program, "uAlphaCutoff");
        alphaBlendHandle = GLES20.glGetUniformLocation(program, "uAlphaBlend");
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal");
        uvHandle = GLES20.glGetAttribLocation(program, "aUv");
    }

    private static int compile(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            throw new IllegalStateException("шейдер 3D не компилируется: "
                    + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private void buildPieces() {
        pieces.clear();
        textureIds.clear();
        final Gltf gltf = model.gltf();
        for (int i = 0; i < gltf.textures.size(); i++) {
            textureIds.add(Integer.valueOf(0));
        }
        for (int m = 0; m < gltf.meshes.size(); m++) {
            final Gltf.Mesh mesh = gltf.meshes.get(m);
            for (int p = 0; p < mesh.primitives.size(); p++) {
                final Gltf.Primitive primitive = mesh.primitives.get(p);
                final Piece piece = new Piece();
                piece.mesh = m;
                piece.primitive = p;
                piece.material = primitive.material;
                piece.vertexCount = primitive.vertexCount;
                piece.indices = primitive.indices;
                if (piece.indices == null || piece.vertexCount == 0) {
                    continue;
                }
                piece.uvBuffer = toBuffer(primitive.uvs, piece.vertexCount * 2);
                final int[] buffers = new int[3];
                GLES20.glGenBuffers(3, buffers, 0);
                piece.vertexBuffer = buffers[0];
                piece.normalBuffer = buffers[1];
                piece.indexBuffer = buffers[2];
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, piece.vertexBuffer);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, piece.vertexCount * 12, null,
                        GLES20.GL_DYNAMIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, piece.normalBuffer);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, piece.vertexCount * 12, null,
                        GLES20.GL_DYNAMIC_DRAW);
                // 16-битные индексы понимает любой драйвер OpenGL ES 2.0: модели такого размера
                // всегда влезают, а 32-битные требуют расширения, которого на старых телефонах нет.
                piece.wideIndices = piece.vertexCount > 65000;
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, piece.indexBuffer);
                if (piece.wideIndices) {
                    final IntBuffer indexData = ByteBuffer.allocateDirect(piece.indices.length * 4)
                            .order(ByteOrder.nativeOrder()).asIntBuffer();
                    indexData.put(piece.indices);
                    indexData.position(0);
                    GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, piece.indices.length * 4,
                            indexData, GLES20.GL_STATIC_DRAW);
                } else {
                    final java.nio.ShortBuffer indexData =
                            ByteBuffer.allocateDirect(piece.indices.length * 2)
                                    .order(ByteOrder.nativeOrder()).asShortBuffer();
                    for (int i = 0; i < piece.indices.length; i++) {
                        indexData.put((short) piece.indices[i]);
                    }
                    indexData.position(0);
                    GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, piece.indices.length * 2,
                            indexData, GLES20.GL_STATIC_DRAW);
                }
                if (piece.material >= 0 && piece.material < gltf.materials.size()) {
                    final Gltf.Material material = gltf.materials.get(piece.material);
                    piece.doubleSided = material.doubleSided;
                    piece.color = material.baseColor;
                    piece.blend = "BLEND".equals(material.alphaMode);
                    if ("MASK".equals(material.alphaMode)) {
                        piece.alphaCutoff = material.alphaCutoff;
                    }
                    if (material.baseColorTexture >= 0
                            && material.baseColorTexture < textureIds.size()) {
                        piece.texture = uploadTexture(gltf, material.baseColorTexture,
                                textureIds, piece.material);
                    }
                }
                pieces.add(piece);
            }
        }
        EchidnaLog.i("3D", "кусков модели: " + pieces.size() + ", текстур: " + countTextures());
    }

    private int countTextures() {
        int total = 0;
        for (int i = 0; i < textureIds.size(); i++) {
            if (textureIds.get(i).intValue() != 0) {
                total++;
            }
        }
        return total;
    }

    private int uploadTexture(Gltf gltf, int textureIndex, List<Integer> ids, int slot) {
        final int existing = ids.get(slot).intValue();
        if (existing != 0) {
            return existing;
        }
        final Gltf.Texture texture = gltf.textures.get(textureIndex);
        if (texture.png == null) {
            return 0;
        }
        final Bitmap bitmap = BitmapFactory.decodeByteArray(texture.png, 0, texture.png.length);
        if (bitmap == null) {
            EchidnaLog.w("3D", "текстура " + texture.name + " не распозналась");
            return 0;
        }
        final int[] handle = new int[1];
        GLES20.glGenTextures(1, handle, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                texture.wrapS == 33071 ? GLES20.GL_CLAMP_TO_EDGE : GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                texture.wrapT == 33071 ? GLES20.GL_CLAMP_TO_EDGE : GLES20.GL_REPEAT);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        bitmap.recycle();
        ids.set(slot, Integer.valueOf(handle[0]));
        return handle[0];
    }

    private static FloatBuffer toBuffer(float[] data, int length) {
        final FloatBuffer buffer = ByteBuffer.allocateDirect(Math.max(4, length * 4))
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        if (data != null) {
            buffer.put(data, 0, Math.min(data.length, length));
        }
        buffer.position(0);
        return buffer;
    }

    public boolean isReady() {
        return ready && model != null;
    }

    /** Имена выражений модели, по алфавиту: из них собирается ряд кнопок в интерфейсе. */
    public List<String> expressionNames() {
        final List<String> names = new ArrayList<String>();
        if (model == null) {
            return names;
        }
        names.addAll(model.expressionNames());
        java.util.Collections.sort(names);
        return names;
    }

    /** Включает выражение на две с половиной секунды, как это делает и Live2D путь. */
    public void playExpression(String name) {
        manualExpression = name;
        manualExpressionTime = 2.5f;
    }

    public Model3D model() {
        return model;
    }

    public String report() {
        return report;
    }

    // ------------------------------------------------------------------ кадр

    /** Advances the character and applies the pose the tracker or a show produced. */
    public void update(float dt, Pose pose, boolean autoBlink) {
        if (!ready || model == null) {
            return;
        }
        clock += dt;
        model.resetPose();
        if (pose != null) {
            applyPose(model, pose);
            model.applyFace(pose.eyeLOpen, pose.mouthOpenY, Pose.safe(pose.cheek),
                    Pose.safe(pose.browLY));
        } else {
            idle();
        }
        // Выражение, выбранное в интерфейсе, живёт две с половиной секунды поверх мимики.
        if (manualExpression != null) {
            if (manualExpressionTime <= 0f) {
                manualExpression = null;
            } else {
                manualExpressionTime -= dt;
                model.setExpression(manualExpression, 1.0f);
            }
        }
        model.update(dt);
    }

    /**
     * The pose of the tracker, mapped onto the bones of the character.
     *
     * <p>The naming of the angles is the one of the scene: {@code angleX} turns the head to the
     * side, {@code angleY} nods, {@code angleZ} tilts. The arms follow the turn of the body and rise
     * with the hands of the user, which is what makes the character look like it is moving rather
     * than gliding.</p>
     *
     * <p>Метод статический и публичный, чтобы тест мог прогнать ту же самую раскладку позы на
     * настоящей модели из APK: если знаки осей когда-нибудь перепутаются, рука поедет вниз вместо
     * вверх и тест это поймает.</p>
     */
    public static void applyPose(Model3D model, Pose pose) {
        // В позе сцены поворот головы влево-вправо лежит в angleX, кивок - в angleY: это
        // соглашение самого рига Live2D, и 3D персонаж следует ему же.
        final float yaw = Pose.safe(pose.angleX);
        final float pitch = Pose.safe(pose.angleY);
        final float roll = Pose.safe(pose.angleZ);

        final int head = model.humanoid("head");
        final int neck = model.humanoid("neck");
        final int spine = model.humanoid("chest") >= 0 ? model.humanoid("chest") : model.humanoid("spine");
        final int hips = model.humanoid("hips");
        model.addRotation(head, yaw, pitch, roll);
        model.addRotation(neck, yaw * 0.45f, pitch * 0.4f, roll * 0.45f);
        model.addRotation(spine, Pose.safe(pose.bodyX) * 0.6f + yaw * 0.2f,
                Pose.safe(pose.bodyY) * 0.5f, Pose.safe(pose.bodyZ) * 0.6f);
        model.addRotation(hips, Pose.safe(pose.bodyX) * 0.35f,
                Pose.safe(pose.bodyY) * 0.3f, Pose.safe(pose.bodyZ) * 0.3f);

        // The arms swing a little with the body, so the character never stands like a mannequin.
        final float swing = Pose.safe(pose.bodyX) * 0.35f;
        // Поднятые руки человека поднимают руки персонажа: плечо уходит вверх на 70 градусов,
        // локоть и кисть слегка догоняют его, чтобы поза выглядела живой, а не поднятой по
        // линейке.
        final float raise = ParamLimits.unit(pose.armY) * 70f;
        model.addRotation(model.humanoid("leftUpperArm"), -raise * 0.25f, 0f, -8f - swing + raise);
        model.addRotation(model.humanoid("rightUpperArm"), -raise * 0.25f, 0f, 8f - swing - raise);
        model.addRotation(model.humanoid("leftLowerArm"), 0f, -6f + raise * 0.18f, 0f);
        model.addRotation(model.humanoid("rightLowerArm"), 0f, 6f - raise * 0.18f, 0f);
        model.addRotation(model.humanoid("leftHand"), raise * 0.12f, 0f, 0f);
        model.addRotation(model.humanoid("rightHand"), raise * 0.12f, 0f, 0f);

        // The eyes look where the user looks; the pupils of the rig are their own bones.
        final float lookX = Pose.safe(pose.eyeBallX) * 12f;
        final float lookY = Pose.safe(pose.eyeBallY) * 10f;
        model.addRotation(model.humanoid("leftEye"), lookX, lookY, 0f);
        model.addRotation(model.humanoid("rightEye"), lookX, lookY, 0f);
    }

    /** Breathing, a slow sway and a blink now and then: the character is never frozen. */
    private void idle() {
        final float breath = (float) Math.sin(clock * 1.35f);
        final float sway = (float) Math.sin(clock * 0.42f);
        // Первый угол - поворот (ось Y модели), второй - кивок (ось X).
        model.addRotation(model.humanoid("chest"), sway * 2.4f, -breath * 1.6f, sway * 1.8f);
        model.addRotation(model.humanoid("neck"), -sway * 2.0f, breath * 1.2f, -sway * 1.4f);
        model.addRotation(model.humanoid("head"), sway * 3.4f, breath * 1.6f, -sway * 2.6f);
        model.addRotation(model.humanoid("leftUpperArm"), 0f, 0f, -9f + breath * 1.2f);
        model.addRotation(model.humanoid("rightUpperArm"), 0f, 0f, 9f - breath * 1.2f);
        // The blink is a short pulse twice a second at most, like a real one.
        final float phase = clock % 4.4f;
        final float blink = phase < 0.14f ? 1f - Math.abs(phase - 0.07f) / 0.07f : 0f;
        model.applyFace(1f - blink, 0f, 0f, 0f);
    }

    /**
     * Draws the character. {@code mirrored} makes it behave like a mirror, exactly like the preview
     * window of the camera.
     */
    public void draw(int width, int height, float zoom, float offsetX, float offsetY, boolean mirrored) {
        if (!ready || model == null || width <= 0 || height <= 0) {
            return;
        }
        final float aspect = (float) width / Math.max(1, height);
        camera.frame(model.poseBounds(), aspect, Math.max(0.05f, zoom), offsetY);
        viewProjection = camera.viewProjection(aspect);
        if (mirrored) {
            // Flip around the vertical axis in clip space: one sign is all a mirror needs.
            for (int i = 0; i < 4; i++) {
                viewProjection[i] = -viewProjection[i];
                viewProjection[8 + i] = -viewProjection[8 + i];
            }
        }
        GLES20.glUseProgram(program);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUniform3f(lightHandle, lightDirection[0], lightDirection[1], lightDirection[2]);

        // Back to front for the transparent pieces, front to back for the solid ones.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < pieces.size(); i++) {
                final Piece piece = pieces.get(i);
                if (pass == 0 ? piece.blend : !piece.blend) {
                    continue;
                }
                drawPiece(piece);
            }
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }

    private void drawPiece(Piece piece) {
        final int count = model.skinPrimitive(piece.mesh, piece.primitive);
        if (count <= 0) {
            return;
        }
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, viewProjection, 0);
        if (piece.texture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, piece.texture);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glUniform1f(hasTextureHandle, 1f);
        } else {
            GLES20.glUniform1f(hasTextureHandle, 0f);
        }
        GLES20.glUniform4f(colorHandle, piece.color[0], piece.color[1], piece.color[2],
                piece.color[3]);
        GLES20.glUniform1f(alphaCutoffHandle, piece.alphaCutoff);
        GLES20.glUniform1f(alphaBlendHandle, piece.blend ? 1f : 0f);
        if (piece.doubleSided) {
            GLES20.glDisable(GLES20.GL_CULL_FACE);
        } else {
            GLES20.glEnable(GLES20.GL_CULL_FACE);
        }
        if (piece.blend) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, piece.vertexBuffer);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * 12,
                toDirectBuffer(model.skinnedPositions()));
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnableVertexAttribArray(positionHandle);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, piece.normalBuffer);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * 12,
                toDirectBuffer(model.skinnedNormals()));
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnableVertexAttribArray(normalHandle);

        if (uvHandle >= 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, piece.uvBuffer);
            GLES20.glEnableVertexAttribArray(uvHandle);
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, piece.indexBuffer);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, piece.indices.length,
                piece.wideIndices ? GLES20.GL_UNSIGNED_INT : GLES20.GL_UNSIGNED_SHORT, 0);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(normalHandle);
        if (uvHandle >= 0) {
            GLES20.glDisableVertexAttribArray(uvHandle);
        }
    }

    private static ByteBuffer directBuffer;
    private static float[] directSource;

    /** Reuses one direct buffer for the whole frame: a phone should not allocate per draw call. */
    private static ByteBuffer toDirectBuffer(float[] data) {
        if (directBuffer == null || directBuffer.capacity() < data.length * 4) {
            directBuffer = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder());
        }
        directSource = data;
        directBuffer.clear();
        directBuffer.asFloatBuffer().put(data);
        directBuffer.position(0);
        directBuffer.limit(data.length * 4);
        return directBuffer;
    }

    public float[] viewProjection() {
        return viewProjection;
    }

    /** Frees the GPU resources; called when the model changes or the context dies. */
    public void release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        for (int i = 0; i < pieces.size(); i++) {
            final Piece piece = pieces.get(i);
            GLES20.glDeleteBuffers(3, new int[]{
                    piece.vertexBuffer, piece.normalBuffer, piece.indexBuffer}, 0);
        }
        for (int i = 0; i < textureIds.size(); i++) {
            final int id = textureIds.get(i).intValue();
            if (id != 0) {
                GLES20.glDeleteTextures(1, new int[]{id}, 0);
            }
        }
        pieces.clear();
        textureIds.clear();
        model = null;
        ready = false;
    }
}
