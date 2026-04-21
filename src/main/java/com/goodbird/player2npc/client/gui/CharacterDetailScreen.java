package com.goodbird.player2npc.client.gui;

import adris.altoclef.player2api.Character;
import com.goodbird.player2npc.Player2NPC;
import com.goodbird.player2npc.client.util.SkinManager;
import com.goodbird.player2npc.network.AutomatoneDespawnRequestPacket;
import com.goodbird.player2npc.network.AutomatoneSpawnRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public class CharacterDetailScreen extends Screen {

    private final Screen parent;
    private final Character character;

    public CharacterDetailScreen(Screen parent, Character character) {
        super(Component.literal("Character Details"));
        this.parent = parent;
        this.character = character;
    }

    @Override
    protected void init() {
        super.init();

        this.addRenderableWidget(Button.builder(Component.literal("Summon"), button -> {
            if (this.minecraft != null && this.minecraft.getConnection() != null) {
                Player2NPC.LOGGER.info("Summoning companion {}", character.name());
                this.minecraft.getConnection().send(AutomatoneSpawnRequestPacket.create(character));
            } else {
                Player2NPC.LOGGER.warn("Unable to summon companion {} because network handler is unavailable",
                        character.name());
            }
        }).bounds(this.width / 2 - 100, this.height - 100, 98, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Despawn"), button -> {
            if (this.minecraft != null && this.minecraft.getConnection() != null) {
                Player2NPC.LOGGER.info("Despawning companion {}", character.name());
                this.minecraft.getConnection().send(AutomatoneDespawnRequestPacket.create(character));
            } else {
                Player2NPC.LOGGER.warn("Unable to despawn companion {} because network handler is unavailable",
                        character.name());
            }
        }).bounds(this.width / 2 - 100, this.height - 130, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }).bounds(this.width / 2 + 2, this.height - 100, 98, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(this.font, character.name(), this.width / 2, 130, 0xFFFFFF);

        int headSize = 96;
        int headX = this.width / 2 - headSize / 2;
        int headY = 150;
        ResourceLocation skinId = SkinManager.getSkinIdentifier(character.skinURL());
        SkinManager.renderSkinHead(graphics, headX, headY, headSize, skinId);

        int textY = headY + headSize + 15;
        List<FormattedText> lines = this.font.getSplitter().splitLines(character.description(), 200, Style.EMPTY);
        for (FormattedText line : lines) {
            graphics.drawCenteredString(font, line.getString(), this.width / 2, textY, 0xAAAAAA);
            textY += font.lineHeight + 2;
        }

        super.render(graphics, mouseX, mouseY, delta);
    }
}