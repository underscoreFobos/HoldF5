package dev.fobium.holdf5.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Set;

/**
 * Motion blur post-processing effect. Uses reflection to handle the
 * PostEffectProcessor API which changed significantly across 1.21.x versions:
 *
 * Era 1 (1.21-1.21.1): JSON-based PostEffectProcessor(TextureManager, ResourceFactory, Framebuffer, Identifier)
 * Era 2-3 (1.21.2-1.21.5): Pipeline-based without persistent targets — blur not supported, gracefully disabled
 * Era 4 (1.21.6-1.21.11): Pipeline-based with ProjectionMatrix2 and persistent targets — CODEC approach
 *
 * IMPORTANT: Loom does NOT remap string literals passed to Class.forName/getMethod/getField.
 * Only direct class references (e.g. Identifier.class) and direct method calls are remapped
 * in the bytecode. All 1.21 classes MUST be referenced via direct imports, not Class.forName.
 * Methods on 1.21 classes MUST be discovered structurally (by param types/count), not by name.
 * Classes NOT in 1.21 (Category, ProjectionMatrix2, etc.) are discovered from runtime
 * constructor/method parameter types of known 1.21 classes.
 *
 * Gracefully disables itself on incompatible versions.
 */
public class Holdf5MotionBlur {
    private static AutoCloseable processor;
    private static AutoCloseable projectionMatrix; // Era 4 only
    private static float lastStrength = -1f;
    private static boolean unavailable = false;
    private static boolean warmedUp = false;

    // Era: 1 = JSON (1.21-1.21.1), 4 = Pipeline+ProjectionMatrix2 (1.21.6+), 0 = unsupported (1.21.2-1.21.5)
    private static int era = -1;

    // Cached reflection handles
    private static Method renderMethod;
    private static Method setUniformsMethod;    // Era 1 only
    private static Method setupDimensionsMethod; // Era 1 only
    private static Method parseEffectMethod;     // Era 4 only
    private static Object objectAllocatorTrivial; // Era 4 only
    private static Class<?> pipelineCls;         // Era 4: PostEffectPipeline (discovered at runtime)
    private static Class<?> pm2Cls;              // Era 4: ProjectionMatrix2 (discovered at runtime)
    private static Class<?> oaCls;               // Era 4: ObjectAllocator (discovered at runtime)
    private static boolean reflectionInitialized = false;

    private static int detectEra() {
        // Era 1: Has the 4-arg JSON constructor (TM, RF, FB, Identifier)
        // Uses direct class literals — Loom remaps these in bytecode.
        try {
            PostEffectProcessor.class.getConstructor(
                    TextureManager.class, ResourceFactory.class, Framebuffer.class, Identifier.class);
            return 1;
        } catch (Throwable ignored) {}

        // Era 4: Has a static 5-param method returning PostEffectProcessor (parseEffect).
        // Discovered structurally — no name strings needed.
        try {
            for (Method m : PostEffectProcessor.class.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 5
                        && PostEffectProcessor.class.isAssignableFrom(m.getReturnType())) {
                    return 4;
                }
            }
        } catch (Throwable ignored) {}

