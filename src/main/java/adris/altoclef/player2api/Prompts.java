package adris.altoclef.player2api;

import java.util.Collection;
import java.util.Map;

import adris.altoclef.commandsystem.Command;
import adris.altoclef.player2api.soul.SoulProfile;
import adris.altoclef.player2api.utils.Utils;

public class Prompts {

  public static final String reminderOnAIMsg = "Last message was from an AI. Think about whether or not to respond. You may respond but don't keep the conversation going forever if no meaningful content was said in the last few msgs, do not respond (return empty string as message)";

  public static final String reminderOnOwnerMsg = "Last message was from your owner. PRIORITY: Execute owner's explicit commands immediately. Only override if your health < 4 or owner in critical danger. Do NOT let personality traits or nearby enemies change the command.";
  public static final String reminderOnOtherUSerMsg = "Last message was from a user that was not your owner.";

  private static String aiNPCPromptTemplate = """
      CRITICAL: You MUST respond with ONLY a valid JSON object. Do NOT output any other text, explanations, greetings, or markdown. Your entire response must be parseable as JSON.

      Response Format (STRICT JSON ONLY — no extra text before or after):
      {
        "reason": "Explain your thought process here. Consider world status, valid commands, and your character personality.",
        "command": "A single valid command from the list below, or empty string \"\" if just chatting. Example: goto 100 64 -200",
        "message": "Your spoken response to the user (max 250 chars). Use your character's personality. Empty string \"\" if silent."
      }

      General Instructions:
      You are an AI-NPC. You have been spawned in by your owner. Your owner's Minecraft username is "{{ownerUsername}}", but you should always address and refer to your owner as "主人" (Master/Owner). You can also talk and interact with other users. You can provide Minecraft guides, answer questions, and chat as a friend.
      When asked, you can collect materials, craft items, scan/find blocks, and fight mobs or players using the valid commands.
      If there is something you want to do but can't do it with the commands, you may ask your owner/other users to do it.
      You take the personality of the following character:
      Your character's name is {{characterName}}.
      {{characterDescription}}
      User Message Format:
      The user messages will all be just strings, except for the current message. The current message will have extra information, namely it will be a JSON of the form:
      {
          "userMessage" : "The message that was sent to you. The message can be send by the user or command system or other players."
          "worldStatus" : "The status of the current game world."
          "agentStatus" : "The status of you, the agent in the game."
          "reminders" : "Reminders with additional instructions."
          "gameDebugMessages" : "The most recent debug messages that the game has printed out. The user cannot see these."
      }
      Additional Guidelines:
      - IMPORTANT: If you are chatting with user, use the bodylang command if you are not performing a task for user. For instance:
          -- Use `bodylang greeting` when greeting/saying hi.
          -- Use `bodylang victory` when celebrating.
          -- Use `bodylang shake_head` when saying no or disagree, and `bodylang nod_head` when saying yes or agree.
          -- Use `stop` to immediately cancel the current automation task and halt all movement/actions. IMPORTANT: `stop` does NOT make you move toward the owner or any target. It only halts. If the owner asks you to come/follow, use `follow_owner`. If the owner asks for rescue/protection, use `attack nearest_hostile <count>`.
          -- Use `follow_owner` when the owner asks you to come to them or follow them (NOT for rescue — use attack for rescue).
      - When the user asks you to craft or obtain an item (like a boat, sword, pickaxe, etc.), ALWAYS use the `get` command to craft/obtain it. Do NOT assume the item already exists or that the user already has it. For example, if the user says "make a boat", you should run `get oak_boat 1` (or `get boat 1`).
      - When the user asks you to build a structure (like a house, bridge, tower, etc.), ALWAYS use the `build_structure` command with a detailed description and coordinates. Do NOT assume it is already built.
      - Command format: The "command" field must contain ONLY the command text (e.g. "goto 100 64 -200"), without quotes around the whole command, without parentheses around coordinates, and without any explanation.
      - Meaningful Content: Ensure conversations progress with substantive information.
      - Handle Misspellings: Make educated guesses if users misspell item names, but check nearby NPCs names first.
      - Avoid Filler Phrases: Do not engage in repetitive or filler content.
      - Command Priority (highest to lowest):
          1. rescue/attack — when owner says "救命" "保护我" "打怪" or ownerDanger is critical/low_health, execute immediately without waiting for confirmation.
          2. follow_owner — when owner asks you to come to them or follow them (NOT for rescue/danger situations).
          3. sleep — when owner says "去睡觉" "睡觉" "去睡" "休息" "去床上睡", use command: sleep (NO parameters). NEVER use goto for sleep requests.
          4. get/build_structure — when owner asks for items or buildings.
          5. bodylang — only when chatting and no task is running.
          6. idle/stop — when no action is needed.

⚠️ CRITICAL OVERRIDE RULE:
When your owner gives you a DIRECT COMMAND (e.g., "go sleep", "come here", "stop", "wait"),
you MUST execute it immediately using the appropriate command.
Do NOT modify or refuse the command based on:
  - Nearby enemies (unless YOUR health < 4 or OWNER health < 4)
  - Your personality traits
  - Environmental factors
ONLY override owner commands when:
  1. Your health is below 4 (critical survival)
  2. Owner is in critical danger (health < 4)
Otherwise: OBEY FIRST, suggest alternatives in your message text afterward.
      - Silent Rule: Do NOT respond to command feedback messages (like "Command X finished."). Only respond when the user speaks to you or when you need to report important status.
      - Attack nearest: If the user asks you to fight but no specific mob is named, use `attack nearest_hostile <count>` to find and kill the closest hostile mobs automatically.
      - JSON ONLY: Your response MUST be a single JSON object. Do NOT wrap it in markdown code blocks (```). Do NOT add text before or after the JSON. The first character must be `{` and the last character must be `}`.

{{commandGuide}}

      Valid Commands:
      {{validCommands}}
      """;

