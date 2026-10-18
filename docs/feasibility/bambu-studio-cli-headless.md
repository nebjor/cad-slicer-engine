# Feasibility: headless Bambu Studio CLI in Docker

**Question:** can Bambu Studio slice an uploaded STL on a headless Linux/Docker host, and does
it emit data good enough to price a print?

**Answer: yes, verified.** A slice ran to completion in a throwaway container with no
`DISPLAY` and no `xvfb`, using the Bambu Lab A1 profiles shipped inside the AppImage, and
produced both G-code and a machine-readable `result.json`.

Everything below was executed on 2026-10-14. Claims are separated into **verified**,
**observed but not fully explained**, and **unresolved**.

| Item | Value |
|---|---|
| Date | 2026-10-14 |
| Bambu Studio | `2.8.2.61` (tag `v02.08.02.61`), `BambuStudio_ubuntu24.04-v02.08.02.61-20260820225108.AppImage` |
| Container base | `ubuntu:24.04` (Ubuntu 24.04.5 LTS) |
| Architecture | `x86_64` |
| Docker | `20.10.22`, Compose `v2.15.1`, server OS type `linux` |
| Probe location | scratch directory, deliberately **not** added to the repository (Phase 0 adds no infrastructure) |

---

## 1. Reproduction

### 1.1 Image

```dockerfile
FROM ubuntu:24.04
ARG APPIMAGE=BambuStudio_ubuntu24.04-v02.08.02.61-20260820225108.AppImage
ARG TAG=v02.08.02.61

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl ca-certificates file xvfb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/bambu
RUN curl -fsSL -o bs.AppImage \
      "https://github.com/bambulab/BambuStudio/releases/download/${TAG}/${APPIMAGE}" \
 && chmod +x bs.AppImage

# --appimage-extract avoids needing FUSE inside the container.
RUN ./bs.AppImage --appimage-extract >/dev/null

# Runtime libraries the binary links against. Installed one-by-one so a single
# wrong package name does not fail the build.
RUN apt-get update && for p in \
      libfontconfig1 libfreetype6 libgtk-3-0t64 libwebkit2gtk-4.1-0 libsoup-3.0-0 \
      libgl1 libglu1-mesa libegl1 libgles2 libosmesa6 libglx-mesa0 mesa-utils \
      libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 libgstreamer-gl1.0-0 \
      libsecret-1-0 libnotify4 libxkbcommon0 libdbus-1-3 libcurl4 \
      libsm6 libice6 libxtst6 libxrandr2 libxi6 libxcursor1 libxinerama1 libxdamage1 \
      libatk1.0-0t64 libatk-bridge2.0-0t64 libcairo2 libpango-1.0-0 libpangocairo-1.0-0 \
      libharfbuzz0b libgdk-pixbuf-2.0-0 libnss3 libasound2t64 libdrm2 libgbm1 \
      fonts-dejavu-core locales ; do \
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "$p" \
        || echo "SKIPPED_PACKAGE: $p" ; \
    done; rm -rf /var/lib/apt/lists/*
```

Every package in that list installed successfully on Ubuntu 24.04 — no `SKIPPED_PACKAGE`
lines appeared. The set was derived by iterating on `ldd ... | grep "not found"`; the first
attempt with no GUI libraries failed immediately (§3.1).

### 1.2 The verified slice command

```bash
APPRUN=/opt/bambu/squashfs-root/AppRun
P=/opt/bambu/squashfs-root/resources/profiles/BBL

"$APPRUN" --slice 0 \
  --load-settings "$P/machine/Bambu Lab A1 0.4 nozzle.json;$P/process/0.20mm Standard @BBL A1.json" \
  --load-filaments "$P/filament/Bambu PLA Basic @BBL A1.json" \
  --outputdir /tmp/a1 \
  /tmp/cube.stl
```

Exit code `0`. `/tmp/a1` then contained:

