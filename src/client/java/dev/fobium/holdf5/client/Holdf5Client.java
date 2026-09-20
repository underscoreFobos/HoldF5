package dev.fobium.holdf5.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class Holdf5Client implements ClientModInitializer {
    private static KeyBinding holdPerspectiveKey;
    private static Holdf5Config config;

    private static boolean keyHeld = false;
    private static boolean active = false;
    private static float transitionProgress = 0f;

    @Override
    public void onInitializeClient() {
        config = Holdf5Config.load();

        holdPerspectiveKey = KeyBindingHelper.registerKeyBinding(createKeyBinding(
                "key.holdf5.hold_perspective",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                "holdf5"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            boolean isHolding = holdPerspectiveKey.isPressed();

            if (isHolding && !keyHeld) {
                if (!config.firstUseShown) {
                    keyHeld = true;
                    config.firstUseShown = true;
                    config.save();
                    client.setScreen(new Holdf5FirstUseScreen());
                    return;
                }
                active = true;
                client.options.setPerspective(config.getPerspective());
            }

            keyHeld = isHolding;
        });
    }

    /**
     * Creates a KeyBinding compatible with both 1.21-1.21.8 (String category)
     * and 1.21.9+ (KeyBinding.Category record).
     */
    private static KeyBinding createKeyBinding(String id, InputUtil.Type type, int code, String categoryName) {
        // Try 1.21.9+ Category-based constructor first.
        // Category doesn't exist in 1.21 build-time mappings, so discover structurally:
        // scan KeyBinding's constructors for one whose 4th param isn't String.
        //
        // CRITICAL: Use Identifier.class (direct class literal — Loom remaps it in bytecode)
        // instead of Class.forName("net.minecraft.util.Identifier") (string literal — Loom
        // does NOT remap strings, so it fails at runtime on intermediary-mapped game).
        try {
            for (Constructor<?> ctor : KeyBinding.class.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 4 && params[3] != String.class) {
                    Class<?> categoryClass = params[3];
                    Identifier identifier = Identifier.of("holdf5", categoryName);

                    // Find static factory: Category.create(Identifier) → Category
                    // Method name won't remap, so discover by signature shape.
                    Method createMethod = null;
                    for (Method m : categoryClass.getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == Identifier.class
                                && categoryClass.isAssignableFrom(m.getReturnType())) {
                            createMethod = m;
                            break;
                        }
                    }
                    if (createMethod == null) continue;

                    Object category = createMethod.invoke(null, identifier);
                    return (KeyBinding) ctor.newInstance(id, type, code, category);
                }
            }
        } catch (Throwable ignored) {
        }

        // Fall back to 1.21-1.21.8 String-based constructor
        try {
            Constructor<KeyBinding> ctor = KeyBinding.class.getConstructor(
                    String.class, InputUtil.Type.class, int.class, String.class);
            return ctor.newInstance(id, type, code, "key.categories." + categoryName);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create KeyBinding — unsupported MC version", e);
        }
    }

    public static Holdf5Config getConfig() {
        return config;
    }

    public static boolean isKeyHeld() {
        return keyHeld;
    }

    public static boolean isActive() {
        return active;
    }

    public static float getTransitionProgress() {
        return transitionProgress;
    }

    public static boolean isMotionBlurActive() {
        if (!config.motionBlur || !active) return false;
        if (config.isMotionBlurWhileHold()) {
            return keyHeld;
        }
        return transitionProgress > 0.01f && transitionProgress < 0.99f;
    }

    public static float updateTransitionProgress() {
        float target = keyHeld ? 1f : 0f;
        float speed = keyHeld || !config.separateTransitionSpeeds
                ? config.transitionSpeed
                : config.releaseTransitionSpeed;
        transitionProgress += (target - transitionProgress) * speed;

        if (!keyHeld && transitionProgress < 0.15f) {
            transitionProgress = 0f;
            active = false;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options.getPerspective() != Perspective.FIRST_PERSON) {
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
        }

        return transitionProgress;
    }
}