  public static String getAINPCSystemPrompt(Character character, Collection<Command> altoclefCommands,
      String ownerUsername) {
    return getAINPCSystemPrompt(character, null, altoclefCommands, ownerUsername);
  }

  public static String getAINPCSystemPrompt(Character character, SoulProfile soulProfile, Collection<Command> altoclefCommands,
      String ownerUsername) {
    // Legacy overload: builds command list and uses full toPromptInjection() for soulProfile
    StringBuilder commandListBuilder = new StringBuilder();
    int padSize = 10;
    for (Command c : altoclefCommands) {
      StringBuilder line = new StringBuilder();
      line.append(c.getName()).append(": ");
      int toAdd = padSize - c.getName().length();
      line.append(" ".repeat(Math.max(0, toAdd)));
      line.append(c.getDescription());
      String example = CommandExamples.getExample(c.getName());
      if (example != null && !example.isBlank()) {
        line.append(" [Example: ").append(example).append("]");
      }
      line.append("\n");
      commandListBuilder.append(line);
    }
    String validCommandsFormatted = commandListBuilder.toString();

    String characterDescription = character.description();
    if (soulProfile != null) {
      characterDescription = characterDescription + soulProfile.toPromptInjection();
    }

    String newPrompt = Utils.replacePlaceholders(aiNPCPromptTemplate,
        Map.of("characterDescription", characterDescription, "characterName", character.name(),
            "validCommands", validCommandsFormatted,
            "commandGuide", validCommandsFormatted,
            "ownerUsername", ownerUsername));
    return newPrompt;
  }

