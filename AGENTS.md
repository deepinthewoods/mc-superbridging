# Repository Guidelines

## Project Structure & Module Organization
The mod follows the standard Loom layout: `src/main/java` holds shared logic and mixin entry points, while `src/client/java` contains client-only hooks. Assets and data resources live under `src/main/resources`, with the namespace `superbridging` for textures, lang files, and mixin configs. Client-specific mixins reside in `src/client/resources/super-bridging.client.mixins.json`, and common mixins plus `fabric.mod.json` sit in `src/main/resources`. Group new classes by feature inside `ninja/trek/superbridging` to keep the package tree tidy.

## Build, Test, and Development Commands
Run `.\gradlew build` to compile, remap, and package the mod. Use `.\gradlew runClient` to launch a development client with this mod enabled; append `--args="--username DevPlayer"` if you need a fake profile. `.\gradlew runServer` starts a dedicated development server for validating shared logic. The toolchain targets Java 21, so ensure your IDE and local Gradle JVM rely on the same JDK.

## Coding Style & Naming Conventions
Stick to four-space indentation, `UpperCamelCase` for classes, `lowerCamelCase` for members, and `UPPER_SNAKE_CASE` for constants. Mirror the existing package layout and suffix mixin classes with `Mixin` for clarity. Favor expressive method names that align with Yarn terminology so code stays recognizable to anyone cross-referencing the mappings.

## Testing Guidelines
There is no automated unit suite yet, so rely on `runClient` for manual smoke testing and recreate bridging scenarios in a flat test world. When introducing gameplay logic, add lightweight logging gated by `FabricLoader.getInstance().isDevelopmentEnvironment()` to verify behaviour without polluting release builds. Consider adding Fabric GameTest harnesses under a future `src/testmod` module if repeatable verification becomes essential.

## Commit & Pull Request Guidelines
Write commits in the imperative mood (for example, `Add fast-bridge toggle`) and squash minor fixups before posting a PR. Pull requests should summarize the gameplay change, outline reproduction steps or tests you ran, and attach screenshots or clips for visual changes. Link related issues and call out data or config migrations so reviewers know what to double-check.

## API References & Research
Always confirm signatures against the current docs before editing mixins or event hooks. Consult the Minecraft Yarn 1.21.10 mappings Javadoc (`https://maven.fabricmc.net/docs/yarn-1.21.10+build.2/`) and the Fabric API 0.136.0+1.21.10 reference (`https://maven.fabricmc.net/docs/fabric-api-0.136.0+1.21.10/`) while working. Note any behavioural nuances in code comments or follow-up issues so the next contributor can build on your findings.
