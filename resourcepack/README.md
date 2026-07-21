# McParty dice resource pack

Provides custom item models:

- `mcparty:dice_1` … `mcparty:dice_6`

Server items set `item_model` to these ids (see `DiceItems`).

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

Replace `assets/mcparty/textures/item/dice_1.png` … `dice_6.png` with your own  
16×16 or 32×32 textures. Keep the same file names.

`pack_format` in `pack.mcmeta` may need bumping when the client version changes.
