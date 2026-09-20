package dev.fobium.holdf5.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.Perspective;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Holdf5Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("holdf5.json");

    public String perspective = "THIRD_PERSON_BACK";
    public float transitionSpeed = 0.15f;
    public boolean separateTransitionSpeeds = false;
    public float releaseTransitionSpeed = 0.15f;
    public boolean motionBlur = false;
    public float motionBlurStrength = 0.5f;
    public String motionBlurMode = "TRANSITION";
    public boolean firstUseShown = false;

    public Perspective getPerspective() {
        return switch (perspective.toUpperCase()) {
            case "THIRD_PERSON_FRONT" -> Perspective.THIRD_PERSON_FRONT;
            default -> Perspective.THIRD_PERSON_BACK;
        };
    }

    public boolean isMotionBlurWhileHold() {
        return "HOLD".equalsIgnoreCase(motionBlurMode);
    }

    public static Holdf5Config load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                Holdf5Config config = GSON.fromJson(json, Holdf5Config.class);
                if (config != null) {
                    config.normalize();
                    return config;
                }
            } catch (IOException | JsonSyntaxException e) {
                e.printStackTrace();
            }
        }
        Holdf5Config config = new Holdf5Config();
        config.normalize();
        config.save();
        return config;
    }

    public void save() {
        normalize();
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void normalize() {
        if (!"THIRD_PERSON_FRONT".equalsIgnoreCase(perspective)) {
            perspective = "THIRD_PERSON_BACK";
        }
        transitionSpeed = clampSpeed(transitionSpeed);
        releaseTransitionSpeed = clampSpeed(releaseTransitionSpeed);
        motionBlurStrength = Math.clamp(motionBlurStrength, 0.1f, 0.95f);
        if (!"HOLD".equalsIgnoreCase(motionBlurMode)) {
            motionBlurMode = "TRANSITION";
        }
    }

    private static float clampSpeed(float speed) {
        return Math.clamp(speed, 0.01f, 1.0f);
    }
}