        // Era 2-3: Pipeline without persistent targets — not supported
        return 0;
    }

    public static void render() {
        Holdf5Config config = Holdf5Client.getConfig();
        if (!Holdf5Client.isMotionBlurActive()) {
            return;
        }
        if (unavailable) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getFramebuffer() == null) return;

        if (processor == null || lastStrength != config.motionBlurStrength) {
            cleanup();
            try {
                createProcessor(client, config.motionBlurStrength);
                lastStrength = config.motionBlurStrength;
                warmedUp = false;
            } catch (Throwable e) {
                System.err.println("[HoldF5] Motion blur unavailable on this MC version: " + e.getMessage());
                unavailable = true;
                return;
            }
        }

        try {
            if (era == 1) {
                renderEra1();
            } else if (era == 4) {
                renderEra4(client);
            }
        } catch (Throwable e) {
            System.err.println("[HoldF5] Motion blur render failed: " + e.getMessage());
            cleanup();
            unavailable = true;
        }
    }

    private static void renderEra1() throws Exception {
        if (!warmedUp) {
            setUniformsMethod.invoke(processor, "Strength", 0f);
            renderMethod.invoke(processor, 0f);
            setUniformsMethod.invoke(processor, "Strength", lastStrength);
            warmedUp = true;
            return;
        }
        renderMethod.invoke(processor, 0f);
    }

    private static void renderEra4(MinecraftClient client) throws Exception {
        renderMethod.invoke(processor, client.getFramebuffer(), objectAllocatorTrivial);
    }

    private static void initReflection() throws Exception {
        if (reflectionInitialized) return;

        if (era == -1) era = detectEra();
        if (era == 0) throw new UnsupportedOperationException(
                "Motion blur requires MC 1.21-1.21.1 or 1.21.6+");

        if (era == 1) {
            // Discover methods structurally — getMethod("render", ...) would pass
            // the yarn name as a string literal, which Loom does NOT remap.

            // render(float): non-static, single float param
            for (Method m : PostEffectProcessor.class.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    renderMethod = m;
                    break;
                }
            }
            // setUniforms(String, float): non-static, (String, float) params
            for (Method m : PostEffectProcessor.class.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class
                        && m.getParameterTypes()[1] == float.class) {
                    setUniformsMethod = m;
                    break;
                }
            }
            // setupDimensions(int, int): non-static, (int, int) params
            for (Method m : PostEffectProcessor.class.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == int.class
                        && m.getParameterTypes()[1] == int.class) {
                    setupDimensionsMethod = m;
                    break;
                }
            }
        } else if (era == 4) {
            // Discover parseEffect: static, 5 params, returns PostEffectProcessor.
            // All era-4-only classes are extracted from its parameter types —
            // no Class.forName needed for names absent from build-time mappings.
            for (Method m : PostEffectProcessor.class.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 5
                        && PostEffectProcessor.class.isAssignableFrom(m.getReturnType())) {
                    parseEffectMethod = m;
                    break;
                }
            }
            if (parseEffectMethod == null)
                throw new UnsupportedOperationException("parseEffect not found on PostEffectProcessor");

            // parseEffect(PostEffectPipeline, TextureManager, Set, Identifier, ProjectionMatrix2)
            Class<?>[] peParams = parseEffectMethod.getParameterTypes();
            pipelineCls = peParams[0]; // PostEffectPipeline
            pm2Cls = peParams[4];      // ProjectionMatrix2

            // Discover render: non-static, 2 params, first is Framebuffer
            for (Method m : PostEffectProcessor.class.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == Framebuffer.class) {
                    renderMethod = m;
                    break;
                }
            }
            if (renderMethod == null)
                throw new UnsupportedOperationException("render(Framebuffer, ObjectAllocator) not found");

            // Discover ObjectAllocator from render's 2nd param
            oaCls = renderMethod.getParameterTypes()[1];

            // Find TRIVIAL: static field whose type == ObjectAllocator
            for (Field f : oaCls.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == oaCls) {
                    objectAllocatorTrivial = f.get(null);
                    break;
                }
            }
            if (objectAllocatorTrivial == null)
                throw new UnsupportedOperationException("ObjectAllocator.TRIVIAL not found");
        }

        reflectionInitialized = true;
    }

    private static void createProcessor(MinecraftClient client, float strength) throws Exception {
        initReflection();

        if (era == 1) {
            createProcessorEra1(client, strength);
        } else if (era == 4) {
            createProcessorEra4(client, strength);
        }
    }

    // ─── Era 1: JSON-based (1.21-1.21.1) ───────────────────────────────────────

    private static void createProcessorEra1(MinecraftClient client, float strength) throws Exception {
        // Direct class literals — Loom remaps these. No Class.forName strings.
        Identifier postEffectId = Identifier.of("holdf5", "post_effects/motion_blur.json");

        Constructor<?> ctor = PostEffectProcessor.class.getConstructor(
                TextureManager.class, ResourceFactory.class, Framebuffer.class, Identifier.class);
        processor = (AutoCloseable) ctor.newInstance(
                client.getTextureManager(),
                client.getResourceManager(),
                client.getFramebuffer(),
                postEffectId
        );

        setupDimensionsMethod.invoke(processor,
                client.getWindow().getFramebufferWidth(),
                client.getWindow().getFramebufferHeight());
        setUniformsMethod.invoke(processor, "Strength", strength);
    }

    // ─── Era 4: Pipeline + ProjectionMatrix2 (1.21.6-1.21.11) ──────────────────

    private static void createProcessorEra4(MinecraftClient client, float strength) throws Exception {
        // Build PostEffectPipeline from JSON via CODEC (pipelineCls discovered in initReflection)
        Object pipeline = buildPipelineFromCodec(pipelineCls, strength);

        // Create ProjectionMatrix2 — find its (String, float, float, boolean) constructor
        Constructor<?> pm2Ctor = null;
        for (Constructor<?> c : pm2Cls.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 4 && p[0] == String.class
                    && p[1] == float.class && p[2] == float.class && p[3] == boolean.class) {
                pm2Ctor = c;
                break;
            }
        }
        if (pm2Ctor == null) throw new UnsupportedOperationException("ProjectionMatrix2 constructor not found");
        projectionMatrix = (AutoCloseable) pm2Ctor.newInstance("holdf5_motion_blur", 0.1f, 1000.0f, false);

        // Build external targets set — Identifier.of is a direct call, Loom remaps it.
        Identifier mainId = Identifier.of("minecraft", "main");
        @SuppressWarnings("unchecked")
        Set<Object> externalTargets = Set.of((Object) mainId);

        // Create our blur Identifier
        Identifier blurId = Identifier.of("holdf5", "motion_blur");

        // parseEffect(pipeline, textureManager, externalTargets, id, projectionMatrix)
        processor = (AutoCloseable) parseEffectMethod.invoke(null,
                pipeline, client.getTextureManager(), externalTargets, blurId, projectionMatrix);
    }

    /**
     * Builds a PostEffectPipeline by constructing JSON and parsing through PostEffectPipeline.CODEC.
     * This avoids needing to construct version-specific record types via reflection.
     */
    private static Object buildPipelineFromCodec(Class<?> pipelineClass, float strength) throws Exception {
        // Build the pipeline JSON for era 4 format
        JsonObject json = new JsonObject();

        // Targets: prev buffer with persistence
        JsonObject targets = new JsonObject();
        JsonObject prevTarget = new JsonObject();
        prevTarget.addProperty("persistent", true);
        targets.add("holdf5:prev", prevTarget);
        json.add("targets", targets);

        // Passes
        JsonArray passes = new JsonArray();

        // Pass 1: motion blur blend (current + prev → main)
        JsonObject blurPass = new JsonObject();
        blurPass.addProperty("vertex_shader", "holdf5:post_effect/motion_blur");
        blurPass.addProperty("fragment_shader", "holdf5:post_effect/motion_blur");

        JsonArray blurInputs = new JsonArray();
        JsonObject inSampler = new JsonObject();
        inSampler.addProperty("sampler_name", "In");
        inSampler.addProperty("target", "minecraft:main");
        inSampler.addProperty("bilinear", true);
        blurInputs.add(inSampler);
        JsonObject prevSampler = new JsonObject();
        prevSampler.addProperty("sampler_name", "Prev");
        prevSampler.addProperty("target", "holdf5:prev");
        prevSampler.addProperty("bilinear", true);
        blurInputs.add(prevSampler);
        blurPass.add("inputs", blurInputs);

        blurPass.addProperty("output", "minecraft:main");

        JsonObject uniforms = new JsonObject();
        JsonObject strengthUniform = new JsonObject();
        strengthUniform.addProperty("type", "float");
        strengthUniform.addProperty("value", strength);
        JsonArray strengthUniforms = new JsonArray();
        strengthUniforms.add(strengthUniform);
        uniforms.add("Strength", strengthUniforms);
        blurPass.add("uniforms", uniforms);

        passes.add(blurPass);

        // Pass 2: blit (main → prev, saves frame for next blend)
        JsonObject blitPass = new JsonObject();
        blitPass.addProperty("vertex_shader", "holdf5:post_effect/blit");
        blitPass.addProperty("fragment_shader", "holdf5:post_effect/blit");

        JsonArray blitInputs = new JsonArray();
        JsonObject blitIn = new JsonObject();
        blitIn.addProperty("sampler_name", "In");
        blitIn.addProperty("target", "minecraft:main");
        blitInputs.add(blitIn);
        blitPass.add("inputs", blitInputs);

        blitPass.addProperty("output", "holdf5:prev");

        passes.add(blitPass);

        json.add("passes", passes);

        // Parse through PostEffectPipeline.CODEC
        return parseJsonWithCodec(pipelineClass, json);
    }

    /**
     * Parses a JsonObject through a class's static CODEC field using reflection.
     * Works with any Mojang Codec/DataResult/JsonOps chain.
     *
     * DFU (com.mojang.serialization) classes are NOT obfuscated — their names
     * are stable across all versions, so Class.forName and getField/getMethod
     * with literal names work fine for DFU types.
     */
    private static Object parseJsonWithCodec(Class<?> targetClass, JsonObject json) throws Exception {
        // Find CODEC field by type — can't use getField("CODEC") because the yarn
        // field name won't remap for classes absent from build-time mappings.
        // Look for a static field assignable to com.mojang.serialization.Codec (DFU, stable name).
        Class<?> codecBaseClass = Class.forName("com.mojang.serialization.Codec");
        Object codec = null;
        for (Field f : targetClass.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && codecBaseClass.isAssignableFrom(f.getType())) {
                codec = f.get(null);
                break;
            }
        }
        if (codec == null) {
            // Try MapCodec as fallback
            Class<?> mapCodecClass = Class.forName("com.mojang.serialization.MapCodec");
            for (Field f : targetClass.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && mapCodecClass.isAssignableFrom(f.getType())) {
                    codec = f.get(null);
                    break;
                }
            }
        }
        if (codec == null) throw new NoSuchFieldException("No Codec field found on " + targetClass.getName());

        // Get JsonOps.INSTANCE (DFU — not obfuscated, stable names)
        Class<?> jsonOpsClass = Class.forName("com.mojang.serialization.JsonOps");
        Field instanceField = jsonOpsClass.getField("INSTANCE");
        Object jsonOps = instanceField.get(null);

        // Find codec.parse(DynamicOps, input) method (DFU — not obfuscated)
        Method parseMethod = null;
        for (Method m : codec.getClass().getMethods()) {
            if (m.getName().equals("parse") && m.getParameterCount() == 2) {
                parseMethod = m;
                break;
            }
        }
        if (parseMethod == null) {
            throw new NoSuchMethodException("Could not find parse method on Codec");
        }

        // Call parse — returns DataResult<T>
        Object dataResult = parseMethod.invoke(codec, jsonOps, json);

        // Extract result from DataResult via result() → Optional<T> (DFU — stable names)
        Method resultMethod = dataResult.getClass().getMethod("result");
        @SuppressWarnings("unchecked")
        Optional<Object> optional = (Optional<Object>) resultMethod.invoke(dataResult);

        if (optional.isPresent()) {
            return optional.get();
        }

        // If parsing failed, get error message
        Method errorMethod = dataResult.getClass().getMethod("error");
        Optional<?> error = (Optional<?>) errorMethod.invoke(dataResult);
        String errorMsg = error.map(Object::toString).orElse("unknown codec error");
        throw new RuntimeException("Failed to parse PostEffectPipeline: " + errorMsg);
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    public static void cleanup() {
        if (processor != null) {
            try { processor.close(); } catch (Exception ignored) {}
            processor = null;
        }
        if (projectionMatrix != null) {
            try { projectionMatrix.close(); } catch (Exception ignored) {}
            projectionMatrix = null;
        }
        lastStrength = -1f;
        warmedUp = false;
    }

    public static boolean isUnavailable() {
        return unavailable;
    }

    public static void resetAvailability() {
        unavailable = false;
        reflectionInitialized = false;
        era = -1;
        parseEffectMethod = null;
        pipelineCls = null;
        pm2Cls = null;
        oaCls = null;
    }
}
