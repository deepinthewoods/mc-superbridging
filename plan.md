# Bridging Mode Implementation Plan

## Goals
- Enter a client-side "bridging mode" whenever the player holds the use key (right mouse) while aiming at air at the player's Y-level, provided at least one horizontal neighbor block could serve as support.
- While bridging mode is active, automatically place blocks under the player and up to two blocks ahead in the direction the player is currently facing, updating smoothly as they turn.
- Keep bridging active until the use key is released, even if the player swaps hotbar slots to restock blocks.

## Implementation Steps
1. **Input + State Tracking**
   - Create a `ninja.trek.superbridging.client.BridgingController` singleton that listens to `ClientTickEvents.END_CLIENT_TICK` and watches `MinecraftClient.options.useKey`.
   - Track state transitions (`IDLE` -> `ARMED` -> `BRIDGING`) so heavy placement logic only runs after the first frame the key is held.
   - Gate optional debug logging behind `FabricLoader.getInstance().isDevelopmentEnvironment()` for manual verification.

2. **Start Condition Evaluation**
   - On key press, snapshot the held stack for validation and clear any cached facing data.
   - Perform a raycast from the player's eyes (range ~5 blocks) to confirm the crosshair is targeting air at the player's Y-level.
   - Check the four horizontal neighbors around that air block; if any exposes a solid top face, mark the controller `BRIDGING` and remember that origin position for future support checks.

3. **Block Placement Logic**
   - Each tick while `BRIDGING`, compute the block directly beneath the player (`floor(x)`, `floor(y) - 1`, `floor(z)`).
   - Derive a normalized horizontal forward vector from the player's current yaw; use it to project one- and two-block offsets in front of the player, rounding to world positions so the bridge keeps up with turns.
   - Build the final placement list as `[under-foot, forward-1, forward-2]`, filtering any duplicates produced by rounding.
   - For each candidate position, ensure the space is air or replaceable before issuing placements.
   - Determine the supporting face dynamically (typically the block behind the target along the negative forward vector) and construct a `BlockHitResult` pointing upward so `ClientPlayerInteractionManager.interactBlock` behaves like a legitimate click.

4. **Inventory Handling**
   - Before each placement, ensure the main-hand item is a placeable `BlockItem` with remaining count.
   - When the slot runs dry, scan the hotbar for the next available stack; update `player.getInventory().selectedSlot` and continue without exiting `BRIDGING`.

5. **Exit Conditions**
   - Immediately stop and reset when the use key is released.
   - Also exit if the player loses ground contact, no solid neighbor exists to support the next placement, or repeated placement failures occur in quick succession; log these cases for debugging.

6. **Mixins & Wiring**
   - Register the controller during client initialization in `SuperBridgingClient` (e.g., `ClientTickEvents.END_CLIENT_TICK.register(BridgingController::tick)`).
   - Introduce additional client mixins only if access to non-exposed internals (e.g., placement cooldowns) becomes necessary.
   - Update `fabric.mod.json` and `super-bridging.client.mixins.json` with any new entry points or mixin classes.

7. **Testing Checklist**
   - Run `./gradlew runClient --args="--username DevPlayer"` and verify:
     - Bridging starts only when aiming at valid edge cases.
     - The bridge follows the player's turn smoothly, filling tiles underfoot and two blocks ahead without gaps.
     - Switching hotbar slots continues placement without mode interruptions.
     - Releasing the use key halts placement instantly.
   - Capture logs or short clips for interesting edge cases (stairs, slabs, liquids) for future tuning.

