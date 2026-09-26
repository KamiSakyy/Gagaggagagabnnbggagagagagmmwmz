package com.echidna.studio.three;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A live 3D character: the skeleton of a VRM model, its facial morphs, its pose and its swinging hair.
 *
 * <p>The model class is the 3D twin of {@code EchidnaModel} on the Live2D side. It owns the runtime
 * state - where every joint is, which morph is currently awake, which way the head is turned - and
 * hands the renderer exactly two things: the matrices the skinning shader needs and the vertex data
 * of the face when an expression moves it.</p>
 *
 * <p>Everything here is plain Java without a single Android call, so the whole animation pipeline of
 * the 3D character is covered by unit tests on a plain JVM.</p>
 */
public final class Model3D {

    /** A joint of a spring chain, with the state the simulation carries between frames. */
    private static final class SpringJoint {
        int node;
        float restLength;
        /** Direction to the tail, in the space of the joint, normalised. */
        float[] localTail = {0f, 1f, 0f};
        /** Last simulated tail, in world space, and where it was a frame ago. */
        float[] currentTail = new float[3];
        float[] previousTail = new float[3];
        int[] colliders = new int[0];
        float stiffness = 0.5f;
        float drag = 0.4f;
        float gravityPower;
        float[] gravityDir = {0f, -1f, 0f};
        float hitRadius = 0.02f;
        boolean started;
    }

    private static final class Spring {
        SpringJoint[] joints;
    }

    private final Gltf gltf;

    private final float[][] restTranslation;
    private final float[][] restRotation;
    private final float[][] restScale;
    private final float[][] extraRotation;
    private final float[][] extraTranslation;

    private final float[][] world;

    private final List<Integer> updateOrder = new ArrayList<Integer>();

    /** The active weight of every morph target of every mesh. */
    private final float[][] morphWeights;
    private final float[] expressionTarget;   // scratch: what every target should be this frame
    private final Map<String, List<Gltf.MorphBinding>> expressions;

    private final List<Spring> springs = new ArrayList<Spring>();

    private float[] restBounds = {0f, 0f, 0f, 1f, 1f, 1f};
    private boolean expressionsDirty = true;
    private float clock;

    public Model3D(Gltf gltf) {
        this.gltf = gltf;
        final int count = gltf.nodes.size();
        world = new float[count][16];
        restTranslation = new float[count][];
        restRotation = new float[count][];
        restScale = new float[count][];
        extraRotation = new float[count][];
        extraTranslation = new float[count][];
        for (int i = 0; i < count; i++) {
            final Gltf.Node node = gltf.nodes.get(i);
            if (node.hasMatrix) {
                decompose(node.matrix, i);
            } else {
                restTranslation[i] = node.translation.clone();
                restRotation[i] = Mat4.normalizeQuaternion(node.rotation);
                restScale[i] = node.scale.clone();
            }
            extraRotation[i] = new float[]{0f, 0f, 0f, 1f};
            extraTranslation[i] = new float[]{0f, 0f, 0f};
            Mat4.identity(world[i]);
        }
        buildOrder();
        morphWeights = new float[gltf.meshes.size()][];
        int biggest = 0;
        for (int i = 0; i < gltf.meshes.size(); i++) {
            final int targets = gltf.meshes.get(i).targetCount();
            morphWeights[i] = new float[targets];
            biggest = Math.max(biggest, targets);
        }
        expressionTarget = new float[biggest];
        expressions = gltf.expressions;
        buildSprings();
        updateWorld();
        restBounds = measureBounds();
    }

    // ------------------------------------------------------------------ скелет

