# McParty dice resource pack

Provides custom item models:

- `mcparty:dice_1` … `mcparty:dice_6`

Server items set `item_model` to these ids (see `DiceItems`).

## Install

1. Zip the **contents** of this folder so `pack.mcmeta` is at the zip root  
   (not a nested `resourcepack/` folder).
2. Put the zip in the client `resourcepacks/` folder, **or** host it and set  
   `resource-pack` / `require-resource-pack` in `server.properties`.
3. Enable the pack in the client (or accept the server prompt).

Without this pack, dice still work but look like plain paper.

## Art

Replace `assets/mcparty/textures/item/dice_1.png` … `dice_6.png` with your own  
16×16 or 32×32 textures. Keep the same file names.

`pack_format` in `pack.mcmeta` may need bumping when the client version changes.
