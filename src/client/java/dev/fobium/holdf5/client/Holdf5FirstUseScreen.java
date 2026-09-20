package dev.fobium.holdf5.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.MultilineTextWidget;
import net.minecraft.text.Text;

public class Holdf5FirstUseScreen extends Screen {

    public Holdf5FirstUseScreen() {
        super(Text.translatable("holdf5.firstuse.title"));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int y = height / 2 - 50;

        MultilineTextWidget text = new MultilineTextWidget(
                Text.translatable("holdf5.firstuse.body"),
                textRenderer
        );
        text.setMaxWidth(260);
        text.setPosition(centerX - text.getWidth() / 2, y);
        addDrawableChild(text);

        y = height / 2 + 10;
        addDrawableChild(ButtonWidget.builder(Text.translatable("holdf5.firstuse.configure"), button -> {
            client.setScreen(Holdf5ConfigScreen.create(null, Holdf5Client.getConfig()));
        }).dimensions(centerX - 100, y, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("holdf5.firstuse.dismiss"), button -> {
            close();
        }).dimensions(centerX - 100, y + 24, 200, 20).build());
    }

    @Override
    public void close() {
        client.setScreen(null);
    }
}
