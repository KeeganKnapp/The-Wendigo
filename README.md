# Example Mod

## Requirements

Server-side only. Requires [Advanced Wall Climber API](https://modrinth.com/mod/advanced-wall-climber-api) (by Nyfaria) to be installed separately in the server's `mods/` folder - unlike Polymer, it is not bundled into this mod's jar (its license forbids embedding). No client-side mod is required for players connecting to the server.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Building & releasing

- `.github/workflows/build.yml` runs on every push/PR to `main`: compiles, runs the GameTest
  suite, builds the jar, and uploads it as a build artifact.
- `.github/workflows/release.yml` fires when a tag matching `vX.Y.Z` (e.g. `v1.0.0`) is pushed -
  it builds the jar and publishes a GitHub Release with the jar attached. Keep the tag in sync
  with `mod_version` in `gradle.properties`; the workflow doesn't bump that for you.

To cut a release: bump `mod_version` in `gradle.properties`, commit, then
`git tag v<version> && git push origin v<version>`.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