    private void decompose(float[] matrix, int index) {
        final float[] translation = {matrix[12], matrix[13], matrix[14]};
        final float[] rotation = new float[4];
        final float[] scale = new float[3];
        for (int c = 0; c < 3; c++) {
            final float length = (float) Math.sqrt(matrix[c * 4] * matrix[c * 4]
                    + matrix[c * 4 + 1] * matrix[c * 4 + 1] + matrix[c * 4 + 2] * matrix[c * 4 + 2]);
            scale[c] = length;
            if (length > 1e-8f) {
                rotation[c] = matrix[c * 4] / length;
            }
        }
        // The quaternion of a rotation matrix, with the scale already divided out.
        final float m00 = scale[0] > 1e-8f ? matrix[0] / scale[0] : 1f;
        final float m01 = scale[0] > 1e-8f ? matrix[1] / scale[0] : 0f;
        final float m02 = scale[0] > 1e-8f ? matrix[2] / scale[0] : 0f;
        final float m10 = scale[1] > 1e-8f ? matrix[4] / scale[1] : 0f;
        final float m11 = scale[1] > 1e-8f ? matrix[5] / scale[1] : 1f;
        final float m12 = scale[1] > 1e-8f ? matrix[6] / scale[1] : 0f;
        final float m20 = scale[2] > 1e-8f ? matrix[8] / scale[2] : 0f;
        final float m21 = scale[2] > 1e-8f ? matrix[9] / scale[2] : 0f;
        final float m22 = scale[2] > 1e-8f ? matrix[10] / scale[2] : 1f;
        final float trace = m00 + m11 + m22;
        float x;
        float y;
        float z;
        float w;
        if (trace > 0f) {
            final float s = (float) Math.sqrt(trace + 1.0f) * 2f;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            final float s = (float) Math.sqrt(1.0f + m00 - m11 - m22) * 2f;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            final float s = (float) Math.sqrt(1.0f + m11 - m00 - m22) * 2f;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            final float s = (float) Math.sqrt(1.0f + m22 - m00 - m11) * 2f;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        restTranslation[index] = translation;
        restRotation[index] = Mat4.normalizeQuaternion(new float[]{x, y, z, w});
        restScale[index] = scale;
    }

    /** Nodes are updated parents first: the world matrix of a child needs the one of its parent. */
    private void buildOrder() {
        updateOrder.clear();
        final boolean[] seen = new boolean[gltf.nodes.size()];
        final List<Integer> stack = new ArrayList<Integer>(gltf.sceneRoots);
        while (!stack.isEmpty()) {
            final int index = stack.remove(stack.size() - 1);
            if (index < 0 || index >= seen.length || seen[index]) {
                continue;
            }
            seen[index] = true;
            updateOrder.add(Integer.valueOf(index));
            final List<Integer> children = gltf.nodes.get(index).children;
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.add(children.get(i));
            }
        }
        for (int i = 0; i < gltf.nodes.size(); i++) {
            if (!seen[i]) {
                updateOrder.add(Integer.valueOf(i));
            }
        }
    }

    private final float[] scratchLocal = new float[16];
    private final float[] scratchWorld = new float[16];
    private final float[] scratchTranslation = new float[3];
    private final float[] scratchRotation = new float[4];
    private final float[] scratchScale = new float[3];

    /** Recomputes every world matrix from the local transforms. */
    public void updateWorld() {
        for (int i = 0; i < updateOrder.size(); i++) {
            final int index = updateOrder.get(i).intValue();
            final Gltf.Node node = gltf.nodes.get(index);
            scratchTranslation[0] = restTranslation[index][0] + extraTranslation[index][0];
            scratchTranslation[1] = restTranslation[index][1] + extraTranslation[index][1];
            scratchTranslation[2] = restTranslation[index][2] + extraTranslation[index][2];
            final float[] rotation = Mat4.multiplyQuaternions(restRotation[index], extraRotation[index]);
            scratchRotation[0] = rotation[0];
            scratchRotation[1] = rotation[1];
            scratchRotation[2] = rotation[2];
            scratchRotation[3] = rotation[3];
            scratchScale[0] = restScale[index][0];
            scratchScale[1] = restScale[index][1];
            scratchScale[2] = restScale[index][2];
            Mat4.fromTrs(scratchLocal, scratchTranslation, scratchRotation, scratchScale);
            if (node.parent >= 0) {
                Mat4.multiply(scratchWorld, world[node.parent], scratchLocal);
                System.arraycopy(scratchWorld, 0, world[index], 0, 16);
            } else {
                System.arraycopy(scratchLocal, 0, world[index], 0, 16);
            }
        }
    }

