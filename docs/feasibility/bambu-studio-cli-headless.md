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
   that actually size the Hetzner server and set the job timeout. **Unverified.** The
   measurement procedure and the table to fill are in section 6.
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

## 6. Resource measurement (to be filled)

Open question 1 above, made executable. `scripts/measure-slice.sh` in the backend repository
slices one model repeatedly with the CLI arguments `SlicerExecutionService` uses and the
flattened profiles `SlicerProfileResolver` writes (supports and bed type included), for one
copy and for four copies, and prints the table below. It prefers GNU `time -v` and falls back
to bash timing plus `/proc` sampling for peak RSS where `time` is not installed (the
production image has no `time`; the report states which method produced the numbers).

The figures to record: a realistic customer model (an STL of at least 10 MB that needs
supports; the 11 MB Benchy is the lower bound, a model with overhangs is better), quantity 1
and 4, three runs each, on the production host inside the backend container, whose limit is
2 CPUs and 3 GB (`deploy/docker-compose.prod.yml` in pod-frontend). The slicer timeout
(`slicer.timeout-seconds`, 120 s today) and the container memory limit are then set from the
maximum wall-clock and peak RSS of the quantity 4 run with headroom, and the concurrency of
two slots (`slicer.capacity.max-concurrent`) is checked against twice the peak RSS.

### Command

On the production host (the image holds only the jar, so the script and the model are copied
in first; the backend must be running in the container, because the first call asks it for one
quote so that the flattened profiles exist under `/tmp`):

```bash
cd ~/pod-frontend/deploy
docker compose -f docker-compose.prod.yml cp ../../cad-slicer-engine/scripts/measure-slice.sh backend:/tmp/measure-slice.sh
docker compose -f docker-compose.prod.yml cp model.stl backend:/tmp/model.stl
docker compose -f docker-compose.prod.yml exec backend bash /tmp/measure-slice.sh -n 3 -q "1 4" /tmp/model.stl
```

In the development stack (pod-frontend repository), where the Benchy is mounted:

```bash
docker compose -f docker-compose.local.yml exec backend scripts/measure-slice.sh -n 3 /workspace/public/3DBenchy.stl
```

`-Q high` measures the finest quality tier, which has the most layers; `-F PETG` another
filament. `scripts/measure-slice.sh -h` lists every option.

### Results

_Placeholder: paste the script's output here, with the date, the host and the model named._

| Date | Host | Model (size) | Method |
|---|---|---|---|
| _not yet measured_ | | | |

| quantity | run | wall s | CPU s | peak RSS MB | plates | predicted print s | exit |
|---|---|---|---|---|---|---|---|
| 1 | 1 | | | | | | |
| 1 | 2 | | | | | | |
| 1 | 3 | | | | | | |
| 1 | max / mean | | | | | | |
| 4 | 1 | | | | | | |
| 4 | 2 | | | | | | |
| 4 | 3 | | | | | | |
| 4 | max / mean | | | | | | |

Decisions once filled: `slicer.timeout-seconds` = _____ (max wall-clock of quantity 4 with
headroom), backend memory limit = _____ (JVM plus two concurrent slices at peak RSS), and
whether `max-concurrent: 2` holds on the production CPU count.

### Quantity scaling, production 2026-10-21

Benchy, 60 x 31 x 48 mm, PLA, 0.20 mm, A1 profiles with tree supports, sliced as one batch
with `--clone-objects N --arrange 1` on the production server (the quote path before
ADR 0006). Request time is the wall-clock of `POST /api/quotes` including the `--info` call.

| copies | request time | plates | print time total | per copy | grams per copy |
|---|---|---|---|---|---|
| 1 | 9 s | 1 | 2438 s | 2438 | 12.4 |
| 4 | 23 s | 1 | 8605 s | 2151 | 12.3 |
| 10 | 38 s | 1 | 21213 s | 2121 | 12.3 |
| 20 | 42 s | 2 | 42532 s | 2127 | 12.3 |

Reading: slicing time grows with the number of copies (roughly 1.5 to 3.5 s per copy on top
of the single slice), material is linear, and print time is linear per copy with a fixed part
per plate of about 300 s (2438 - 2127 = 311 s: heating, purge line, first layer). The second
plate at twenty copies is where the slicer's arrange ran out of bed at 15 copies. These
numbers are the basis of the quantity arithmetic in
[ADR 0006](../adr/0006-quantity-by-arithmetic.md), which replaced the batch slice.

## Sources

- [Command Line Usage · bambulab/BambuStudio Wiki](https://github.com/bambulab/BambuStudio/wiki/Command-Line-Usage)
- [Bambu Studio CLI Reference — every flag for headless slicing (Printago)](https://printago.io/slicer-cli/bambu)
- [Tim-Gabrikowski/bambu-docker-wrapper](https://github.com/Tim-Gabrikowski/bambu-docker-wrapper)
- [bambulab/BambuStudio releases](https://github.com/bambulab/BambuStudio/releases)
