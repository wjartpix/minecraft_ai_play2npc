package com.goodbird.player2npc.client.gui;

import adris.altoclef.player2api.Character;
import adris.altoclef.player2api.utils.CharacterUtils;
import com.goodbird.player2npc.Player2NPC;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public class CharacterSelectionScreen extends Screen {

    private Character[] characters = null;
    private boolean isLoading = true;
    private Component statusMessage = null;

    public CharacterSelectionScreen() {
        super(Component.literal("Select a Character"));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        isLoading = true;
        statusMessage = null;

        Minecraft minecraftClient = Minecraft.getInstance();

        CompletableFuture.supplyAsync(
                        () -> CharacterUtils.requestCharacters(minecraftClient.player, "player2-ai-npc-minecraft"))
                .whenCompleteAsync((result, throwable) -> {
                    this.isLoading = false;

                    if (throwable != null) {
                        Player2NPC.LOGGER.error("Failed to load Player2 characters", throwable);
                        this.characters = new Character[0];
                        this.statusMessage = Component.literal("Failed to load characters");
                        return;
                    }

                    this.characters = (result != null) ? result : new Character[0];
                    this.statusMessage = (this.characters.length == 0)
                            ? Component.literal("No characters available")
                            : null;

                    this.clearWidgets();
                    this.createCharacterCards();
                }, minecraftClient);
    }

    private void createCharacterCards() {
        if (characters == null || characters.length == 0) return;

        int cardWidth = 100;
        int cardHeight = 130;
        int padding = 30;
        int cardsPerRow = Math.max(1, (this.width - padding) / (cardWidth + padding));

        int totalWidth = cardsPerRow * (cardWidth + padding) - padding;
        int startX = this.width / 2 - totalWidth / 2;
        int startY = 70;

        int currentX = startX;
        int currentY = startY;

        for (Character character : characters) {
            this.addRenderableWidget(new CharacterCardWidget(currentX, currentY, cardWidth, cardHeight, character, this::onCharacterClicked));

            currentX += cardWidth + padding;
            if (currentX + cardWidth > startX + totalWidth) {
                currentX = startX;
                currentY += cardHeight + padding;
            }
        }
    }

    private void onCharacterClicked(Character character) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new CharacterDetailScreen(this, character));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(this.font, "Select a Character", this.width / 2, 20, 0xFFFFFF);

        if (isLoading) {
            graphics.drawCenteredString(this.font, "Loading...", this.width / 2, this.height / 2, 0xAAAAAA);
        } else if (statusMessage != null) {
            graphics.drawCenteredString(this.font, statusMessage.getString(), this.width / 2,
                    this.height / 2, 0xAAAAAA);
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}