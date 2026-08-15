# Staged fork code

These sources are **not on the build's compile path yet**, and that is deliberate.

They were written against Paper/Folia 1.21.x internals. Canvas is on Minecraft 26.2, and several
of the APIs they touch — `ChunkPacketBlockController`, `ChunkPacketInfo`, `PalettedContainer`
accessors, Folia's `TickRegionScheduler` reporting — will have moved or changed signature. Dropping
them straight into `src/main/java` means `compileJava` fails and you get no jar at all.

So the scaffold builds first, and the code goes in once its signatures are checked against the
actual decompiled 26.2 source.

## What is here

| Path | Goes to | Notes |
| --- | --- | --- |
| `server/gg/havoc/folia/` | `havocfolia-server/src/main/java/` | The feature code |
| `api/gg/havoc/folia/api/` | `havocfolia-api/src/main/java/` | Plugin-facing interfaces |
| `tests/gg/havoc/folia/` | `havocfolia-server/src/test/java/` | 34 assertions, no NMS needed |

## Which files touch NMS

Verify these against the decompiled source before moving them in — the `upstream-sources`
CI artifact contains exactly the files they depend on:

| File | Depends on |
| --- | --- |
| `antifreecam/AntiFreecamController.java` | `ChunkPacketBlockController`, `ChunkPacketInfo` |
| `antifreecam/SectionCodec.java` | `PalettedContainer`, `LevelChunkSection`, `Palette` |
| `antifreecam/PresetBlocks.java` | `Block.BLOCK_STATE_REGISTRY`, `BuiltInRegistries` |
| `antifreecam/RefreshQueue.java` | `Level`, `BlockPos` |
| `command/HavocCommand.java` | `Commands`, `CommandSourceStack` |
| `command/RegionReport.java` | Folia `TickRegions`, `ThreadedRegionizer` |
| `view/RegionMspt.java` | Folia `TickRegionScheduler` |
| `HavocFolia.java` | `ServerLevel`, `Level` |

These need **no** NMS and can go in immediately:
`antifreecam/DepthObfuscator.java`, `scheduler/PinStrategy.java`, `scheduler/AffinityManager.java`,
`view/AdaptiveViewDistance.java`, `config/HavocConfig.java`, `util/Format.java` (Adventure only).

## Moving code in

```bash
mkdir -p havocfolia-server/src/main/java
cp -r fork-code/server/gg havocfolia-server/src/main/java/
./gradlew compileJava        # fix signatures against the real source, then repeat
```

Do it a few files at a time. `compileJava` is much faster than a full patch apply.