  public static String getAINPCSystemPrompt(Character character, SoulProfile soulProfile, Collection<Command> altoclefCommands,
      String ownerUsername, String commandGuide) {
    // Build validCommands list from altoclefCommands (if available)
    StringBuilder commandListBuilder = new StringBuilder();
    if (altoclefCommands != null) {
      int padSize = 10;
      for (Command c : altoclefCommands) {
        StringBuilder line = new StringBuilder();
        line.append(c.getName()).append(": ");
        int toAdd = padSize - c.getName().length();
        line.append(" ".repeat(Math.max(0, toAdd)));
        line.append(c.getDescription());
        String example = CommandExamples.getExample(c.getName());
        if (example != null && !example.isBlank()) {
          line.append(" [Example: ").append(example).append("]");
        }
        line.append("\n");
        commandListBuilder.append(line);
      }
    }
    String validCommandsFormatted = commandListBuilder.toString();

    String characterDescription = character.description();
    if (soulProfile != null) {
      characterDescription = characterDescription + soulProfile.toCompactPromptInjection();
    }

    // Resolve commandGuide: use provided value, or fall back to building from altoclefCommands
    String resolvedCommandGuide;
    if (commandGuide != null) {
      resolvedCommandGuide = commandGuide;
    } else if (altoclefCommands != null) {
      resolvedCommandGuide = validCommandsFormatted;
    } else {
      resolvedCommandGuide = "";
    }

    String newPrompt = Utils.replacePlaceholders(aiNPCPromptTemplate,
        Map.of("characterDescription", characterDescription, "characterName", character.name(),
            "validCommands", validCommandsFormatted,
            "commandGuide", resolvedCommandGuide,
            "ownerUsername", ownerUsername));
    return newPrompt;
  }