    public float[] worldMatrix(int node) {
        return world[node];
    }

    public Gltf gltf() {
        return gltf;
    }

    public int nodeIndex(String name) {
        for (int i = 0; i < gltf.nodes.size(); i++) {
            if (gltf.nodes.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Looks a VRM humanoid bone up, tolerating the different spellings of the two VRM versions. */
    public int humanoid(String name) {
        final Integer node = gltf.humanoid.get(name);
        if (node != null) {
            return node.intValue();
        }
        final String[] alternatives = alternativesOf(name);
        for (int i = 0; i < alternatives.length; i++) {
            final Integer other = gltf.humanoid.get(alternatives[i]);
            if (other != null) {
                return other.intValue();
            }
        }
        return -1;
    }

    private static String[] alternativesOf(String name) {
        if ("hips".equals(name)) {
            return new String[]{"hip"};
        }
        if ("spine".equals(name)) {
            return new String[]{"chest", "upperChest"};
        }
        if ("chest".equals(name)) {
            return new String[]{"upperChest", "spine"};
        }
        if ("neck".equals(name)) {
            return new String[]{"head"};
        }
        return new String[0];
    }

    /** Adds a rotation to a bone, on top of the pose the file came with. */
    public void addRotation(int node, float yawDeg, float pitchDeg, float rollDeg) {
        if (node < 0 || node >= gltf.nodes.size()) {
            return;
        }
        final float[] delta = Mat4.quaternionFromEuler(yawDeg, pitchDeg, rollDeg);
        extraRotation[node] = Mat4.normalizeQuaternion(
                Mat4.multiplyQuaternions(extraRotation[node], delta));
        dirty();
    }

    /** Sets the extra rotation of a bone outright. */
    public void setExtraRotation(int node, float[] quaternion) {
        if (node < 0 || node >= gltf.nodes.size()) {
            return;
        }
        extraRotation[node] = Mat4.normalizeQuaternion(quaternion);
        dirty();
    }

    public void resetPose() {
        for (int i = 0; i < extraRotation.length; i++) {
            extraRotation[i] = new float[]{0f, 0f, 0f, 1f};
            extraTranslation[i] = new float[]{0f, 0f, 0f};
        }
        dirty();
    }

    private void dirty() {
        needsWorldUpdate = true;
    }

    private boolean needsWorldUpdate = true;

    // ------------------------------------------------------------------ мимика

    /**
     * Turns a VRM expression on or off. Weights are not applied at once: they are collected and the
     * whole set is resolved together, because one morph target can be driven by several expressions.
     */
    public void setExpression(String preset, float weight) {
        final List<Gltf.MorphBinding> bindings = expressions.get(preset);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        for (int i = 0; i < bindings.size(); i++) {
            final Gltf.MorphBinding binding = bindings.get(i);
            if (binding.mesh < 0 || binding.mesh >= morphWeights.length) {
                continue;
            }
            final float[] weights = morphWeights[binding.mesh];
            if (binding.target < 0 || binding.target >= weights.length) {
                continue;
            }
            weights[binding.target] += weight * binding.weight;
        }
        expressionsDirty = true;
    }

    /**
     * The blink, the mouth and the mood of the model, driven straight from the tracker.
     *
     * <p>The 3D character uses the same signals as the Live2D one: it is the same face in front of the
     * same camera. Blinking is a closed eye; the mouth is the "aa" preset scaled by how far the jaw
     * fell open; a smile puts the "happy" curve on the face.</p>
     */
    public void applyFace(float eyeOpen, float mouthOpen, float smile, float mood) {
        clearExpressions();
        setExpression("blink", clamp01(1.0f - eyeOpen));
        setExpression("aa", clamp01(mouthOpen) * 0.9f);
        if (smile > 0.05f) {
            setExpression("happy", clamp01(smile) * 0.7f);
        }
        if (mood > 0.05f) {
            setExpression("relaxed", clamp01(mood) * 0.5f);
        }
    }

    public void clearExpressions() {
        for (int i = 0; i < morphWeights.length; i++) {
            for (int t = 0; t < morphWeights[i].length; t++) {
                morphWeights[i][t] = 0f;
            }
        }
        expressionsDirty = true;
    }

    /** The weight of one morph target, clamped to the range the shader can use. */
    public float morphWeight(int mesh, int target) {
        if (mesh < 0 || mesh >= morphWeights.length) {
            return 0f;
        }
        final float[] weights = morphWeights[mesh];
        if (target < 0 || target >= weights.length) {
            return 0f;
        }
        return weights[target] < 0f ? 0f : (weights[target] > 1.5f ? 1.5f : weights[target]);
    }

    public boolean hasExpressions() {
        return !expressions.isEmpty();
    }

    public java.util.Set<String> expressionNames() {
        return expressions.keySet();
    }

    public boolean expressionsChanged() {
        final boolean value = expressionsDirty;
        expressionsDirty = false;
        return value;
    }

    // ------------------------------------------------------------------ скиннинг

    /** The matrices the skinning shader needs for one skin: joint world matrix times bind pose. */
    public float[] skinningMatrices(int skin) {
        if (skin < 0 || skin >= gltf.skins.size()) {
            return new float[0];
        }
        final Gltf.Skin skeleton = gltf.skins.get(skin);
        final float[] out = new float[skeleton.joints.length * 16];
        final float[] inverseMesh = new float[16];
        final int meshNode = meshNodeOf(skin);
        if (meshNode >= 0) {
            Mat4.invertAffine(inverseMesh, world[meshNode]);
        } else {
            Mat4.identity(inverseMesh);
        }
        final float[] temp = new float[16];
        final float[] bind = new float[16];
        for (int j = 0; j < skeleton.joints.length; j++) {
            final int joint = skeleton.joints[j];
            if (joint < 0 || joint >= gltf.nodes.size()) {
                for (int k = 0; k < 16; k++) {
                    out[j * 16 + k] = k % 5 == 0 ? 1f : 0f;
                }
                continue;
            }
            Mat4.multiply(temp, inverseMesh, world[joint]);
            System.arraycopy(skeleton.inverseBind, j * 16, bind, 0, 16);
            Mat4.multiply(temp, temp, bind);
            System.arraycopy(temp, 0, out, j * 16, 16);
        }
        return out;
    }

    private int meshNodeOf(int skin) {
        for (int i = 0; i < gltf.meshes.size(); i++) {
            if (gltf.meshes.get(i).skin == skin && gltf.meshes.get(i).node >= 0) {
                return gltf.meshes.get(i).node;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ пружины

    private void buildSprings() {
        for (int s = 0; s < gltf.springs.size(); s++) {
            final Gltf.SpringChain chain = gltf.springs.get(s);
            final Spring spring = new Spring();
            spring.joints = new SpringJoint[chain.joints.length];
            for (int j = 0; j < chain.joints.length; j++) {
                final SpringJoint joint = new SpringJoint();
                joint.node = chain.joints[j];
                joint.stiffness = chain.stiffness[j];
                joint.drag = chain.drag[j];
                joint.gravityPower = chain.gravityPower[j];
                joint.gravityDir = chain.gravityDir[j];
                joint.hitRadius = chain.hitRadius[j];
                if (joint.node < 0 || joint.node >= gltf.nodes.size()) {
                    spring.joints = null;
                    break;
                }
                float[] tail = null;
                if (j + 1 < chain.joints.length) {
                    final int next = chain.joints[j + 1];
                    tail = localOffset(joint.node, next);
                    if (tail != null) {
                        joint.restLength = length(tail);
                    }
                }
                if (tail == null) {
                    // The last joint of a chain has no child joint: it keeps swinging a little in the
                    // direction it was pointing, which is enough for a hair tip.
                    tail = new float[]{0f, -0.03f, 0f};
                    joint.restLength = length(tail);
                }
                joint.localTail = normalize(tail);
                joint.colliders = chain.colliders;
                spring.joints[j] = joint;
            }
            if (spring.joints != null && spring.joints.length > 1) {
                springs.add(spring);
            }
        }
    }

    /** The position of a child node in the local space of its parent. */
    private float[] localOffset(int parent, int child) {
        if (child < 0 || child >= gltf.nodes.size()) {
            return null;
        }
        return restTranslation[child].clone();
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static float[] normalize(float[] v) {
        final float length = length(v);
        if (length < 1e-6f) {
            return new float[]{0f, -1f, 0f};
        }
        return new float[]{v[0] / length, v[1] / length, v[2] / length};
    }

    /**
     * Advances the hair and the clothes of the model.
     *
     * <p>Every chain is a row of joints that behave like beads on a string: each one remembers where it
     * was a frame ago, gets dragged by its own inertia and gravity, is pulled back by the stiffness
     * towards where the head is now, and is pushed out of the head and the body by the colliders.
     * Twenty two chains of the pixiv sample are what makes the character feel alive rather than
     * rigid.</p>
     */
    private void simulateSprings(float dt) {
        if (springs.isEmpty() || dt <= 0f) {
            return;
        }
        final float step = Math.min(dt, 1f / 30f);
        final float[] inverseJoint = new float[16];
        for (int s = 0; s < springs.size(); s++) {
            final Spring spring = springs.get(s);
            for (int j = 0; j < spring.joints.length; j++) {
                final SpringJoint joint = spring.joints[j];
                final float[] jointWorld = jointWorldPosition(joint.node);
                final float[] restTail = add(jointWorld, rotateVector(world[joint.node],
                        scale(joint.localTail, joint.restLength)));
                if (!joint.started) {
                    joint.currentTail = restTail;
                    joint.previousTail = restTail.clone();
                    joint.started = true;
                    continue;
                }
                // Inertia: the tail keeps going where it was going, losing a part of its speed.
                final float[] next = {
                        joint.currentTail[0] + (joint.currentTail[0] - joint.previousTail[0])
                                * (1f - joint.drag),
                        joint.currentTail[1] + (joint.currentTail[1] - joint.previousTail[1])
                                * (1f - joint.drag),
                        joint.currentTail[2] + (joint.currentTail[2] - joint.previousTail[2])
                                * (1f - joint.drag)};
                for (int axis = 0; axis < 3; axis++) {
                    next[axis] += joint.gravityDir[axis] * joint.gravityPower * step * step * 90f;
                }
                // Stiffness pulls the tail back to where the bone it hangs from is pointing now.
                final float pull = clamp(joint.stiffness * step * 26f, 0f, 1f);
                for (int axis = 0; axis < 3; axis++) {
                    next[axis] += (restTail[axis] - next[axis]) * pull;
                }
                pushOutOfColliders(next, joint.hitRadius);
                joint.previousTail = joint.currentTail.clone();
                joint.currentTail = next;

                // The joint turns so that it points at the simulated tail. The angle is measured in
                // the space of the joint itself, which keeps the maths stable whatever the head does,
                // and is damped so that the hair never snaps.
                Mat4.invertAffine(inverseJoint, world[joint.node]);
                final float[] tailLocal = Mat4.transformPoint(inverseJoint,
                        joint.currentTail[0], joint.currentTail[1], joint.currentTail[2]);
                final float[] aim = normalize(tailLocal);
                if (dot(aim, joint.localTail) < 0.99999f) {
                    final float[] target = fromTo(joint.localTail, aim);
                    setExtraRotation(joint.node, damp(target, 0.45f));
                    updateWorld();
                }
            }
        }
    }

    /** Blends a rotation towards "no rotation at all", which is what keeps a spring calm. */
    private static float[] damp(float[] quaternion, float amount) {
        final float w = quaternion[3] >= 0f ? quaternion[3] : -quaternion[3];
        final float angle = 2f * (float) Math.acos(Math.min(1f, w));
        if (angle < 0.02f) {
            return new float[]{0f, 0f, 0f, 1f};
        }
        final float kept = Math.min(1f, amount);
        final float half = angle * 0.5f * kept;
        final float sin = (float) Math.sin(half);
        final float axisLength = (float) Math.sqrt(quaternion[0] * quaternion[0]
                + quaternion[1] * quaternion[1] + quaternion[2] * quaternion[2]);
        if (axisLength < 1e-6f) {
            return new float[]{0f, 0f, 0f, 1f};
        }
        return Mat4.normalizeQuaternion(new float[]{
                quaternion[0] / axisLength * sin,
                quaternion[1] / axisLength * sin,
                quaternion[2] / axisLength * sin,
                (float) Math.cos(half)});
    }

    private void pushOutOfColliders(float[] point, float hitRadius) {
        for (int i = 0; i < gltf.colliders.size(); i++) {
            final Gltf.SpringCollider collider = gltf.colliders.get(i);
            if (collider.node < 0) {
                continue;
            }
            final float[] center = jointWorldPosition(collider.node);
            final float[] offset = rotateVector(world[collider.node], collider.offset);
            final float[] sphere = add(center, offset);
            final float radius = collider.radius + hitRadius;
            final float[] delta = subtract(point, sphere);
            final float distance = length(delta);
            if (distance < radius && distance > 1e-5f) {
                final float scale = radius / distance;
                point[0] = sphere[0] + delta[0] * scale;
                point[1] = sphere[1] + delta[1] * scale;
                point[2] = sphere[2] + delta[2] * scale;
            }
        }
    }

    private float[] jointWorldPosition(int node) {
        return new float[]{world[node][12], world[node][13], world[node][14]};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static float[] subtract(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float[] scale(float[] v, float s) {
        return new float[]{v[0] * s, v[1] * s, v[2] * s};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    /** The shortest rotation that turns one direction into another. */
    static float[] fromTo(float[] from, float[] to) {
        final float d = dot(from, to);
        if (d > 0.99999f) {
            return new float[]{0f, 0f, 0f, 1f};
        }
        if (d < -0.99999f) {
            // Opposite directions: any perpendicular axis is a valid half turn.
            final float[] axis = Math.abs(from[0]) < 0.9f
                    ? cross(from, new float[]{1f, 0f, 0f}) : cross(from, new float[]{0f, 1f, 0f});
            return Mat4.normalizeQuaternion(new float[]{axis[0], axis[1], axis[2], 0f});
        }
        final float[] axis = cross(from, to);
        final float w = 1f + d;
        return Mat4.normalizeQuaternion(new float[]{axis[0], axis[1], axis[2], w});
    }

    /** Rotates a vector by the rotation part of a matrix. */
    private static float[] rotateVector(float[] matrix, float[] v) {
        return new float[]{
                matrix[0] * v[0] + matrix[4] * v[1] + matrix[8] * v[2],
                matrix[1] * v[0] + matrix[5] * v[1] + matrix[9] * v[2],
                matrix[2] * v[0] + matrix[6] * v[1] + matrix[10] * v[2]};
    }

    // ------------------------------------------------------------------ габариты

    /** A box around the model in its rest pose: {@code minX, minY, minZ, maxX, maxY, maxZ}. */
    public float[] restBounds() {
        return restBounds;
    }

    private float[] measureBounds() {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        // The bind pose is enough for framing: it is what the character looks like standing still.
        for (int m = 0; m < gltf.meshes.size(); m++) {
            final Gltf.Mesh mesh = gltf.meshes.get(m);
            for (int p = 0; p < mesh.primitives.size(); p++) {
                final Gltf.Primitive primitive = mesh.primitives.get(p);
                for (int v = 0; v < primitive.vertexCount; v++) {
                    final float x = primitive.positions[v * 3];
                    final float y = primitive.positions[v * 3 + 1];
                    final float z = primitive.positions[v * 3 + 2];
                    if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
                        continue;
                    }
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (minX > maxX) {
            return new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** The box around the model with the current pose applied: used to keep it inside the camera. */
    public float[] poseBounds() {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int m = 0; m < gltf.meshes.size(); m++) {
            final Gltf.Mesh mesh = gltf.meshes.get(m);
            if (mesh.node < 0) {
                continue;
            }
            final float[] matrix = world[mesh.node];
            for (int p = 0; p < mesh.primitives.size(); p++) {
                final Gltf.Primitive primitive = mesh.primitives.get(p);
                for (int v = 0; v < primitive.vertexCount; v += 3) {
                    final float[] point = Mat4.transformPoint(matrix,
                            primitive.positions[v * 3], primitive.positions[v * 3 + 1],
                            primitive.positions[v * 3 + 2]);
                    minX = Math.min(minX, point[0]);
                    minY = Math.min(minY, point[1]);
                    minZ = Math.min(minZ, point[2]);
                    maxX = Math.max(maxX, point[0]);
                    maxY = Math.max(maxY, point[1]);
                    maxZ = Math.max(maxZ, point[2]);
                }
            }
        }
        if (minX > maxX) {
            return restBounds;
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    // ------------------------------------------------------------------ кадр

    /** Advances the character by {@code dt} seconds: pose, springs and skinning. */
    public void update(float dt) {
        clock += dt;
        if (needsWorldUpdate) {
            updateWorld();
            needsWorldUpdate = false;
        }
        simulateSprings(dt);
        updateWorld();
        needsWorldUpdate = false;
    }

    public float clock() {
        return clock;
    }

    // ------------------------------------------------------- данные для шейдера

    private float[] scratchPositions = new float[0];
    private float[] scratchNormals = new float[0];

    /**
     * Skins one primitive on the CPU and leaves the result in {@link #skinnedPositions()}.
     *
     * <p>The skinning is done in Java rather than in the shader on purpose: a vertex shader on
     * OpenGL ES 2.0 cannot index uniform arrays with an attribute, and the joint matrix texture the
     * desktop engines use needs float textures that not every phone has. The processor of a phone
     * skins twenty five thousand vertices in a couple of milliseconds, and the result is a model that
     * looks exactly the same on every device.</p>
     *
     * @return the number of vertices written
     */
    public int skinPrimitive(int meshIndex, int primitiveIndex) {
        if (meshIndex < 0 || meshIndex >= gltf.meshes.size()) {
            return 0;
        }
        final Gltf.Mesh mesh = gltf.meshes.get(meshIndex);
        if (primitiveIndex < 0 || primitiveIndex >= mesh.primitives.size()) {
            return 0;
        }
        final Gltf.Primitive primitive = mesh.primitives.get(primitiveIndex);
        final int count = primitive.vertexCount;
        if (scratchPositions.length < count * 3) {
            scratchPositions = new float[count * 3];
            scratchNormals = new float[count * 3];
        }
        final float[] meshWorld = mesh.node >= 0 ? world[mesh.node] : Mat4.identity();
        final float[] skin = mesh.skin >= 0 ? skinningMatrices(mesh.skin) : null;

        for (int v = 0; v < count; v++) {
            float x = primitive.positions[v * 3];
            float y = primitive.positions[v * 3 + 1];
            float z = primitive.positions[v * 3 + 2];
            // The morph targets of the current expression, applied before the skin.
            for (int t = 0; t < primitive.targets.size(); t++) {
                final float weight = morphWeight(meshIndex, t);
                if (weight == 0f) {
                    continue;
                }
                final float[] target = primitive.targets.get(t);
                x += target[v * 3] * weight;
                y += target[v * 3 + 1] * weight;
                z += target[v * 3 + 2] * weight;
            }
            float nx = primitive.normals == null ? 0f : primitive.normals[v * 3];
            float ny = primitive.normals == null ? 1f : primitive.normals[v * 3 + 1];
            float nz = primitive.normals == null ? 0f : primitive.normals[v * 3 + 2];

            float px = 0f;
            float py = 0f;
            float pz = 0f;
            float wx = 0f;
            float wy = 0f;
            float wz = 0f;
            boolean skinned = false;
            if (skin != null && primitive.joints != null && primitive.weights != null) {
                for (int k = 0; k < 4; k++) {
                    final float weight = primitive.weights[v * 4 + k];
                    if (weight == 0f) {
                        continue;
                    }
                    final int joint = (int) primitive.joints[v * 4 + k];
                    final int base = joint * 16;
                    if (base < 0 || base + 16 > skin.length) {
                        continue;
                    }
                    px += weight * (skin[base] * x + skin[base + 4] * y + skin[base + 8] * z + skin[base + 12]);
                    py += weight * (skin[base + 1] * x + skin[base + 5] * y + skin[base + 9] * z + skin[base + 13]);
                    pz += weight * (skin[base + 2] * x + skin[base + 6] * y + skin[base + 10] * z + skin[base + 14]);
                    wx += weight * (skin[base] * nx + skin[base + 4] * ny + skin[base + 8] * nz);
                    wy += weight * (skin[base + 1] * nx + skin[base + 5] * ny + skin[base + 9] * nz);
                    wz += weight * (skin[base + 2] * nx + skin[base + 6] * ny + skin[base + 10] * nz);
                    skinned = true;
                }
            }
            if (!skinned) {
                px = x;
                py = y;
                pz = z;
                wx = nx;
                wy = ny;
                wz = nz;
            }
            scratchPositions[v * 3] = meshWorld[0] * px + meshWorld[4] * py + meshWorld[8] * pz + meshWorld[12];
            scratchPositions[v * 3 + 1] = meshWorld[1] * px + meshWorld[5] * py + meshWorld[9] * pz + meshWorld[13];
            scratchPositions[v * 3 + 2] = meshWorld[2] * px + meshWorld[6] * py + meshWorld[10] * pz + meshWorld[14];
            final float nwx = meshWorld[0] * wx + meshWorld[4] * wy + meshWorld[8] * wz;
            final float nwy = meshWorld[1] * wx + meshWorld[5] * wy + meshWorld[9] * wz;
            final float nwz = meshWorld[2] * wx + meshWorld[6] * wy + meshWorld[10] * wz;
            final float normalLength = (float) Math.sqrt(nwx * nwx + nwy * nwy + nwz * nwz);
            if (normalLength > 1e-6f) {
                scratchNormals[v * 3] = nwx / normalLength;
                scratchNormals[v * 3 + 1] = nwy / normalLength;
                scratchNormals[v * 3 + 2] = nwz / normalLength;
            } else {
                scratchNormals[v * 3] = 0f;
                scratchNormals[v * 3 + 1] = 1f;
                scratchNormals[v * 3 + 2] = 0f;
            }
        }
        return count;
    }

    public float[] skinnedPositions() {
        return scratchPositions;
    }

    public float[] skinnedNormals() {
        return scratchNormals;
    }

    /** The indices of one primitive, as an int array (the same for every frame). */
    public int[] primitiveIndices(int meshIndex, int primitiveIndex) {
        return gltf.meshes.get(meshIndex).primitives.get(primitiveIndex).indices;
    }

    public Gltf.Primitive primitive(int meshIndex, int primitiveIndex) {
        return gltf.meshes.get(meshIndex).primitives.get(primitiveIndex);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }
}