```
-rw-r--r-- 1 root root 60473 plate_1.gcode
-rw-r--r-- 1 root root  2022 result.json
```

The test model was a 10 mm ASCII-STL tetrahedron generated inline — no model was downloaded.
`--info` on it reported `manifold = yes`, `number_of_facets = 4`, `volume = 166.666672`.

---

## 2. Verified findings

**V1. `xvfb` is not required.** `--help`, `--info` and a full `--slice` all succeeded with
`DISPLAY` unset. Running the same commands under `xvfb-run -a` produced identical results.
This directly contradicts the project's current configuration
(`slicer.binary-path: "xvfb-run bambu-studio-cli"`), which pulls in an X server dependency
the slicing path does not need.

**V2. The output file is `plate_1.gcode`, not `<inputname>.gcode`.** The plate index drives
the name. `SlicerExecutionService.executeSlicing()` computes
`inputFile.getName().replaceAll("\\.(stl|3mf)$", ".gcode")` and returns that path, which will
not exist on disk. This is a confirmed Phase 5 defect.

**V3. A second output file, `result.json`, is the right thing to parse.** Observed keys:

```json
{
  "error_string": "Success.",
  "return_code": 0,
  "layer_height": 0.20000000298023224,
  "plate_index": 0,
  "sliced_plates": [{
    "feature_type_times": { "Outer wall": 64.78, "Inner wall": 46.79, "Sparse infill": 11.37,
                            "Travel": 32.67, "Internal solid infill": 35.22, "...": 0 },
    "filaments": [{ "filament_id": "", "id": 1, "main_used_g": 0.0, "total_used_g": 0.0 }],
    "objects": [{ "name": "cube.stl", "triangle_count": 4,
                  "bbox": { "width": 10.0, "depth": 10.000001907348633, "height": 10.0,
                            "x": 95.0, "y": 95.00000762939453, "z": 0.0 } }],
    "main_predication": 198.491943359375,
    "total_predication": 199.23666381835938,
    "sliced_time": 75
  }],
  "sparse_infill_density": 20.0,
  "wall_loops": 2
}
```

`return_code` and `error_string` give a structured success/failure signal, and
`total_predication` (seconds) is the machine-time input the quotation needs. Parsing this is
strictly better than scraping G-code comments.

