# KimiOS display pipeline — latency analysis & roadmap

How pixels get from the guest GUI to the phone screen, where the latency is,
what we optimized, and the shared-memory architecture that removes VNC entirely.

## 1. Current pipeline

```
guest GUI (software render)
  -> back buffer (RAM)            [dirty-rect render]
  -> fb_present: copy dirty rect -> FRAMEBUFFER (ramfb)
  -> QEMU ramfb device
  -> QEMU VNC server (encode)     [refresh timer ~30 ms]
  -> TCP socket (127.0.0.1:5900)
  -> Android RFB client (decode)
  -> memcpy -> ANativeWindow buffer (RGBX_8888)
  -> SurfaceFlinger composite (GPU/HWC) -> panel
```

Latency sources, roughly in order of impact:
1. **VNC refresh timer.** QEMU's VNC server refreshes on a timer (~30 ms when
   active; adaptive mode backs off toward ~1 s when the screen is idle, so the
   *first* update after idle is slow).
2. **Encode/decode + socket.** Even raw encoding copies the full dirty region
   through QEMU's VNC encoder, the TCP stack, and the client decoder.
3. **Client blit.** Per-pixel color/alpha work and CPU scaling (now removed).
4. **Guest render.** Software rendering of text (bilinear per pixel) and the
   wallpaper; mitigated by dirty rectangles and instant press feedback.
5. **Input poll.** The GUI's idle sleep between input polls (now 8 ms).

## 2. Optimizations applied (no custom QEMU needed)

- **QEMU flags:** `-vnc 127.0.0.1:0,non-adaptive=on,lossy=off`.
  - `non-adaptive=on` disables the idle back-off so the refresh interval stays
    at its base (~30 ms) instead of stretching after idle — snappier first frame.
  - `lossy=off` keeps updates lossless. (Note: `lossy` takes `on/off`, not `0`.)
- **Raw encoding only.** The client advertises just Raw (0) + CopyRect (1) — no
  Tight/ZRLE/Hextile/zlib. Raw is a straight pixel copy (no compression CPU cost);
  CopyRect makes scrolling cheap. Over loopback TCP, raw bandwidth is not the
  bottleneck — encode/decode and the refresh timer are.
- **Direct memcpy, no conversion.** The surface is `WINDOW_FORMAT_RGBX_8888`
  (alpha ignored), and the requested VNC pixel format (R shift 0, G shift 8,
  B shift 16, little-endian) already lays bytes out as R,G,B,X. So the blit is a
  pure row `memcpy` — no alpha OR, no color conversion. The buffer is sized to
  the framebuffer and stretched to the view by SurfaceFlinger in **hardware**,
  so there is no CPU scaling either.
- **Guest dirty rectangles.** `fb_present(x0,y0,x1,y1)` copies only the changed
  region of the back buffer to ramfb, so VNC sends smaller updates.
- **Instant press feedback + 8 ms input poll** reduce guest-side click latency.

These are real wins, but the VNC refresh timer + encode/decode/socket round trip
remain. To go below that, remove VNC.

## 3. Next step: ashmem / shared-memory framebuffer (replace VNC)

Goal: the Android app reads the guest framebuffer **directly from shared
memory** — no VNC server, no encoding, no socket, no decode.

### Architecture

```
app: ASharedMemory_create("kimios-fb", RAM_SIZE)  -> fd  (memfd/ashmem, tmpfs)
app: pass fd/path to QEMU
QEMU: -object memory-backend-memfd,id=ram,size=128M,share=on,memfd=<fd>
      -machine memory-backend=ram         (guest RAM lives in the shared region)
app: mmap(fd, MAP_SHARED)                  -> same physical pages as guest RAM
app: framebuffer_ptr = base + FB_PHYS_OFF  (FRAMEBUFFER guest-physical address)
loop (60 fps or on dirty signal):
    memcpy surface <- framebuffer_ptr      (or AHardwareBuffer zero-copy)
    ANativeWindow_unlockAndPost
```

