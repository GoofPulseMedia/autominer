# AutoMiner - Minecraft Fabric Mod

An advanced, client-side Minecraft Fabric mod that provides automated mining with intelligent area selection, persistent learning, and safe movement logic.

## Features

* **Smart Area Mining**: Select any 3D area and the miner will clear it using an efficient, layer-by-layer snake pattern. The miner now starts each new layer from the corner closest to your position to minimize travel time and intelligently handles vegetation.
* **Pause & Resume**: Pause the mining operation at any time and resume exactly where you left off.
* **Persistent Learning**: A reward-based system remembers successful and unsuccessful locations and saves this data to `training_data.json`, improving pathfinding over time.
* **Training Mode**: Generate a test area to safely run and train the miner's logic in a controlled environment.
* **Configuration**: Fine-tune settings like the pathfinding search limit, nodes processed per tick, and stuck detection timer via the in-game config menu (requires Mod Menu) or chat commands.
* **Visual Feedback**: See your selected area, the miner's planned path, and the current target with colored overlays. Status messages are also shown on the action bar.
* **Client-Side Commands**: All commands are handled locally and are not sent to the server or public chat.

## How to Use

1.  Press the **Select Area** key (`F1`) to enter selection mode.
2.  Right-click a block to set the start position.
3.  Right-click another block to set the end position.
4.  Press **Enter** to confirm the selection.
5.  Press the **Start Mining** key (`F2`) to begin.
6.  Use the **Pause/Resume** (`F4`/`F5`) or **Stop** (`F3`) keys to control the miner.

## Commands & Keybindings

While the mod is best used with keybindings, every action has a corresponding chat command. Commands are client-side (they start with `+`) and will not be sent to the server chat.

| Action                | Default Key | Chat Command                        |
| :------------------   | :---------- | :---------------------------------- |
| **Select Area**       | `F1`        | `+automine select`                  |
| **Confirm Selection** | `Enter`     | *(none)*                            |
| **Start Mining**      | `F2`        | `+automine start`                   |
| **Stop Mining**       | `F3`        | `+automine stop`                    |
| **Pause Mining**      | `F4`        | `+automine pause`                   |
| **Resume Mining**     | `F5`        | `+automine resume`                  |
| **Cancel Selection**  | `F6`        | `+automine cancel`                  |
| **Start Training**    | `F7`        | `+automine train`                   |

**Configuration Commands:**
* `+automine config show` - Displays the current settings.
* `+automine config set pathfindingLimit <value>` - Changes the pathfinding search limit (value must be between 10 and 10000).
* `+automine config reset` - Resets settings to their defaults.

## Safety Features

* Mines strictly within the selected area.
* Checks for safe footing before moving to a new position.
* Prioritizes breaking falling blocks (e.g., gravel, sand) above the target to prevent collapses.
* Detects fall damage and applies a learning penalty to avoid similar situations in the future.
* **Tool Durability Check**: Automatically monitors tool durability and pauses the operation with a warning if the active tool is about to break, preventing accidental tool loss.
* Stops automatically when the entire area has been mined.

## Requirements

-   Minecraft 1.21.8
-   Fabric Loader 0.16.14+
-   Fabric API 0.129.0+

## Installation

1.  Download and install Fabric Loader for Minecraft 1.21.8.
2.  Download the correct Fabric API version.
3.  Place both this mod and the Fabric API jar in your `mods` folder.
4.  Launch Minecraft with the Fabric profile.

## Building from Source

1.  Clone this repository.
2.  Run `./gradlew build`.
3.  The built mod will be in `build/libs/`.

## License

This project is licensed under the MIT License. See the LICENSE file for details.