**V4. The bundled A1 profiles load, despite the wiki's warning.** The official
[Command Line Usage](https://github.com/bambulab/BambuStudio/wiki/Command-Line-Usage) wiki
says the `--load-settings` config "should be a full config instead of the one used in
`resources/profiles/BBL/machine`". The top-level profiles nevertheless applied cleanly — the
generated G-code header confirms it:

```
; printer_settings_id = Bambu Lab A1 0.4 nozzle
; print_settings_id = 0.20mm Standard @BBL A1
; filament_settings_id = "Bambu PLA Basic @BBL A1"
; printer_model = Bambu Lab A1
; nozzle_diameter = 0.4
; layer_height = 0.2
; filament_type = PLA
```

Not every file in those directories is a profile: `machine/` also contains G-code *fragment*
files such as `Bambu Lab A1 0.4 nozzle template machine_start_gcode.json`. The selector must
match the exact profile name, not a glob.

**V5. Slicing with no settings at all also succeeds** and writes `plate_1.gcode` — it falls
back to defaults. That is a trap: a job would appear to succeed while pricing against the
wrong machine. Profiles must always be passed explicitly.

**V6. Quotation-relevant G-code trailer fields** (useful as a cross-check on `result.json`):

```
; model printing time: 3m 18s; total estimated time: 3m 19s
; total filament length [mm] : 59.47
; total filament volume [cm^3] : 143.05
; total filament weight [g] : 0.00
; max_z_height: 10.00
```

---

## 3. Observed but not fully explained

**O1. Three shared libraries are still missing, harmlessly.**
`ldd squashfs-root/bin/bambu-studio` reports:

```
libavcodec.so.61 => not found
libswscale.so.8  => not found
libavutil.so.59  => not found
```

These are FFmpeg libraries, almost certainly used for timelapse/video export. Slicing worked
anyway. They should be installed before anything calls `--enable-timelapse` or a PNG/video
export flag.

**O2. GLFW and Wayland errors are logged but non-fatal.** The no-settings slice emitted:

```
error: XDG_RUNTIME_DIR is invalid or not set in the environment.
[error] error_code 65544, description: Wayland: Failed to connect to display
[error] glfwInit return error, code 65544
```

and still exited `0` with correct output. **Consequence for the implementation: exit code is
the success signal, not the presence of `error` in stderr.** A naive log-scraping check would
reject every successful slice. Cross-check `result.json`'s `return_code`/`error_string`
instead.

**O3. The gram figures are unusable for small parts.** Both
`result.json` (`total_used_g: 0.0`) and the G-code trailer
(`total filament weight [g] : 0.00`) reported zero for a part that used 59.47 mm of filament
— roughly 0.14 cm³, about 0.18 g of PLA. The `total filament volume [cm^3] : 143.05` figure
is also suspicious: 143 is the volume in **cubic millimetres**, so that label appears to be
wrong by a factor of 1000. Pricing should compute mass from filament length and material
density and treat the slicer's gram value as a sanity check only. Needs re-testing on a
realistically sized model.

---

## 4. Unresolved questions

1. **Does it hold for real models?** The probe used a 4-triangle tetrahedron. Nothing here
   establishes CPU time, peak RSS or wall-clock for a 50 MB STL with supports — the numbers
   that actually size the Hetzner server and set the job timeout. **Unverified.**
2. **Are the gram fields usable at realistic scale, or must mass always be derived?** (O3.)
   **Unverified.**
3. **How should user-selected options be applied?** The probe used stock profiles. Whether
   layer height / infill / material should be command-line overrides (documented as highest
   priority) or generated profile JSON per job is an open design question for Phase 5.
4. **Licensing for commercial use.** Bambu Studio is AGPL-3.0; it is being invoked as a
   separate process rather than linked, which is the right boundary, but running it as part of
   a paid service warrants a deliberate read of the licence before Phase 8.
5. **Image size and build reproducibility.** Measured: the AppImage is 230,025,720 bytes
   (219 MiB), extracting to 541 MB, and the finished probe image is 1.43 GB. It is downloaded
   with no checksum verification on every rebuild. Phase 5 should pin the release and verify a
   checksum, and consider shipping only `squashfs-root` rather than both.
6. **x86_64 only.** Confirmed working on `x86_64`. The release publishes no ARM64 AppImage
   (assets are Ubuntu 22.04/24.04 x86_64, macOS and Windows only), so the Hetzner instance must
   be an x86 one. Whether that constrains the cheaper instance line is a pricing question to
   check against Hetzner's current catalogue — **unverified here**.

---

## 5. Recommendations for Phase 5

- Drop `xvfb-run` from `slicer.binary-path` (V1).
- Treat `--outputdir` + `plate_N.gcode` as the output contract; do not predict a filename from
  the input name (V2).
- Parse `result.json`, assert `return_code == 0`, and ignore stderr noise (V3, O2).
- Always pass explicit machine/process/filament profiles; never rely on defaults (V5).
- Derive mass from length × density; cross-check against the slicer's grams (O3).
- Bake the slicer into its own image and keep it out of the ordinary `mvn verify` path.

## Sources

- [Command Line Usage · bambulab/BambuStudio Wiki](https://github.com/bambulab/BambuStudio/wiki/Command-Line-Usage)
- [Bambu Studio CLI Reference — every flag for headless slicing (Printago)](https://printago.io/slicer-cli/bambu)
- [Tim-Gabrikowski/bambu-docker-wrapper](https://github.com/Tim-Gabrikowski/bambu-docker-wrapper)
- [bambulab/BambuStudio releases](https://github.com/bambulab/BambuStudio/releases)
