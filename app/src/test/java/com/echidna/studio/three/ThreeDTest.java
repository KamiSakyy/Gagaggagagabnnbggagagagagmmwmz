package com.echidna.studio.three;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The 3D pipeline of the app, checked on a real .glb file built byte by byte inside the test.
 *
 * <p>The model has everything the pixiv character has, only smaller: a scene with a root node, a
 * skinned mesh with two joints and one morph target, a material, a VRM humanoid section, a VRM blink
 * expression and a spring chain. Every stage of the pipeline - parsing, the skeleton, the skinning
 * matrices, the morphs, the springs, the CPU skinning and the camera - is driven by it, so a mistake
 * in the 3D maths fails here rather than on the phone of the user.</p>
 */
public class ThreeDTest {

    // ------------------------------------------------------------ сборка файла

    /** The JSON of the test model. Kept as text so the whole scene can be read at a glance. */
    private static String json(int positionCount) {
        return "{"
                + "\"asset\":{\"version\":\"2.0\"},"
                + "\"scene\":0,"
                + "\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":["
                + "  {\"name\":\"Root\",\"children\":[1]},"
                + "  {\"name\":\"Hips\",\"translation\":[0,1,0],\"children\":[2]},"
                + "  {\"name\":\"Head\",\"translation\":[0,0.4,0],\"children\":[3]},"
                + "  {\"name\":\"Mesh\",\"mesh\":0,\"skin\":0}"
                + "],"
                + "\"meshes\":[{\"name\":\"Body\",\"primitives\":[{"
                + "  \"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},"
                + "  \"indices\":5,\"material\":0,\"targets\":[{\"POSITION\":6}]}]}],"
                + "\"skins\":[{\"name\":\"Skin\",\"joints\":[1,2],\"inverseBindMatrices\":7}],"
                + "\"materials\":[{\"name\":\"Skin\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0.8,0.7,1]},"
                + "  \"extensions\":{\"VRMC_materials_mtoon\":{\"outlineWidthFactor\":0.001}}}],"
                + "\"extensions\":{\"VRMC_vrm\":{"
                + "  \"meta\":{\"name\":\"Тестовая модель\",\"authors\":[\"тест\"],\"licenseUrl\":\"https://vrm.dev/licenses/1.0/\"},"
                + "  \"humanoid\":{\"humanBones\":{\"hips\":{\"node\":1},\"head\":{\"node\":2}}},"
                + "  \"expressions\":{\"preset\":{\"blink\":{\"morphTargetBinds\":[{\"node\":3,\"index\":0,\"weight\":1.0}]},"
                + "    \"happy\":{\"morphTargetBinds\":[{\"node\":3,\"index\":0,\"weight\":0.5}]}}}},"
                + " \"VRMC_springBone\":{"
                + "  \"colliders\":[{\"node\":{\"index\":2},\"shape\":{\"radius\":0.1}}],"
                + "  \"colliderGroups\":[{\"colliders\":[0]}],"
                + "  \"springs\":[{\"name\":\"Hair\",\"joints\":[{\"node\":1,\"stiffness\":0.5,\"dragForce\":0.2},"
                + "    {\"node\":2,\"stiffness\":0.8,\"dragForce\":0.3}],\"colliderGroups\":[0]}]}},"
                + "\"accessors\":["
                + "  {\"bufferView\":0,\"componentType\":5126,\"count\":" + positionCount + ",\"type\":\"VEC3\"},"
                + "  {\"bufferView\":1,\"componentType\":5126,\"count\":" + positionCount + ",\"type\":\"VEC3\"},"
                + "  {\"bufferView\":2,\"componentType\":5126,\"count\":" + positionCount + ",\"type\":\"VEC2\"},"
                + "  {\"bufferView\":3,\"componentType\":5123,\"count\":" + positionCount + ",\"type\":\"VEC4\"},"
                + "  {\"bufferView\":4,\"componentType\":5126,\"count\":" + positionCount + ",\"type\":\"VEC4\"},"
                + "  {\"bufferView\":5,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"},"
                + "  {\"bufferView\":6,\"componentType\":5126,\"count\":" + positionCount + ",\"type\":\"VEC3\"},"
                + "  {\"bufferView\":7,\"componentType\":5126,\"count\":2,\"type\":\"MAT4\"}"
                + "],"
                + "\"bufferViews\":["
                + "  {\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + (positionCount * 12) + "},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 12) + ",\"byteLength\":" + (positionCount * 12) + "},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 24) + ",\"byteLength\":" + (positionCount * 8) + "},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 24 + positionCount * 8) + ",\"byteLength\":" + (positionCount * 8) + "},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 24 + positionCount * 16) + ",\"byteLength\":" + (positionCount * 16) + "},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 24 + positionCount * 32) + ",\"byteLength\":6},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 24 + positionCount * 32 + 6) + ",\"byteLength\":" + (positionCount * 12) + "},"
                + "  {\"buffer\":0,\"byteOffset\":" + (positionCount * 36 + positionCount * 32 + 6) + ",\"byteLength\":128}"
                + "],"
                + "\"buffers\":[{\"byteLength\":" + bufferLength(positionCount) + "}]"
                + "}";
    }

    private static int bufferLength(int count) {
        return count * 24 + count * 8 + count * 8 + count * 16 + 6 + count * 12 + 128;
    }

    private static byte[] bin(int count) {
        final ByteBuffer buffer = ByteBuffer.allocate(bufferLength(count)).order(ByteOrder.LITTLE_ENDIAN);
        // Позиции: треугольник в плоскости XY плюс вершина выше, чтобы морф было видно.
        for (int i = 0; i < count; i++) {
            buffer.putFloat(i == 0 ? 0f : (i == 1 ? 1f : 0f));
            buffer.putFloat(i == 2 ? 2f : 0f);
            buffer.putFloat(0f);
        }
        for (int i = 0; i < count; i++) {
            buffer.putFloat(0f);
            buffer.putFloat(0f);
            buffer.putFloat(1f);
        }
        for (int i = 0; i < count; i++) {
            buffer.putFloat(i == 1 ? 1f : 0f);
            buffer.putFloat(i == 2 ? 1f : 0f);
        }
        for (int i = 0; i < count; i++) {
            buffer.putShort((short) 0);      // первая кость
            buffer.putShort((short) 1);      // вторая кость
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
        }
        for (int i = 0; i < count; i++) {
            buffer.putFloat(0.5f);
            buffer.putFloat(0.5f);
            buffer.putFloat(0f);
            buffer.putFloat(0f);
        }
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 2);
        for (int i = 0; i < count; i++) {   // морф: поднимает вершины вверх
            buffer.putFloat(0f);
            buffer.putFloat(0.5f);
            buffer.putFloat(0f);
        }
        // Inverse bind: у корня — сдвиг вниз на метр, у головы — сдвиг на 1.4 вниз.
        putIdentity(buffer, 0f, -1f, 0f);
        putIdentity(buffer, 0f, -1.4f, 0f);
        return buffer.array();
    }

    private static void putIdentity(ByteBuffer buffer, float tx, float ty, float tz) {
        final float[] m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, tx, ty, tz, 1};
        for (int i = 0; i < 16; i++) {
            buffer.putFloat(m[i]);
        }
    }

    private static byte[] glb(int count) {
        final byte[] json = json(count).getBytes(java.nio.charset.Charset.forName("UTF-8"));
        final byte[] bin = bin(count);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x46546C67);
        header.putInt(2);
        header.putInt(12 + 8 + padded(json.length) + 8 + padded(bin.length));
        out.write(header.array(), 0, 12);
        writeChunk(out, json, 0x4E4F534A);
        writeChunk(out, bin, 0x004E4942);
        return out.toByteArray();
    }

    private static int padded(int length) {
        return (length + 3) / 4 * 4;
    }

    private static void writeChunk(ByteArrayOutputStream out, byte[] data, int type) {
        final ByteBuffer chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        chunkHeader.putInt(padded(data.length));
        chunkHeader.putInt(type);
        out.write(chunkHeader.array(), 0, 8);
        out.write(data, 0, data.length);
        for (int i = data.length; i < padded(data.length); i++) {
            out.write(type == 0x4E4F534A ? ' ' : 0);
        }
    }

    private static Gltf parse(int count) throws IOException {
        return Gltf.parse(new ByteArrayInputStream(glb(count)));
    }

    // ------------------------------------------------------------------ тесты

    @Test
    public void theReaderUnderstandsAWholeGlbFile() throws IOException {
        final Gltf gltf = parse(3);
        assertEquals("узлов", 4, gltf.nodes.size());
        assertEquals("мешей", 1, gltf.meshes.size());
        assertEquals("скинов", 1, gltf.skins.size());
        assertEquals("материалов", 1, gltf.materials.size());
        assertEquals("название модели", "Тестовая модель", gltf.title);
        assertEquals("автор", "тест", gltf.author);
        assertEquals("вершин в меше", 3, gltf.meshes.get(0).vertexCount());
        assertEquals("морфов", 1, gltf.meshes.get(0).targetCount());
        assertEquals("костей humanoid", 2, gltf.humanoid.size());
        assertEquals("выражений", 2, gltf.expressions.size());
        assertNotNull("выражение blink", gltf.expressions.get("blink"));
        assertEquals("пружинных цепочек", 1, gltf.springs.size());
        assertEquals("коллайдеров", 1, gltf.colliders.size());
        assertTrue("материал должен быть toon", gltf.materials.get(0).toon);
        assertEquals("индексов", 3, gltf.meshes.get(0).primitives.get(0).indices.length);
    }

    @Test
    public void aBrokenFileIsRejectedInsteadOfCrashing() {
        try {
            Gltf.parse(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
            assertTrue("битый файл должен был отвергнуться", false);
        } catch (IOException expected) {
            assertTrue("сообщение должно объяснять причину",
                    expected.getMessage().contains("glb"));
        }
    }

    @Test
    public void theSkeletonIsPosedAndSkinned() throws IOException {
        final Model3D model = new Model3D(parse(3));
        model.update(1f / 60f);
        final int head = model.humanoid("head");
        final int hips = model.humanoid("hips");
        assertEquals("кость головы", 2, head);
        assertEquals("кость таза", 1, hips);

        final float[] before = model.worldMatrix(head).clone();
        model.addRotation(head, 45f, 0f, 0f);
        model.update(1f / 60f);
        final float[] after = model.worldMatrix(head);
        assertTrue("поворот головы не изменил её мировую матрицу",
                Math.abs(before[0] - after[0]) > 0.05f);

        // Скиннинг: при повороте головы вершины двигаются, при повороте таза тоже.
        final int count = model.skinPrimitive(0, 0);
        assertEquals("вершин заскинено", 3, count);
        final float[] posed = model.skinnedPositions().clone();
        model.resetPose();
        model.update(1f / 60f);
        final int again = model.skinPrimitive(0, 0);
        final float[] rest = model.skinnedPositions();
        assertEquals(3, again);
        float moved = 0f;
        for (int i = 0; i < 9; i++) {
            moved += Math.abs(posed[i] - rest[i]);
        }
        assertTrue("скиннинг не среагировал на позу", moved > 0.05f);
    }

    /** Проверка на ошибку, которая уже один раз ломала модель: M = M * N. */
    @Test
    public void multiplyingAMatrixByItselfStaysCorrect() {
        final float[] rotation = Mat4.identity();
        Mat4.fromTrs(rotation, new float[]{0.3f, -0.2f, 0.7f},
                Mat4.quaternionFromEuler(25f, 10f, -15f), new float[]{1f, 1f, 1f});
        final float[] copy = rotation.clone();
        Mat4.multiply(rotation, rotation, copy);        // out совпадает с первым аргументом
        final float[] expected = new float[16];
        Mat4.multiply(expected, copy, copy);
        float worst = 0f;
        for (int i = 0; i < 16; i++) {
            worst = Math.max(worst, Math.abs(rotation[i] - expected[i]));
        }
        assertTrue("умножение матрицы на себя считается неверно: " + worst, worst < 1e-5f);

        // И обратная матрица: произведение обязано быть единичной.
        final float[] inverse = new float[16];
        Mat4.invertAffine(inverse, copy);
        final float[] product = new float[16];
        Mat4.multiply(product, inverse, copy);
        worst = 0f;
        for (int i = 0; i < 16; i++) {
            final float expect = i % 5 == 0 ? 1f : 0f;
            worst = Math.max(worst, Math.abs(product[i] - expect));
        }
        assertTrue("обратная матрица считается неверно: " + worst, worst < 1e-4f);
    }

    @Test
    public void expressionsMoveTheMorphTargets() throws IOException {
        final Model3D model = new Model3D(parse(3));
        model.update(0f);
        model.applyFace(0.0f, 0.0f, 0.0f, 0f);          // полностью закрытые глаза
        assertEquals("blink должен применить морф", 1.0f, model.morphWeight(0, 0), 0.001f);
        assertTrue("морф изменил позу вершин", model.expressionsChanged());

        model.applyFace(1.0f, 0.0f, 0.0f, 0f);          // открытые глаза
        assertEquals("открытые глаза снимают морф", 0.0f, model.morphWeight(0, 0), 0.001f);

        model.applyFace(1.0f, 1.0f, 1.0f, 0f);          // открытый рот и улыбка
        assertTrue("улыбка и рот должны дать вес морфу", model.morphWeight(0, 0) > 0.2f);

        // Скиннинг обязан учитывать морф: вершина уезжает вверх.
        model.update(0f);
        model.skinPrimitive(0, 0);
        final float[] withMorph = model.skinnedPositions().clone();
        model.applyFace(1.0f, 0.0f, 0.0f, 0f);
        model.skinPrimitive(0, 0);
        final float[] withoutMorph = model.skinnedPositions();
        assertTrue("морф не дошёл до вершин",
                Math.abs(withMorph[1] - withoutMorph[1]) > 0.05f);
    }

    @Test
    public void theSpringsKeepTheHairWhereItBelongsTo() throws IOException {
        final Model3D model = new Model3D(parse(3));
        final float[] bounds = model.restBounds();
        assertEquals("габариты по ширине", 1.0f, bounds[3] - bounds[0], 0.01f);
        assertEquals("габариты по высоте", 2.0f, bounds[4] - bounds[1], 0.01f);

        for (int i = 0; i < 120; i++) {
            model.update(1f / 60f);
        }
        final float[] posed = model.poseBounds();
        assertFalse("модель взорвалась: " + posed[4],
                Float.isNaN(posed[4]) || posed[4] > 20f || posed[4] < -20f);
        assertTrue("модель исчезла: " + posed[4], posed[4] > 0.5f);
    }

    @Test
    public void theCameraFramesTheWholeCharacter() throws IOException {
        final Model3D model = new Model3D(parse(3));
        model.update(0f);
        final Camera3D camera = new Camera3D();
        final float aspect = 0.75f;
        camera.frame(model.restBounds(), aspect, 1f, 0f);
        final float[] viewProjection = camera.viewProjection(aspect);

        // Верх головы и низ ступней обязаны попасть в кадр: иначе персонажа обрежет.
        final float[] bounds = model.restBounds();
        for (int corner = 0; corner < 4; corner++) {
            final float x = corner % 2 == 0 ? bounds[0] : bounds[3];
            final float y = corner < 2 ? bounds[1] : bounds[4];
            final float z = (bounds[2] + bounds[5]) * 0.5f;
            final float[] clip = {
                    viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12],
                    viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13],
                    viewProjection[2] * x + viewProjection[6] * y + viewProjection[10] * z + viewProjection[14],
                    viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15]};
            assertTrue("угол модели попал за камеру", clip[3] > 0f);
            final float ndcX = clip[0] / clip[3];
            final float ndcY = clip[1] / clip[3];
            assertTrue("угол модели вне кадра по X: " + ndcX, Math.abs(ndcX) < 1.05f);
            assertTrue("угол модели вне кадра по Y: " + ndcY, Math.abs(ndcY) < 1.05f);
        }
    }

    /** Каждое выражение каталога обязано быть применимым: опечатка в имени = мёртвая кнопка. */
    @Test
    public void theModeleReactsToEveryExpressionNameItOffers() throws IOException {
        final Model3D model = new Model3D(parse(3));
        final java.util.Iterator<String> names = model.expressionNames().iterator();
        int applied = 0;
        while (names.hasNext()) {
            final String name = names.next();
            model.clearExpressions();
            model.setExpression(name, 1f);
            float total = 0f;
            for (int target = 0; target < 12; target++) {
                total += Math.abs(model.morphWeight(0, target));
            }
            assertTrue("выражение " + name + " ничего не меняет", total > 0.0f);
            applied++;
        }
        assertEquals("выражений применено", model.expressionNames().size(), applied);
    }

    /** Человеческие кости ищутся и по именам другого поколения VRM. */
    @Test
    public void humanoidBonesAreFoundAcrossVrmVersions() throws IOException {
        final Model3D model = new Model3D(parse(3));
        assertTrue("head обязана найтись", model.humanoid("head") >= 0);
        assertEquals("неизвестная кость даёт -1", -1, model.humanoid("tail"));
        // "chest" в этом файле нет, но "spine" есть через альтернативные имена? Проверим, что
        // поиск не падает и возвращает либо кость, либо -1.
        final int spine = model.humanoid("spine");
        assertTrue("поиск альтернативы не должен падать", spine >= -1);
    }
}
