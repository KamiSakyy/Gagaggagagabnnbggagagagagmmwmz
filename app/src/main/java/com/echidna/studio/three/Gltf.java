package com.echidna.studio.three;

import com.echidna.studio.three.Json.Arr;
import com.echidna.studio.three.Json.Obj;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A small but complete glTF 2.0 reader - the format both .glb and .vrm files use.
 *
 * <p>It reads exactly what the 3D stage of this app needs and nothing else: the node tree, skinned
 * meshes with joints, weights and morph targets, materials with their textures, and the VRM
 * extensions that say which bone is the head and which morph closes the eyes. Everything is plain
 * Java, with the small {@link Json} reader of this package, so the reader is testable on a plain JVM
 * and does not pull another megabyte of libraries into the APK.</p>
 */
public final class Gltf {

    private static final int COMPONENT_BYTE = 5120;
    private static final int COMPONENT_UNSIGNED_BYTE = 5121;
    private static final int COMPONENT_SHORT = 5122;
    private static final int COMPONENT_UNSIGNED_SHORT = 5123;
    private static final int COMPONENT_UNSIGNED_INT = 5125;
    private static final int COMPONENT_FLOAT = 5126;

    /** One glTF primitive: a triangle list that shares one material. */
    public static final class Primitive {
        public float[] positions;
        public float[] normals;
        public float[] uvs;
        public float[] joints;      // four joint indices per vertex, as floats
        public float[] weights;     // and their four weights
        public int[] indices;
        public int indexCount;
        public int vertexCount;
        public int material = -1;
        /** Morph targets: {@code targets[t][vertex * 3 + xyz]} - the deltas of a blend shape. */
        public List<float[]> targets = Collections.emptyList();
    }

    /** A glTF mesh: one or more primitives, drawn with one skeleton. */
    public static final class Mesh {
        public String name = "";
        public int skin = -1;
        public int node = -1;
        public final List<Primitive> primitives = new ArrayList<Primitive>();

        public int vertexCount() {
            int total = 0;
            for (int i = 0; i < primitives.size(); i++) {
                total += primitives.get(i).vertexCount;
            }
            return total;
        }

        public int targetCount() {
            return primitives.isEmpty() ? 0 : primitives.get(0).targets.size();
        }
    }

    /** A node of the scene graph: a transform and, sometimes, a mesh. */
    public static final class Node {
        public String name = "";
        public int parent = -1;
        public final List<Integer> children = new ArrayList<Integer>();
        public float[] translation = {0f, 0f, 0f};
        public float[] rotation = {0f, 0f, 0f, 1f};
        public float[] scale = {1f, 1f, 1f};
        /** True when the file stores the transform as a 4x4 matrix instead of T/R/S. */
        public boolean hasMatrix;
        public float[] matrix = new float[16];
        public int mesh = -1;
        public int skin = -1;
    }

    /** A material. The toon shading of this app uses the colour, the texture and the outline hint. */
    public static final class Material {
        public String name = "";
        public float[] baseColor = {1f, 1f, 1f, 1f};
        public int baseColorTexture = -1;
        public boolean doubleSided;
        public String alphaMode = "OPAQUE";
        public float alphaCutoff = 0.5f;
        public float[] emissive = {0f, 0f, 0f};
        /** True for VRM/MToon and unlit materials: they go through the toon ramp, not through PBR. */
        public boolean toon;
        public float[] outlineColor = {0.09f, 0.05f, 0.07f, 1f};
        public float outlineWidth;
    }

    /** A texture with its sampler settings; the pixels stay as the compressed file they came in. */
    public static final class Texture {
        public String name = "";
        public byte[] png;
        public int wrapS = 10497;
        public int wrapT = 10497;
    }

    /** A skeleton: the nodes it drives and the bind pose of every joint. */
    public static final class Skin {
        public String name = "";
        public int[] joints = new int[0];
        public float[] inverseBind = new float[0];   // 16 floats per joint
    }

    /** One morph target of one mesh, the way an expression drives it. */
    public static final class MorphBinding {
        public int mesh = -1;
        public int target;
        public float weight = 1f;
    }

