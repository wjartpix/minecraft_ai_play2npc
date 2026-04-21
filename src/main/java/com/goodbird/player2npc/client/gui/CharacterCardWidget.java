package com.goodbird.player2npc.client.gui;

import adris.altoclef.player2api.Character;
import com.goodbird.player2npc.client.util.SkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

public class CharacterCardWidget extends AbstractWidget {

    private final Character character;
    private final Consumer<Character> onClick;
    private final int BACKGROUND_COLOR = 0xFF181825;

    public CharacterCardWidget(int x, int y, int width, int height, Character character, Consumer<Character> onClick) {
        super(x, y, width, height, Component.literal(character.name()));
        this.character = character;
        this.onClick = onClick;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, 0xff1b1f4c);
        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 30, 0x20FFFFFF);

        int headSize = this.width - 24;
        int headX = this.getX() + 12;
        int headY = this.getY() + 42;
        ResourceLocation skinId = SkinManager.getSkinIdentifier(character.skinURL());
        SkinManager.renderSkinHead(graphics, headX, headY, headSize, skinId);

        Component nameText = Component.literal(character.shortName());
        int textY = this.getY() + 12;
        graphics.drawCenteredString(Minecraft.getInstance().font, nameText, this.getX() + this.width / 2, textY, 0xFFFFFF);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active && this.visible) {
            this.onClick.accept(this.character);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {

    }
}