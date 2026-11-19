# McParty dice resource pack

Provides custom item models:

- `mcparty:dice_1` … `mcparty:dice_6`
- Dice skins for placement and shop dice (`DiceSkin`): `dice_bronze_1..3`, `dice_silver_2..5`,
  `dice_gold_4..6`, and the Chaos Die `dice_chaos_m3`, `m2`, `m1`, `0`, `1..6` (m = minus).
  They reuse the `dice_N` model with a recoloured frame; each face also has a glyph (E015–E028)
- `mcparty:tnt_multishot` for the TNT Spleef Multishot power-up
- `mcparty:sugar_rush` 16×16 chubby swirl lollipop for the Snow Spleef Sugar Rush candy (`SUGAR` item)
- `mcparty:snow_shovel`, `mcparty:throwing_tnt` — Snow Spleef shovel and throwable TNT (handheld)
- Props for upcoming minigames (`item/generated`, usable on items or `ItemDisplay`):
  `crown` (King of the Hill), `target` (Shooting Range), `checkpoint_flag` / `finish_flag` /
  `speed_boost` (races), `jump_marker` (Block Jump), `queen_core` (Antwar), `noise_maker` (Warden Escape)
- `textures/minigame/*.png` — minigame icons; `font-images.yml` maps every minigame to `mg_<id>`
  for the final reveal title
- `mcparty:elytra_ring` for server-spawned Elytra Race ring displays
- `mcparty:snow_shovel` 3D Snow Spleef shovel (64×64 atlas, 1 texel per model unit, blade up along +Y)
- `mcparty:musical_chair` Musical Chairs seat (64×64 atlas). With the `FIXED` item-display transform the
  seat faces the display's yaw and is centred on its location — spawn it 0.5 block above the floor
- `mcparty:claw_blade` 3D sci-fi claw blade (64×64 atlas, blade up +Y, edge −Z; grip sits where
  Cytooxien's katana grip does, so its hand transforms carry over)
- `mcparty:laser_gun` 3D Laser Tag blaster (32×32 atlas, points −Z; light-blue parts use `light_emission`)
- `mcparty:power_up_box` + `mcparty:power_up_question` for the shared minigame power-up box
  sharing one `power_up_box.png` sheet. The inside-out `rainbow_inside` element (`from` > `to`) is listed
  before the `glass` shell so the translucent glass blends over it instead of hiding it

## Font images

McParty also generates bitmap font glyphs from `plugins/McParty/font-images.yml`.
Place a texture under `assets/mcparty/textures/` in the local pack source and
define it by id:

```yaml
images:
  coin:
    texture: hud/coin.png
    scale: 8
    y-position: 8
    codepoint: E000
```

The same image can be used in `messages.yml` as `%img_coin%`. This works in
chat, action bars, and the configurable tab list.
The bundled `dice_1` … `dice_6` images and the dice skin faces are also used
automatically for the post-roll action-bar result; a face without a glyph falls
back to a coloured number.
Local mode generates `assets/mcparty/font/images.json` before each ZIP is
created. External mode requires the hosted ZIP to contain a matching generated
font file.

Each id is **one cube, one texture** (all 6 sides use that face art). The server
swaps models while spinning (`DiceItems.face` → `item_model` `mcparty:dice_N`).
Not a single multi-face die mesh.

## Automatic (plugin)

McParty can prompt players for this pack (see `config.yml` → `resource-pack`).

### Local host (default)

1. Leave `resource-pack.mode: local`.
2. On enable the plugin zips `plugins/McParty/resourcepack/` (bundled pack is extracted if missing), computes SHA-1, and serves HTTP on `local.port` (default **8163**).
3. Set **`resource-pack.local.public-url`** to a URL **clients** can open, e.g.  
   `http://YOUR_PUBLIC_IP:8163/mcparty.zip`  
   or a reverse-proxy HTTPS URL that forwards to that port.
4. Open the port in the firewall / panel. Without `public-url`, only `127.0.0.1` is used (same-machine clients only).

### External host

1. Zip this folder so `pack.mcmeta` is at the zip root (not a nested `resourcepack/`).
2. Upload somewhere with a **direct** download URL (HTTPS recommended).
3. Config:

```yaml
resource-pack:
  mode: external
  external:
    url: "https://cdn.example.com/mcparty.zip"
    sha1: "40-char-lowercase-sha1-of-that-zip"
```

PowerShell SHA-1:

```powershell
(Get-FileHash -Algorithm SHA1 .\mcparty.zip).Hash.ToLower()
```

### When players get the prompt

- `send-on: party` (default) — on `/party create` or `/party join`
- `send-on: join` — every player login

`required` / `kick-on-decline` control enforcement. Without the pack, dice still work but look like plain paper.

Do not also set the same pack in `server.properties` unless you know you want two packs stacked.

## Manual client install

1. Zip the **contents** of this folder so `pack.mcmeta` is at the zip root.
2. Put the zip in the client `resourcepacks/` folder and enable it.

## Art

Replace `assets/mcparty/textures/item/dice_1.png` … `dice_6.png` with your own.
Textures are **14×14**. Models use `"texture_size": [14, 14]` and UV `[0,0,14,14]`
so each face uses the full PNG.

Models are a **14×14×14** cube (`from [1,1,1]` → `to [15,15,15]`) with the result
face on **up** and opposites summing to 7. Side faces use the other numbers.

`pack_format` in `pack.mcmeta` may need bumping when the client version changes.
