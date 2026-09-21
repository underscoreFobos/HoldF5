package dev.fobium.holdf5.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class Holdf5ConfigScreen {
    private Holdf5ConfigScreen() {
    }

    public static Screen create(Screen parent, Holdf5Config config) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("holdf5.config.title"))
                .setSavingRunnable(config::save);
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory camera = builder.getOrCreateCategory(Text.translatable("holdf5.config.category.camera"));

        camera.addEntry(entries.startSelector(
                        Text.translatable("holdf5.config.perspective.label"),
                        PerspectiveOption.values(),
                        PerspectiveOption.from(config.perspective))
                .setDefaultValue(PerspectiveOption.BACK)
                .setTooltip(Text.translatable("holdf5.config.perspective.desc"))
                .setSaveConsumer(option -> config.perspective = option.value)
                .build());
        camera.addEntry(entries.startIntSlider(
                        Text.translatable("holdf5.config.transition_speed.label"),
                        speedToPercent(config.transitionSpeed), 1, 100)
                .setDefaultValue(15)
                .setTooltip(Text.translatable("holdf5.config.transition_speed.desc"))
                .setSaveConsumer(value -> config.transitionSpeed = percentToSpeed(value))
                .build());
        BooleanListEntry separateSpeeds = entries.startBooleanToggle(
                        Text.translatable("holdf5.config.separate_transition_speeds.label"),
                        config.separateTransitionSpeeds)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("holdf5.config.separate_transition_speeds.desc"))
                .setSaveConsumer(value -> config.separateTransitionSpeeds = value)
                .build();
        camera.addEntry(separateSpeeds);
        camera.addEntry(entries.startIntSlider(
                        Text.translatable("holdf5.config.release_transition_speed.label"),
                        speedToPercent(config.releaseTransitionSpeed), 1, 100)
                .setDefaultValue(15)
                .setTooltip(Text.translatable("holdf5.config.release_transition_speed.desc"))
                .setRequirement(Requirement.isTrue(separateSpeeds))
                .setSaveConsumer(value -> config.releaseTransitionSpeed = percentToSpeed(value))
                .build());

        if (supportsVisualEffects()) {
            ConfigCategory effects = builder.getOrCreateCategory(Text.translatable("holdf5.config.category.effects"));
            BooleanListEntry motionBlur = entries.startBooleanToggle(
                            Text.translatable("holdf5.config.motion_blur.label"),
                            config.motionBlur)
                    .setDefaultValue(false)
                    .setTooltip(Text.translatable("holdf5.config.motion_blur.desc"))
                    .setSaveConsumer(value -> {
                        config.motionBlur = value;
                        if (value) {
                            Holdf5MotionBlur.resetAvailability();
                        } else {
                            Holdf5MotionBlur.cleanup();
                        }
                    })
                    .build();
            effects.addEntry(motionBlur);
            effects.addEntry(entries.startSelector(
                            Text.translatable("holdf5.config.motion_blur_mode.label"),
                            MotionBlurMode.values(),
                            MotionBlurMode.from(config.motionBlurMode))
                    .setDefaultValue(MotionBlurMode.TRANSITION)
                    .setTooltip(Text.translatable("holdf5.config.motion_blur_mode.desc"))
                    .setRequirement(Requirement.isTrue(motionBlur))
                    .setSaveConsumer(mode -> config.motionBlurMode = mode.value)
                    .build());
            effects.addEntry(entries.startIntSlider(
                            Text.translatable("holdf5.config.motion_blur_strength.label"),
                            strengthToPercent(config.motionBlurStrength), 10, 95)
                    .setDefaultValue(50)
                    .setTooltip(Text.translatable("holdf5.config.motion_blur_strength.desc"))
                    .setRequirement(Requirement.isTrue(motionBlur))
                    .setSaveConsumer(value -> {
                        config.motionBlurStrength = percentToStrength(value);
                        Holdf5MotionBlur.cleanup();
                    })
                    .build());
        }

        return builder.build();
    }

    private static int speedToPercent(float speed) {
        return Math.round(speed * 100.0f);
    }

    private static float percentToSpeed(int percent) {
        return percent / 100.0f;
    }

    private static int strengthToPercent(float strength) {
        return Math.round(strength * 100.0f);
    }

    private static float percentToStrength(int percent) {
        return percent / 100.0f;
    }

    private static boolean supportsVisualEffects() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.21");
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major > 1 || major == 1 && (minor > 21 || minor == 21 && patch >= 6);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private enum PerspectiveOption {
        BACK("THIRD_PERSON_BACK", "holdf5.config.perspective.third_person_back"),
        FRONT("THIRD_PERSON_FRONT", "holdf5.config.perspective.third_person_front");

        private final String value;
        private final String translationKey;

        PerspectiveOption(String value, String translationKey) {
            this.value = value;
            this.translationKey = translationKey;
        }

        private static PerspectiveOption from(String value) {
            return "THIRD_PERSON_FRONT".equalsIgnoreCase(value) ? FRONT : BACK;
        }

        @Override
        public String toString() {
            return Text.translatable(translationKey).getString();
        }
    }

    private enum MotionBlurMode {
        TRANSITION("TRANSITION", "holdf5.config.motion_blur_mode.transition"),
        HOLD("HOLD", "holdf5.config.motion_blur_mode.hold");

        private final String value;
        private final String translationKey;

        MotionBlurMode(String value, String translationKey) {
            this.value = value;
            this.translationKey = translationKey;
        }

        private static MotionBlurMode from(String value) {
            return "HOLD".equalsIgnoreCase(value) ? HOLD : TRANSITION;
        }

        @Override
        public String toString() {
            return Text.translatable(translationKey).getString();
        }
    }
}
