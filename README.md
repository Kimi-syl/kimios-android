# KimiOS Android Frontend

An Android app that runs [KimiOS](https://github.com/Kimi-syl/kimios) — a
bare-metal ARM64 Rust OS with a Nothing-OS x Material-3 phone GUI — on your
phone, inside a bundled QEMU. No root, no PC, no external downloads: the
kernel, disk image, QEMU 10.2.1 and a kpkg package repository all ship inside
the APK.

Current version: **v0.8.0** (`versionCode` 14).
Releases: <https://github.com/Kimi-syl/kimios-android/releases>

## What you get

- The full KimiOS phone GUI (lock screen, launcher, Quick Settings,
  Calculator, Clock, Terminal, browser, ...) rendered in a SurfaceView.
- Touch and keyboard input forwarded to the guest (virtio-tablet /
  virtio-keyboard via QMP).
- Guest networking through QEMU user-mode NAT (SLIRP): DHCP, DNS, TCP, the
  in-GUI web browser.
- **Offline package management:** a local HTTP server serves the bundled
  kpkg repo, so `kpkg update` / `kpkg install busybox` work with no external
  network. BusyBox (402 applets), static musl hello/fib/fortune/uname are
  included; curl/npm/pip are listed but marked not-runnable (they need a
  larger Linux ABI than the guest currently implements).
- VM configuration sidebar: resolution, vCPU count, RAM and disk size;
  live boot log; memory inspector; file insertion into the guest disk.

## Requirements

| Item | Value | Why |
|---|---|---|
| Device ABI | `arm64-v8a` only | QEMU + the native bridge are AArch64 |
| minSdk | 30 (Android 11) | `ASharedMemory`/modern surface APIs, 64-bit-only devices |
| targetSdk | **28** | QEMU's TCG JIT needs writable+executable memory; Android enforces W^X only for apps targeting API 29+. Same approach as Termux |
| compileSdk | 35 | build toolchain |
| Emulation | TCG (software) | no KVM on Android; expect modest speed, not native performance |

Because `targetSdk` is 28, install by sideloading the APK (adb or file
manager); Play Store distribution is not intended.

## How it works

```text
MainActivity (SurfaceView + settings sidebar)
  |  start/stop, config, logs, file insertion
  v
QemuService (foreground service, wake lock)
  |  AssetInstaller: copy assets/kimios + assets/qemu to filesDir
  |  create/resize disk.img (KimiFS formats it on first guest mount)
  |  RepoServer.start()  -> bundled kpkg repo on 127.0.0.1:8080
  v
bundled QEMU 10.2.1 (jniLibs/arm64-v8a/libqemu.so, Termux bionic build)
  -machine virt,gic-version=3 -cpu cortex-a53 -smp N -m NM
  -device ramfb -device virtio-keyboard-device -device virtio-tablet-device
  -netdev user,id=n0 -device virtio-net-device,netdev=n0
  -drive ...disk.img -device virtio-blk-device
  -vnc 127.0.0.1:0,non-adaptive=on,lossy=off
  -qmp unix:...qmp.sock,server,nowait
  -append kimios.fb=<width>x<height>  -kernel .../kimios
  |
  |-- VNC (127.0.0.1:5900) --> native RFB client (cpp/rfb_client.cpp)
  |       Raw + CopyRect only; DesktopSize/Cursor/LastRect pseudo-encodings
  |       handled; row memcpy into an RGBX_8888 ANativeWindow buffer
  |       (no color conversion, no CPU scaling — SurfaceFlinger stretches
  |       in hardware) --> panel
  |
  |-- QMP unix socket --> cpp/qmp_client.cpp: touch/keys from
  |       NativeBridge.nativePointer/nativeKey become input-send-event
  |
  `-- SLIRP NAT: guest's 10.0.2.2 = host loopback, so the guest's kpkg
        (default base http://10.0.2.2:8080) reaches RepoServer.kt, which
        serves app/src/main/assets/kpkg/ (repo.json + static binaries)
```

Key files:

- `app/src/main/java/com/kimios/frontend/QemuService.kt` — builds the QEMU
  command line, owns the process lifecycle, status broadcasts/notification.
- `app/src/main/java/com/kimios/frontend/RepoServer.kt` — tiny GET-only
  HTTP/1.0 server for `assets/kpkg/` on `127.0.0.1:8080` (path-traversal
  guarded). The guest sees it at `10.0.2.2:8080` through SLIRP.
- `app/src/main/cpp/rfb_client.cpp` — the VNC/RFB display client.
- `app/src/main/cpp/qmp_client.cpp`, `native-lib.cpp` — QMP input bridge,
  JNI surface handling (`NativeBridge.kt`).
- `app/src/main/java/com/kimios/frontend/AssetInstaller.kt` — first-boot
  extraction of `assets/kimios/` (kernel ELF, `virt.dtb`, `disk.img`) and
  `assets/qemu/` (shared libraries, BIOS/share files). An existing
  `disk.img` is preserved across app updates.
- `app/src/main/java/com/kimios/frontend/KimiFs.kt` — direct KimiFS image
  reader/writer used for file insertion **while the VM is stopped** (the
  guest rebuilds its allocation bitmap at mount, so no on-disk bitmap needs
  maintaining here).

The QEMU binary is packaged as `jniLibs/arm64-v8a/libqemu.so` so Android
allows executing it from `nativeLibraryDir` (the W^X workaround). The guest
kernel is the hardware-independent graphics build: it tries VirtIO-GPU 2D
and auto-falls back to ramfb, which is what the VNC pipeline displays.

## Build

Toolchain: JDK 17, Android SDK (compileSdk 35), NDK 26.1, CMake. The native
bridge is built with a CMake toolchain file; defaults match the project's
build environment and can be overridden:

```bash
# optional overrides (defaults shown)
export KIMIOS_TOOLCHAIN=/opt/native-toolchain.cmake
export KIMIOS_ANDROID_LIB_DIR=/opt/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26
export JAVA_HOME=/opt/jdk17

./build-native.sh
```

`build-native.sh` does three things:

1. CMake-configures and builds `app/src/main/cpp` (Release, PIC) into
   `build-native/`, then copies `libkimios-bridge.so` (and `libc++_shared.so`
   when present) into `app/src/main/jniLibs/arm64-v8a/`.
2. Runs `./gradlew --no-daemon :app:assembleDebug` (using an ARM-host aapt2
   override when available).
3. Prints the resulting APK: `app/build/outputs/apk/debug/app-debug.apk`.

The bundled QEMU is already checked in (`jniLibs/arm64-v8a/libqemu.so` +
`assets/qemu/lib`). To replace it with another Android/bionic-linked
aarch64 QEMU build:

```bash
scripts/install-qemu.sh <path-to-qemu-system-aarch64> [lib-dir]
./build-native.sh
```

The binary must run under Android bionic (static or NDK build) — a
glibc-linked proot/Termux binary will not work. To refresh the guest kernel
or disk, replace `app/src/main/assets/kimios/{kimios,virt.dtb,disk.img}`
with artifacts from the [OS repo](https://github.com/Kimi-syl/kimios)
(`cargo build --release`, target `aarch64-unknown-none`).

## Install and run

1. Install the APK (release from
   [Releases](https://github.com/Kimi-syl/kimios-android/releases), or your
   own `app-debug.apk`): `adb install KimiOS-Frontend-v0.8.0.apk`.
2. Open the app and start the VM. First boot extracts assets and creates
   the disk image, so it takes a while; the sidebar shows the live boot log
   (`qemu.log` + guest `serial.log`).
3. The guest boots to the KimiOS lock screen (~0.4 s kernel boot under
   emulation, plus QEMU startup). Tap/swipe up to unlock.
4. Adjust resolution/vCPU/RAM/disk in the sidebar before starting; insert
   files into `/disk` while the VM is stopped, then run them in the guest
   Terminal (`run /disk/<file>`).
5. In the guest Terminal: `kpkg update`, `kpkg install busybox`,
   `kpkg run busybox ls -l` — all served locally by the app.

## Version history (highlights)

- **v0.8.0** — bundles the hardware-independent graphics kernel (backend
  dispatch + VirtIO-GPU 2D driver + `gfxbench`).
- **v0.7.2** — lower display latency: VNC non-adaptive/lossless raw, RGBX
  direct-memcpy blit; DNS fix + dirty-rect kernel.
- **v0.7.0/v0.7.1** — BusyBox in the bundled repo; double-buffered kernel
  fixes GUI flicker; RFB opaque-alpha + pseudo-encoding fixes end the
  reconnect loop.
- **v0.6.0** — The Pivot: Linux ABI + kpkg with the bundled local repo
  server; ~0.4 s boot.
- **v0.5.0** — mini-Dalvik DEX execution + sample `.dex` disk.
- **v0.4.0** — on-device networking, web browser, APK inspector.
- **v0.3.x** — Nothing OS fonts, auto fit-screen resolution, host time sync,
  aspect-adaptive guest UI.
- **v0.2.x** — W^X fix, VM config sidebar, live boot logs, memory inspector,
  file insertion; first-frame and VNC-port fixes.
- **v0.1.0** — initial frontend: bundled QEMU, native RFB display, QMP input.

## Known limitations

- **Software emulation only** (TCG, no KVM): the guest is functional but
  slow compared to native hardware; release kernels help.
- **Display latency floor:** the VNC refresh timer (~30 ms) plus
  encode/decode/socket remains after all current optimizations. The next
  milestone is an **ashmem/shared-memory framebuffer** that removes VNC
  entirely — design and analysis in
  [docs/display-pipeline.md](docs/display-pipeline.md).
- **VirtIO-GPU 2D** in the guest currently falls back to ramfb
  (`attach_backing` fails on the bundled QEMU); see the OS repo's
  `docs/graphics-architecture.md`.
- Interactive `busybox sh` needs fork/execve, which the guest Linux ABI does
  not implement yet; individual applets work.
- `targetSdk 28` is deliberate (W^X for the TCG JIT); the app is meant to be
  sideloaded, not Play-distributed.

## Links

- OS kernel: <https://github.com/Kimi-syl/kimios>
- Display latency analysis and ashmem roadmap:
  [docs/display-pipeline.md](docs/display-pipeline.md)
- Releases (APKs): <https://github.com/Kimi-syl/kimios-android/releases>