Key pieces:
- **Shared guest RAM.** QEMU `memory-backend-memfd` (or `memory-backend-file`
  with `mem-path` pointing at an app-created `ASharedMemory`/ashmem region) with
  `share=on` backs guest RAM with a file the app can also map. The app creates
  the region first and hands the fd to QEMU (fd inheritance across `fork`, or a
  passed path), so both map the *same* pages.
- **Framebuffer location.** `FRAMEBUFFER` is a known guest-physical address
  (see `src/fb.rs`); within the RAM region it is at a fixed offset. The app maps
  the whole RAM region and points at that offset. The back buffer / dirty
  metadata can live in a small shared control page too.
- **Synchronization.** Options, simplest first:
  1. **Poll at 60 fps** — the app copies the framebuffer every vsync. Dead
     simple; cost is one memcpy per frame even when idle (8 MB ~ <1 ms).
  2. **Shared dirty flag/counter** — the guest bumps a counter in a shared
     control page on `fb_present`; the app polls the counter (cheap) and copies
     only when it changes, optionally only the dirty rect.
  3. **eventfd** — guest signals an eventfd (via a small virtio device or a
     hypercall) on present; the app blocks on it. Lowest idle cost, most work.
- **Tearing.** Copy from the *back* buffer (the guest renders offscreen and
  publishes), or use the dirty flag as a frame boundary, so the app never reads
  a half-drawn frame.

### Why this is faster
Removes the VNC refresh-timer wait, the encoder, the TCP round trip, and the
decoder. The only remaining cost is one shared-memory memcpy + SurfaceFlinger
composite. Latency drops from "~30 ms VNC + pipeline" to "~one vsync (16 ms) +
memcpy", and idle cost is near zero with a dirty flag.

### Effort / risk
- App side: `ASharedMemory` (API 27+; minSdk is 30, fine), mmap, a render loop,
  fd passing to the QEMU subprocess. Moderate.
- QEMU side: stock `memory-backend-memfd`/`-file` (the bundled QEMU 10.2.1
  supports these) — **no custom QEMU build required** for the basic version.
- Guest side: expose the framebuffer physical offset (already known) and,
  optionally, a shared dirty counter. Small.
- This supersedes the RFB client entirely (keep it as a fallback).

## 4. GPU acceleration / direct rendering — honest assessment

- **Guest GUI is software-rendered.** ramfb is a dumb linear framebuffer; there
  is no guest GPU. The Nothing OS UI is drawn by the CPU in `kimi-gui`. You
  cannot "enable GPU acceleration" for that rendering without giving the guest a
  GPU (virtio-gpu) and rewriting the UI against a GPU API — a large project, not
  a flag.
- **Android composition is already GPU-accelerated.** SurfaceFlinger composites
  the SurfaceView with the GPU / hardware composer. The CPU `memcpy` into the
  surface is the only software step; the stretch-to-view and panel output are
  hardware. So "direct rendering" to the panel is effectively already happening
  via the SurfaceView.
- **virtio-gpu + virgl/venus** would give the guest real GPU acceleration
  (QEMU `-device virtio-gpu-gl -display egl-headless`, virglrenderer on the
  host). On Android this needs host EGL/GPU access and a guest virtio-gpu driver
  plus a GPU-backed UI — substantial, and orthogonal to the latency problem
  (which is the display *transport*, not guest rendering).
- **Zero-copy on Android:** `AHardwareBuffer` + `ANativeWindow` can avoid one
  copy, but the guest framebuffer lives in QEMU memory, so a copy from guest RAM
  is unavoidable unless that RAM *is* the shared region (section 3). ashmem is
  therefore the highest-leverage change.

**Bottom line:** the practical latency win is the shared-memory framebuffer
(section 3), not GPU flags. The current release applies the no-custom-QEMU
optimizations (section 2); ashmem is the recommended next milestone.