    /** A chain of joints simulated as a spring: the hair and the clothes of the model. */
    public static final class SpringChain {
        public String name = "";
        public int[] joints = new int[0];
        public int[] colliders = new int[0];
        /**
         * Spring settings, one entry per joint. VRM 1.0 stores them on every joint of the chain and
         * VRM 0.x once for the whole chain; both end up here in the same per joint layout.
         */
        public float[] stiffness;
        public float[] drag;
        public float[] gravityPower;
        public float[][] gravityDir;
        public float[] hitRadius;
    }

    /** A sphere the springs must not pass through: the head and the body of the model. */
    public static final class SpringCollider {
        public int node = -1;
        public float radius = 0.05f;
        /** Centre of the sphere in the local space of the node. */
        public float[] offset = {0f, 0f, 0f};
    }

    public final List<Node> nodes = new ArrayList<Node>();
    public final List<Mesh> meshes = new ArrayList<Mesh>();
    public final List<Skin> skins = new ArrayList<Skin>();
    public final List<Material> materials = new ArrayList<Material>();
    public final List<Texture> textures = new ArrayList<Texture>();
    public final List<Integer> sceneRoots = new ArrayList<Integer>();

    /** VRM humanoid bones: {@code "head"} to a node index. */
    public final Map<String, Integer> humanoid = new HashMap<String, Integer>();
    /** VRM expressions: a preset name to the morph weights it drives. */
    public final Map<String, List<MorphBinding>> expressions =
            new HashMap<String, List<MorphBinding>>();
    public final List<SpringChain> springs = new ArrayList<SpringChain>();
    public final List<SpringCollider> colliders = new ArrayList<SpringCollider>();
    public String title = "";
    public String author = "";
    public String license = "";

    private final byte[] bin;
    private final Arr accessors;
    private final Arr bufferViews;

    private Gltf(Obj json, byte[] bin)  {
        this.bin = bin;
        this.accessors = json.optArray("accessors");
        this.bufferViews = json.optArray("bufferViews");
        readNodes(json);
        readSkins(json);
        readMeshes(json);
        readMaterials(json);
        readTextures(json);
        readScene(json);
        readVrm(json);
    }

    /** Reads a .glb (or .vrm, which is a .glb with extensions) from a stream. */
    public static Gltf parse(InputStream stream) throws IOException {
        final byte[] data = readAll(stream);
        if (data.length < 20 || data[0] != 'g' || data[1] != 'l' || data[2] != 'T' || data[3] != 'F') {
            throw new IOException("это не glb: нет сигнатуры glTF");
        }
        final ByteBuffer header = ByteBuffer.wrap(data, 0, 12).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt();                     // magic
        header.getInt();                     // version
        header.getInt();                     // total length
        int offset = 12;
        Obj json = null;
        byte[] bin = new byte[0];
        while (offset + 8 <= data.length) {
            final ByteBuffer chunkHeader =
                    ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN);
            final int length = chunkHeader.getInt();
            final int type = chunkHeader.getInt();
            offset += 8;
            if (length < 0 || offset + length > data.length) {
                throw new IOException("чанк glb обрезан");
            }
            if (type == 0x4E4F534A) {        // "JSON"
                try {
                    json = Json.parseObject(new String(data, offset, length, "UTF-8"));
                } catch (Json.JsonError e) {
                    throw new IOException("не разобрать JSON модели: " + e.getMessage());
                }
            } else if (type == 0x004E4942) { // "BIN\0"
                bin = new byte[length];
                System.arraycopy(data, offset, bin, 0, length);
            }
            offset += length;
        }
        if (json == null) {
            throw new IOException("в glb нет блока JSON");
        }
        try {
            return new Gltf(json, bin);
        } catch (Json.JsonError e) {
            throw new IOException("модель повреждена: " + e.getMessage());
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        final byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ чтение

    private void readNodes(Obj json) {
        final Arr array = json.optArray("nodes");
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final Obj o = array.optObject(i);
            if (o == null) {
                continue;
            }
            final Node node = new Node();
            node.name = o.optString("name", "");
            node.mesh = o.optInt("mesh", -1);
            node.skin = o.optInt("skin", -1);
            if (o.has("matrix")) {
                node.hasMatrix = true;
                node.matrix = floatsOr(o.optArray("matrix"), node.matrix);
            } else {
                node.translation = floatsOr(o.optArray("translation"), node.translation);
                node.rotation = floatsOr(o.optArray("rotation"), node.rotation);
                node.scale = floatsOr(o.optArray("scale"), node.scale);
            }
            final Arr children = o.optArray("children");
            for (int c = 0; children != null && c < children.length(); c++) {
                node.children.add(Integer.valueOf(children.optInt(c, -1)));
            }
            nodes.add(node);
        }
        for (int i = 0; i < nodes.size(); i++) {
            final Node parent = nodes.get(i);
            for (int c = 0; c < parent.children.size(); c++) {
                final int child = parent.children.get(c).intValue();
                if (child >= 0 && child < nodes.size()) {
                    nodes.get(child).parent = i;
                }
            }
        }
    }