  private final static String buildStructurePrompt = """
                              You are a code generator for a tiny construction DSL used by a Minecraft bot.
                              ## Objective:
                              Given a natural-language description of a structure, return only the DSL program as a single plain-text string (possibly multi-line). No explanations, no markdown, no code fences, no JSON, no Java wrappers.
                              ### DSL Summary (what you can output)
                              Declarations: let name = <int|string|boolean>;
                              Strings use double quotes; integers only; booleans true|false.
                              Arithmetic: + - * / % (integer math only).
                              Comparisons/logic: == != < <= > >= && || !
                              Control flow:
                              For loops: for (let i = 0; i < N; i = i + 1) { ... }
                              Conditionals: if (cond) { ... } else { ... }
                              Side effects:
                              setBlock(x, y, z, blockName); — place a single block.
                              Comments: // comment
                              Forbidden: user-defined functions, imports, while/foreach, floats, external calls (THIS INCLUDES Math.sin, etc. DO NOT USE Math.sin, or any other external inputs).
                              Place blocks via setBlock(baseX + dx, baseY + dy, baseZ + dz, <material>);
                              If materials are named in the description, use them (e.g., "oak_planks", "stone_bricks", "glass", "cobblestone", "spruce_log", "lantern", "torch", "water", "lava"). If unknown, fall back to "stone".
                              ## Structure Guidelines
                              - Make sure blocknames are correct minecraft blocknames.
                              - Make sure to comment your thoughts, and really think about this, this is very important that the design is not to simple.
                              - Translate the description into concrete geometry with loops/conditionals (floors, walls, roofs, pillars, arches, domes by integer radii, etc.).
                              - For buildings where it makes sense, make sure you also add beds, crafting_table, furnace, etc, be creative!! Maybe a building could have paintings in the hallway, maybe a fireplace, etc.
                              - For buildings when it makes sense, add rooms instead of having a big empty space. Make sure the rooms are different too, maybe a kitchen, bedroom, bathroom, etc. Try not to just make a rectangle/cube as well, maybe make the building an L shape, or add multiple sections, or something similar.
                              - Make sure any torches are attached to a block, and not floating in the air.
                              - A player is 2x1, so make sure structures are the appropriate size.
                              ##  Output Rules (critical)
                              Output only the final DSL program as plain text, each statement on its own line.
                              Every statement ends with ; (except }).
                              Do not wrap the program in quotes, Java, JSON, or markdown.
                              No extra commentary before or after. The first character of your output must be part of the DSL, and the last character must be ; or }.
                              Mini Example (illustrative only; do not echo this)
      // L-shaped villa with rooms, furniture, and thoughtful layout
      // Design thoughts: We'll build an L-shaped single-story villa (24x16 main hall + 12x12 wing).
      // Height = 8 (comfortable for 2-block-tall player). Interior walls create rooms: foyer/hall, kitchen, bedroom, study.
      // We'll add beds, crafting_table, furnace, bookshelves, tables, and well-placed torches on top of solid blocks (not floating).
      // Windows are spaced regularly; doors are 2 blocks tall. A stone-brick fireplace with a chimney and a campfire hearth adds flair.
      let baseX = 0;
      let baseY = 64;
      let baseZ = 0;
      let dir = "north";
      let block = "stone_bricks";
      // ====== FOUNDATION ======
      // Main rectangle: 24 x 16
      for (let x = 0; x < 24; x = x + 1) {
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY, baseZ + z, "stone");
        }
      }
      // Wing rectangle: 12 x 12, attached on the east side (from z=4..15)
      for (let x = 24; x < 36; x = x + 1) {
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY, baseZ + z, "stone");
        }
      }
      // ====== FLOORING ======
      // Main hall floor: oak_planks
      for (let x = 0; x < 24; x = x + 1) {
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "oak_planks");
        }
      }
      // Wing floor: spruce_planks for contrast
      for (let x = 24; x < 36; x = x + 1) {
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "spruce_planks");
        }
      }
      // ====== OUTER WALLS (HEIGHT 8) ======
      for (let y = 2; y <= 9; y = y + 1) {
        // Main rectangle perimeter
        for (let x = 0; x < 24; x = x + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 0, "stone_bricks");
          setBlock(baseX + x, baseY + y, baseZ + 15, "stone_bricks");
        }
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + 0, baseY + y, baseZ + z, "stone_bricks");
          setBlock(baseX + 23, baseY + y, baseZ + z, "stone_bricks");
        }
        // Wing perimeter
        for (let x = 24; x < 36; x = x + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 4, "stone_bricks");
          setBlock(baseX + x, baseY + y, baseZ + 15, "stone_bricks");
        }
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + 24, baseY + y, baseZ + z, "stone_bricks");
          setBlock(baseX + 35, baseY + y, baseZ + z, "stone_bricks");
        }
      }
      // ====== DOORWAYS ======
      // Main entrance centered on front (z=0) of main hall: width 3, height 3
      for (let dx = 10; dx <= 12; dx = dx + 1) {
        for (let dy = 2; dy <= 4; dy = dy + 1) {
          setBlock(baseX + dx, baseY + dy, baseZ + 0, "air");
        }
      }
      // Door from main hall to wing (opening on shared wall at x=23): 2x3
      for (let dz = 8; dz <= 9; dz = dz + 1) {
        for (let dy = 2; dy <= 4; dy = dy + 1) {
          setBlock(baseX + 23, baseY + dy, baseZ + dz, "air");
        }
      }
      // ====== WINDOWS ======
      // Evenly spaced windows (2x2) around exterior walls, leaving corners
      for (let y = 4; y <= 5; y = y + 1) {
        for (let x = 3; x <= 21; x = x + 6) {
          setBlock(baseX + x, baseY + y, baseZ + 0, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 0, "glass");
          setBlock(baseX + x, baseY + y, baseZ + 15, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 15, "glass");
        }
        for (let z = 3; z <= 13; z = z + 5) {
          setBlock(baseX + 0, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 1, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 23, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 22, baseY + y, baseZ + z, "glass");
        }
        // Wing windows
        for (let x = 26; x <= 34; x = x + 8) {
          setBlock(baseX + x, baseY + y, baseZ + 4, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 4, "glass");
          setBlock(baseX + x, baseY + y, baseZ + 15, "glass");
          setBlock(baseX + x + 1, baseY + y, baseZ + 15, "glass");
        }
        for (let z = 6; z <= 14; z = z + 4) {
          setBlock(baseX + 24, baseY + y, baseZ + z, "glass");
          setBlock(baseX + 35, baseY + y, baseZ + z, "glass");
        }
      }
      // ====== ROOF (FLAT WITH BORDER) ======
      for (let x = 0; x < 24; x = x + 1) {
        for (let z = 0; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 10, baseZ + z, "stone");
        }
      }
      for (let x = 24; x < 36; x = x + 1) {
        for (let z = 4; z < 16; z = z + 1) {
          setBlock(baseX + x, baseY + 10, baseZ + z, "stone");
        }
      }
      // Roof trim
      for (let x = 0; x < 24; x = x + 1) {
        setBlock(baseX + x, baseY + 10, baseZ + 0, "stone_bricks");
        setBlock(baseX + x, baseY + 10, baseZ + 15, "stone_bricks");
      }
      for (let z = 0; z < 16; z = z + 1) {
        setBlock(baseX + 0, baseY + 10, baseZ + z, "stone_bricks");
        setBlock(baseX + 23, baseY + 10, baseZ + z, "stone_bricks");
      }
      for (let x = 24; x < 36; x = x + 1) {
        setBlock(baseX + x, baseY + 10, baseZ + 4, "stone_bricks");
        setBlock(baseX + x, baseY + 10, baseZ + 15, "stone_bricks");
      }
      for (let z = 4; z < 16; z = z + 1) {
        setBlock(baseX + 24, baseY + 10, baseZ + z, "stone_bricks");
        setBlock(baseX + 35, baseY + 10, baseZ + z, "stone_bricks");
      }
      // ====== INTERIOR ROOMS ======
      // Partition main hall into foyer (front), corridor (middle), and living room (rear)
      for (let x = 2; x <= 21; x = x + 1) {
        for (let y = 2; y <= 7; y = y + 1) {
          // Wall between foyer and corridor at z=5
          setBlock(baseX + x, baseY + y, baseZ + 5, "stone_bricks");
          // Wall between corridor and living room at z=10
          setBlock(baseX + x, baseY + y, baseZ + 10, "stone_bricks");
        }
      }
      // Doorways (2x2) in those partitions
      for (let dy = 2; dy <= 3; dy = dy + 1) {
        setBlock(baseX + 12, baseY + dy, baseZ + 5, "air");
        setBlock(baseX + 12, baseY + dy, baseZ + 10, "air");
        setBlock(baseX + 13, baseY + dy, baseZ + 5, "air");
        setBlock(baseX + 13, baseY + dy, baseZ + 10, "air");
      }
      // Wing: split into kitchen (north) and bedroom (south)
      for (let x = 26; x <= 33; x = x + 1) {
        for (let y = 2; y <= 7; y = y + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 10, "stone_bricks");
        }
      }
      // Wing doorways (2x2)
      for (let dy = 2; dy <= 3; dy = dy + 1) {
        setBlock(baseX + 30, baseY + dy, baseZ + 10, "air");
        setBlock(baseX + 31, baseY + dy, baseZ + 10, "air");
      }
      // ====== FIREPLACE & CHIMNEY (living room corner) ======
      // Hearth at (x=3..5, z=12..13)
      for (let x = 3; x <= 5; x = x + 1) {
        for (let z = 12; z <= 13; z = z + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "cobblestone");
        }
      }
      // Campfire for safe flame
      setBlock(baseX + 4, baseY + 2, baseZ + 12, "campfire");
      // Back wall cladding and chimney up
      for (let y = 2; y <= 10; y = y + 1) {
        setBlock(baseX + 4, baseY + y, baseZ + 14, "cobblestone");
        setBlock(baseX + 4, baseY + y, baseZ + 15, "cobblestone");
      }
      for (let y = 11; y <= 13; y = y + 1) {
        setBlock(baseX + 4, baseY + y, baseZ + 15, "cobblestone");
      }
      // ====== FURNITURE & UTILITIES ======
      // Corridor rug (carpet)
      for (let x = 9; x <= 14; x = x + 1) {
        for (let z = 6; z <= 9; z = z + 1) {
          setBlock(baseX + x, baseY + 2, baseZ + z, "red_carpet");
        }
      }
      // Living room: table (logs + slab top), bookshelves, torches on top of shelves
      // Table legs
      setBlock(baseX + 16, baseY + 2, baseZ + 12, "spruce_log");
      setBlock(baseX + 18, baseY + 2, baseZ + 12, "spruce_log");
      setBlock(baseX + 16, baseY + 2, baseZ + 14, "spruce_log");
      setBlock(baseX + 18, baseY + 2, baseZ + 14, "spruce_log");
      // Table top
      for (let x = 16; x <= 18; x = x + 1) {
        for (let z = 12; z <= 14; z = z + 1) {
          setBlock(baseX + x, baseY + 3, baseZ + z, "oak_slab");
        }
      }
      // Bookshelf wall
      for (let x = 19; x <= 21; x = x + 1) {
        for (let y = 2; y <= 4; y = y + 1) {
          setBlock(baseX + x, baseY + y, baseZ + 13, "bookshelf");
        }
      }
      // Torches on top of bookshelf (attached to solid block below)
      for (let x = 19; x <= 21; x = x + 1) {
        setBlock(baseX + x, baseY + 5, baseZ + 13, "torch");
      }
      // Kitchen (wing north): counters (stone), crafting_table, furnace, sink (water)
      for (let x = 26; x <= 33; x = x + 1) {
        setBlock(baseX + x, baseY + 2, baseZ + 6, "stone");
      }
      setBlock(baseX + 27, baseY + 2, baseZ + 7, "crafting_table");
      setBlock(baseX + 28, baseY + 2, baseZ + 7, "furnace");
      // Simple sink basin
      setBlock(baseX + 30, baseY + 2, baseZ + 7, "cauldron");
      setBlock(baseX + 30, baseY + 3, baseZ + 7, "water");
      // Bedroom (wing south): double bed, side tables (barrels), chest
      setBlock(baseX + 29, baseY + 2, baseZ + 12, "bed");
      setBlock(baseX + 30, baseY + 2, baseZ + 12, "bed");
      setBlock(baseX + 28, baseY + 2, baseZ + 12, "barrel");
      setBlock(baseX + 31, baseY + 2, baseZ + 12, "barrel");
      setBlock(baseX + 33, baseY + 2, baseZ + 13, "chest");
      // Study (rear main hall): desk, chair, bookshelves, torches on desk corners
      // Desk
      for (let x = 7; x <= 9; x = x + 1) {
        setBlock(baseX + x, baseY + 2, baseZ + 13, "oak_slab");
      }
      setBlock(baseX + 8, baseY + 2, baseZ + 12, "stair");
      setBlock(baseX + 7, baseY + 3, baseZ + 13, "torch");
      setBlock(baseX + 9, baseY + 3, baseZ + 13, "torch");
      // ====== INTERIOR LIGHTING (TORCHES ON TOP OF FLOOR BLOCKS) ======
      // Main hall grid, placed on floor tops (supported by floor below at y-1)
      for (let x = 3; x <= 21; x = x + 6) {
        for (let z = 3; z <= 13; z = z + 5) {
          setBlock(baseX + x, baseY + 2, baseZ + z, "torch");
        }
      }
      // Wing lighting
      for (let x = 26; x <= 34; x = x + 4) {
        setBlock(baseX + x, baseY + 2, baseZ + 6, "torch");
        setBlock(baseX + x, baseY + 2, baseZ + 13, "torch");
      }
      // ====== FRONT PATH & GARDEN TOUCH ======
      // Small path leading from entrance
      for (let z = -1; z >= -6; z = z - 1) {
        for (let x = 10; x <= 12; x = x + 1) {
          setBlock(baseX + x, baseY + 1, baseZ + z, "cobblestone");
        }
      }
      // Flower beds flanking the path
      for (let z = -1; z >= -6; z = z - 1) {
        setBlock(baseX + 9, baseY + 2, baseZ + z, "rose_bush");
        setBlock(baseX + 13, baseY + 2, baseZ + z, "peony");
      }
                  """;

  public static String getBuildStructurePrompt() {
    return buildStructurePrompt;
  }

}