    private void readSkins(Obj json) {
        final Arr array = json.optArray("skins");
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final Obj o = array.optObject(i);
            if (o == null) {
                continue;
            }
            final Skin skin = new Skin();
            skin.name = o.optString("name", "");
            final Arr joints = o.optArray("joints");
            skin.joints = new int[joints == null ? 0 : joints.length()];
            for (int j = 0; joints != null && j < joints.length(); j++) {
                skin.joints[j] = joints.optInt(j, -1);
            }
            if (o.has("inverseBindMatrices")) {
                final float[] ibm = accessorFloats(o.optInt("inverseBindMatrices", -1));
                if (ibm != null && ibm.length == skin.joints.length * 16) {
                    skin.inverseBind = ibm;
                }
            }
            if (skin.inverseBind.length != skin.joints.length * 16) {
                skin.inverseBind = new float[skin.joints.length * 16];
                for (int j = 0; j < skin.joints.length; j++) {
                    identityInto(skin.inverseBind, j * 16);
                }
            }
            skins.add(skin);
        }
    }

    private void readMeshes(Obj json) {
        final Arr array = json.optArray("meshes");
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final Obj o = array.optObject(i);
            if (o == null) {
                continue;
            }
            final Mesh mesh = new Mesh();
            mesh.name = o.optString("name", "");
            final Arr primitives = o.optArray("primitives");
            for (int p = 0; primitives != null && p < primitives.length(); p++) {
                final Obj primitive = primitives.optObject(p);
                if (primitive == null || primitive.optInt("mode", 4) != 4) {
                    continue;   // только треугольники: линии и точки модели не нужны
                }
                final Obj attributes = primitive.optObject("attributes");
                if (attributes == null || !attributes.has("POSITION")) {
                    continue;
                }
                final Primitive out = new Primitive();
                out.positions = accessorFloats(attributes.optInt("POSITION", -1));
                if (out.positions == null) {
                    continue;
                }
                out.vertexCount = out.positions.length / 3;
                if (attributes.has("NORMAL")) {
                    out.normals = accessorFloats(attributes.optInt("NORMAL", -1));
                }
                if (attributes.has("TEXCOORD_0")) {
                    out.uvs = accessorFloats(attributes.optInt("TEXCOORD_0", -1));
                }
                if (attributes.has("JOINTS_0")) {
                    out.joints = accessorFloats(attributes.optInt("JOINTS_0", -1));
                }
                if (attributes.has("WEIGHTS_0")) {
                    out.weights = accessorFloats(attributes.optInt("WEIGHTS_0", -1));
                }
                out.material = primitive.optInt("material", -1);
                if (primitive.has("indices")) {
                    out.indices = accessorInts(primitive.optInt("indices", -1));
                }
                if (out.indices == null) {
                    out.indices = new int[out.vertexCount];
                    for (int v = 0; v < out.vertexCount; v++) {
                        out.indices[v] = v;
                    }
                }
                out.indexCount = out.indices.length;
                final Arr targets = primitive.optArray("targets");
                if (targets != null && targets.length() > 0) {
                    final List<float[]> morphs = new ArrayList<float[]>(targets.length());
                    for (int t = 0; t < targets.length(); t++) {
                        final Obj target = targets.optObject(t);
                        final float[] morph = target != null && target.has("POSITION")
                                ? accessorFloats(target.optInt("POSITION", -1)) : null;
                        morphs.add(morph != null && morph.length == out.vertexCount * 3
                                ? morph : new float[out.vertexCount * 3]);
                    }
                    out.targets = morphs;
                }
                mesh.primitives.add(out);
            }
            meshes.add(mesh);
        }
        // Every mesh remembers the node (and the skeleton) it is drawn at.
        for (int n = 0; n < nodes.size(); n++) {
            final Node node = nodes.get(n);
            if (node.mesh >= 0 && node.mesh < meshes.size()) {
                final Mesh mesh = meshes.get(node.mesh);
                mesh.node = n;
                if (node.skin >= 0) {
                    mesh.skin = node.skin;
                }
            }
        }
    }

    private void readMaterials(Obj json) {
        final Arr array = json.optArray("materials");
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final Obj o = array.optObject(i);
            if (o == null) {
                continue;
            }
            final Material material = new Material();
            material.name = o.optString("name", "");
            material.doubleSided = o.optBoolean("doubleSided", false);
            material.alphaMode = o.optString("alphaMode", "OPAQUE");
            material.alphaCutoff = (float) o.optDouble("alphaCutoff", 0.5);
            final Obj pbr = o.optObject("pbrMetallicRoughness");
            if (pbr != null) {
                material.baseColor = floatsOr(pbr.optArray("baseColorFactor"), material.baseColor);
                final Obj texture = pbr.optObject("baseColorTexture");
                if (texture != null) {
                    material.baseColorTexture = texture.optInt("index", -1);
                }
            }
            material.emissive = floatsOr(o.optArray("emissiveFactor"), material.emissive);
            final Obj extensions = o.optObject("extensions");
            if (extensions != null) {
                material.toon = extensions.has("VRMC_materials_mtoon")
                        || extensions.has("KHR_materials_unlit") || extensions.has("VRM");
                final Obj mtoon = extensions.optObject("VRMC_materials_mtoon");
                if (mtoon != null) {
                    material.outlineWidth = (float) mtoon.optDouble("outlineWidthFactor", 0.0);
                    material.outlineColor = floatsOr(mtoon.optArray("outlineColorFactor"),
                            material.outlineColor);
                }
            }
            materials.add(material);
        }
    }

    private void readTextures(Obj json) {
        final Arr images = json.optArray("images");
        final Arr sources = json.optArray("textures");
        final Arr samplers = json.optArray("samplers");
        if (images == null || sources == null) {
            return;
        }
        final byte[][] imageData = new byte[images.length()][];
        for (int i = 0; i < images.length(); i++) {
            final Obj image = images.optObject(i);
            if (image == null || !image.has("bufferView") || bufferViews == null) {
                continue;   // внешние файлы не поддерживаются: модель лежит одним куском
            }
            final Obj view = bufferViews.optObject(image.optInt("bufferView", -1));
            if (view == null) {
                continue;
            }
            final int offset = view.optInt("byteOffset", 0);
            final int length = view.optInt("byteLength", 0);
            if (offset >= 0 && length > 0 && offset + length <= bin.length) {
                imageData[i] = new byte[length];
                System.arraycopy(bin, offset, imageData[i], 0, length);
            }
        }
        for (int i = 0; i < sources.length(); i++) {
            final Texture texture = new Texture();
            final Obj source = sources.optObject(i);
            if (source != null) {
                texture.name = source.optString("name", "");
                final int image = source.optInt("source", -1);
                if (image >= 0 && image < imageData.length) {
                    texture.png = imageData[image];
                }
                final int sampler = source.optInt("sampler", -1);
                if (samplers != null) {
                    final Obj s = samplers.optObject(sampler);
                    if (s != null) {
                        texture.wrapS = s.optInt("wrapS", 10497);
                        texture.wrapT = s.optInt("wrapT", 10497);
                    }
                }
            }
            textures.add(texture);
        }
    }

    private void readScene(Obj json) {
        final Obj chosen = json.optArray("scenes") == null ? null
                : json.optArray("scenes").optObject(json.optInt("scene", 0));
        final Arr roots = chosen == null ? null : chosen.optArray("nodes");
        for (int i = 0; roots != null && i < roots.length(); i++) {
            sceneRoots.add(Integer.valueOf(roots.optInt(i, -1)));
        }
        if (sceneRoots.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).parent < 0) {
                    sceneRoots.add(Integer.valueOf(i));
                }
            }
        }
    }

    /**
     * Reads what makes a VRM a character rather than a statue: which node is the head, which morph
     * closes the eyes, and which chains of joints are hair that should swing.
     */
    private void readVrm(Obj json) {
        final Obj extensions = json.optObject("extensions");
        if (extensions == null) {
            return;
        }
        final boolean vrm0 = extensions.has("VRM") && !extensions.has("VRMC_vrm");
        final Obj vrm = vrm0 ? extensions.optObject("VRM") : extensions.optObject("VRMC_vrm");
        if (vrm == null) {
            return;
        }
        readHumanoid(vrm);
        readExpressions(vrm);
        final Obj meta = vrm.optObject("meta");
        if (meta != null) {
            title = meta.optString("name", "");
            final Arr authors = meta.optArray("authors");
            author = authors != null && authors.length() > 0
                    ? authors.optString(0, "") : meta.optString("author", "");
            license = meta.optString("licenseUrl", meta.optString("otherLicenseUrl", ""));
        }
        final Obj spring = vrm0 ? extensions.optObject("VRM")
                : extensions.optObject("VRMC_springBone");
        if (spring != null) {
            readSpringBones(spring, vrm0);
        }
    }

    private void readHumanoid(Obj vrm) {
        final Obj humanoidSection = vrm.optObject("humanoid");
        final Obj bones = humanoidSection == null ? null : humanoidSection.optObject("humanBones");
        if (bones == null) {
            return;
        }
        final Iterator<String> keys = bones.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final Object value = bones.get(key);
            int node = -1;
            if (value instanceof Map) {
                final Obj object = new Obj(Json.asMap(value));
                node = object.has("node") ? object.optInt("node", -1) : object.optInt("index", -1);
            } else if (value instanceof Number) {
                node = ((Number) value).intValue();
            }
            if (node >= 0 && node < nodes.size()) {
                humanoid.put(key, Integer.valueOf(node));
            }
        }
    }

    private void readExpressions(Obj vrm) {
        final Obj expressionSection = vrm.optObject("expressions");
        if (expressionSection == null) {
            return;
        }
        final Obj preset = expressionSection.optObject("preset");
        final Obj custom = expressionSection.optObject("custom");
        final Obj[] groups = {preset, custom};
        for (int g = 0; g < groups.length; g++) {
            final Obj group = groups[g];
            if (group == null) {
                continue;
            }
            final Iterator<String> names = group.keys();
            while (names.hasNext()) {
                final String name = names.next();
                final Object raw = group.get(name);
                if (!(raw instanceof Map)) {
                    continue;
                }
                final Obj expression = new Obj(Json.asMap(raw));
                final List<MorphBinding> bindings = new ArrayList<MorphBinding>();
                final Arr morphs = expression.optArray("morphTargetBinds");
                for (int i = 0; morphs != null && i < morphs.length(); i++) {
                    final Obj bind = morphs.optObject(i);
                    if (bind == null) {
                        continue;
                    }
                    final MorphBinding binding = new MorphBinding();
                    // VRM 1.0 binds by node, VRM 0.x by mesh: the node carries the mesh anyway.
                    if (bind.has("node")) {
                        final int node = bind.optInt("node", -1);
                        binding.mesh = node >= 0 && node < nodes.size() ? nodes.get(node).mesh : -1;
                    } else {
                        binding.mesh = bind.optInt("mesh", -1);
                    }
                    binding.target = bind.optInt("index", bind.optInt("target", 0));
                    binding.weight = (float) bind.optDouble("weight", 1.0);
                    if (binding.mesh >= 0 && binding.mesh < meshes.size()) {
                        bindings.add(binding);
                    }
                }
                if (!bindings.isEmpty()) {
                    expressions.put(name, bindings);
                }
            }
        }
    }

    private void readSpringBones(Obj spring, boolean vrm0) {
        // The collider list keeps its positions: a spring refers to its colliders by index, so an
        // entry that is not usable has to stay in place (with node -1) instead of being dropped.
        final Arr colliders = spring.optArray("colliders");
        for (int i = 0; colliders != null && i < colliders.length(); i++) {
            final SpringCollider collider = new SpringCollider();
            final Obj o = colliders.optObject(i);
            if (o != null) {
                final Obj shape = o.optObject("shape");
                collider.radius = shape != null
                        ? (float) shape.optDouble("radius", 0.05)
                        : (float) o.optDouble("radius", 0.05);
                final Obj nodeObject = o.optObject("node");
                collider.node = nodeObject != null
                        ? nodeObject.optInt("index", -1) : o.optInt("node", -1);
                collider.offset = floatsOr(o.optArray("offset"), collider.offset);
            }
            if (collider.node < 0 || collider.node >= nodes.size()) {
                collider.node = -1;
            }
            this.colliders.add(collider);
        }

        final Arr colliderGroups = spring.optArray("colliderGroups");
        final Arr springs = spring.optArray("springs");
        for (int i = 0; springs != null && i < springs.length(); i++) {
            final Obj o = springs.optObject(i);
            if (o == null) {
                continue;
            }
            final SpringChain chain = new SpringChain();
            chain.name = o.optString("name", "spring" + i);
            final Arr jointArray = o.optArray("joints");
            chain.joints = readJointList(jointArray);
            if (chain.joints.length < 2) {
                continue;
            }
            // VRM 0.x keeps one set of values for the chain; VRM 1.0 puts them on the joints.
            final float chainStiffness = (float) o.optDouble("stiffness", 0.5);
            final float chainDrag = (float) o.optDouble("dragForce", o.optDouble("drag", 0.4));
            final float chainGravity = (float) o.optDouble("gravityPower", 0.0);
            final float chainHitRadius = (float) o.optDouble("hitRadius", 0.02);
            final float[] chainGravityDir = floatsOr(o.optArray("gravityDir"), new float[]{0f, -1f, 0f});
            chain.stiffness = new float[chain.joints.length];
            chain.drag = new float[chain.joints.length];
            chain.gravityPower = new float[chain.joints.length];
            chain.gravityDir = new float[chain.joints.length][];
            chain.hitRadius = new float[chain.joints.length];
            for (int j = 0; j < chain.joints.length; j++) {
                final Obj joint = jointArray == null ? null : jointArray.optObject(j);
                final float fallbackStiffness = j == 0 ? chainStiffness : chainStiffness;
                chain.stiffness[j] = joint == null ? fallbackStiffness
                        : (float) joint.optDouble("stiffness", fallbackStiffness);
                chain.drag[j] = joint == null ? chainDrag
                        : (float) joint.optDouble("dragForce", chainDrag);
                chain.gravityPower[j] = joint == null ? chainGravity
                        : (float) joint.optDouble("gravityPower", chainGravity);
                chain.hitRadius[j] = joint == null ? chainHitRadius
                        : (float) joint.optDouble("hitRadius", chainHitRadius);
                chain.gravityDir[j] = joint == null ? chainGravityDir
                        : floatsOr(joint.optArray("gravityDir"), chainGravityDir);
            }
            final List<Integer> groupColliders = new ArrayList<Integer>();
            final Arr groups = o.optArray("colliderGroups");
            for (int c = 0; groups != null && c < groups.length(); c++) {
                final Obj group = colliderGroups == null
                        ? null : colliderGroups.optObject(groups.optInt(c, -1));
                final Arr list = group == null ? null : group.optArray("colliders");
                for (int k = 0; list != null && k < list.length(); k++) {
                    final int collider = list.optInt(k, -1);
                    if (collider >= 0 && collider < this.colliders.size()) {
                        groupColliders.add(Integer.valueOf(collider));
                    }
                }
            }
            chain.colliders = new int[groupColliders.size()];
            for (int c = 0; c < groupColliders.size(); c++) {
                chain.colliders[c] = groupColliders.get(c).intValue();
            }
            this.springs.add(chain);
        }
    }

    private int[] readJointList(Arr joints) {
        if (joints == null) {
            return new int[0];
        }
        final int[] out = new int[joints.length()];
        for (int j = 0; j < joints.length(); j++) {
            final Object value = joints.opt(j);
            if (value instanceof Map) {
                out[j] = new Obj(Json.asMap(value)).optInt("node", -1);
            } else if (value instanceof Number) {
                out[j] = ((Number) value).intValue();
            } else {
                out[j] = -1;
            }
        }
        return out;
    }

    // ------------------------------------------------------------- аксессоры

    private static float[] floatsOr(Arr array, float[] fallback) {
        if (array == null) {
            return fallback;
        }
        final float[] out = new float[array.length()];
        for (int i = 0; i < array.length(); i++) {
            out[i] = (float) array.optDouble(i, 0.0);
        }
        return out;
    }

    private static void identityInto(float[] out, int offset) {
        for (int i = 0; i < 16; i++) {
            out[offset + i] = 0f;
        }
        out[offset] = 1f;
        out[offset + 5] = 1f;
        out[offset + 10] = 1f;
        out[offset + 15] = 1f;
    }

    private Accessor accessor(int index) {
        if (accessors == null || index < 0 || index >= accessors.length()) {
            return null;
        }
        final Obj o = accessors.optObject(index);
        if (o == null || !o.has("bufferView")) {
            return null;
        }
        final Obj view = bufferViews == null
                ? null : bufferViews.optObject(o.optInt("bufferView", -1));
        if (view == null) {
            return null;
        }
        final Accessor accessor = new Accessor();
        accessor.count = o.optInt("count", 0);
        accessor.components = componentsOf(o.optString("type", "SCALAR"));
        accessor.componentType = o.optInt("componentType", COMPONENT_FLOAT);
        accessor.normalized = o.optBoolean("normalized", false);
        accessor.byteOffset = view.optInt("byteOffset", 0) + o.optInt("byteOffset", 0);
        final int stride = view.optInt("byteStride", 0);
        accessor.byteStride = stride > 0 ? stride : accessor.components * componentSize(accessor.componentType);
        return accessor;
    }

    private static int componentsOf(String type) {
        if ("VEC2".equals(type)) {
            return 2;
        }
        if ("VEC3".equals(type)) {
            return 3;
        }
        if ("VEC4".equals(type) || "MAT2".equals(type)) {
            return 4;
        }
        if ("MAT3".equals(type)) {
            return 9;
        }
        if ("MAT4".equals(type)) {
            return 16;
        }
        return 1;
    }

    private static int componentSize(int componentType) {
        switch (componentType) {
            case COMPONENT_FLOAT:
            case COMPONENT_UNSIGNED_INT:
                return 4;
            case COMPONENT_UNSIGNED_SHORT:
            case COMPONENT_SHORT:
                return 2;
            default:
                return 1;
        }
    }

    private float[] accessorFloats(int index) {
        final Accessor accessor = accessor(index);
        if (accessor == null) {
            return null;
        }
        final float[] out = new float[accessor.count * accessor.components];
        for (int i = 0; i < accessor.count; i++) {
            for (int c = 0; c < accessor.components; c++) {
                out[i * accessor.components + c] = accessor.readFloat(i, c);
            }
        }
        return out;
    }

    private int[] accessorInts(int index) {
        final Accessor accessor = accessor(index);
        if (accessor == null) {
            return null;
        }
        final int[] out = new int[accessor.count * accessor.components];
        for (int i = 0; i < accessor.count; i++) {
            for (int c = 0; c < accessor.components; c++) {
                out[i * accessor.components + c] = (int) accessor.readFloat(i, c);
            }
        }
        return out;
    }

    /** A view on one typed array inside the binary chunk. */
    private final class Accessor {
        int count;
        int components;
        int componentType;
        int byteOffset;
        int byteStride;
        boolean normalized;

        float readFloat(int element, int component) {
            final int offset = byteOffset + element * byteStride
                    + component * componentSize(componentType);
            if (offset < 0 || offset + 4 > bin.length) {
                return 0f;
            }
            switch (componentType) {
                case COMPONENT_FLOAT:
                    return ByteBuffer.wrap(bin, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                case COMPONENT_UNSIGNED_SHORT: {
                    final int value = ByteBuffer.wrap(bin, offset, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                    return normalized ? value / 65535.0f : value;
                }
                case COMPONENT_SHORT:
                    return ByteBuffer.wrap(bin, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
                case COMPONENT_UNSIGNED_BYTE: {
                    final int value = bin[offset] & 0xFF;
                    return normalized ? value / 255.0f : value;
                }
                case COMPONENT_BYTE:
                    return bin[offset];
                case COMPONENT_UNSIGNED_INT:
                    return (float) (ByteBuffer.wrap(bin, offset, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL);
                default:
                    return 0f;
            }
        }
    }
}
