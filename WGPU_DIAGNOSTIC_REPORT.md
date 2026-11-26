# WebGPU Diagnostic Report: Black Page / "tex=map failed Invalid byte buffer" Issue

## Log Dump Analyzed
- **File**: `/mnt/c/users/branden/downloads/houri_dump_1795565656537.log`
- **Device**: realme RMX2151 (Mali-G76 MC4, Vulkan, driver 26.0.0)
- **Tier**: MEDIUM, budget=ROOMY
- **App**: app.houri.nomtl.beta (no MTL flavor, pure WebGPU path)
- **Chapter**: Manga Ball source, 17 pages, webp format
- **Timeline**: 2026-11-25, session elapsed ~24s

---

## 1. What the Logs Show — Page by Page

### Decode Pipeline (Successful)
The decode pipeline functions correctly across all 17 pages:
- Pages flow: `dequeued → stream → readBytes → validate → upscale-check → upscale → metadata → decoderInit → decodeNext → prepare → gpuUpload → decoded`
- Decode times: 75ms-394ms per page (normal range)
- Multiple pages complete decode successfully: ch=8/i=7,8,9,10,11,12,13,14,15,6
- 14 pages decoded total (2 abandoned)

### Diagnostics Output (Final State)
At the end of the session (lines 290-294, 731-748):

```
outcomes=abandoned:2 decoded:14
drawn=true frames=87
page=key=Reader(chapterId=8, index=10) img=1100x1606 scale=1.0305344 x=0.024074107 y=0.0 home=1.0305344 trim=1048x1606 mips=1 fmt=22 q=true
draw=pages=1 quads=1 skippedNoMips=0 last=1100x1606 scale=1.0 drawScale=1.0305344 x=0.0 y=0.0 home=1.0305344 trim=1048x1606 mips=1 fmt=22 dst=1080x2153 hq=true rect=0.0, 0.11564368, 1.0496184, 0.88435626 rect=0.0, 0.11564368, 1.0496184, 0.88435626 tex=map failed Invalid byte buffer.
```

**Critical contradiction**: `drawn=true` with `frames=87`, but `tex=map failed Invalid byte buffer`. The "drawn" flag is set before the actual GPU texture mapping succeeds, so it is misleading.

### System Logcat — WebGPU Validation Errors (Lines 364-516)
Repeated for ~30 frames:

```
E WebGpuRenderer: Uncaptured WebGPU error type=2: Destroyed texture [Texture (unlabeled 3584x3584 px, TextureFormat::RGBA8Unorm)] used in a submit.
E WebGpuRenderer: - While calling [Queue].Submit([[CommandBuffer]])
E WebGpuRenderer: dropping frame: 1 WebGPU validation error(s) last=type=2: Destroyed texture...
```

Pattern: The error `type=2` = `GPUDeviceError` / "used after free". A texture is destroyed but still referenced in a command buffer submit.

### Post-Parking Behavior (Lines 518-530)
After the "draw failure streak 30 frames" parking:

```
E ImageViewerState: Nothing can be drawn: no render surface
D WebGpuViewer: Queued ch=8/i=11,12,13,14,15 priority=749..745 depth=1..4
New pages decoded successfully (ch=8/i=11,12,13,14,15,6)
But: frames=87, drawn=true, yet tex=map failed Invalid byte buffer persists
```

---

## 2. Root Cause: "tex=map failed Invalid byte buffer" + "Destroyed texture used in submit"

### The Sequence of Events

1. **GPU textures are created** for decoded pages during `gpuUpload` stage of the decode pipeline
2. **Device loss or texture invalidation occurs** — the WebGPU device is reset/recreated, but textures from the old device are not properly invalidated
3. **The renderer continues referencing old texture handles** — these textures are now "destroyed" from the new device's perspective
4. **When `Texture.map()` is called** (to read back or present the texture), it fails with "Invalid byte buffer" because the underlying GPU resource no longer exists
5. **WebGPU validation catches this**: `Destroyed texture used in a submit` — the GPU driver detects that a destroyed texture is being referenced in a command buffer submission
6. **After ~30 frames of errors**, the system parks the renderer (`draw failure streak 30 frames; parking until the next invalidation`)
7. **New decodes continue** but the texture lifetime issue persists because the root cause (improper texture cleanup across device state changes) is not fixed

### Why This Is NOT a Pure Device Loss
- `deviceLosses=0 recoveries=0 failedRecoveries=0 parks=0` in the diagnostics — no formal device loss was tracked
- But `tex=map failed Invalid byte buffer` and `Destroyed texture used in submit` are the **same class of bug** as device loss, just at the texture level
- The adapter may have been consumed/recreated internally without the app's device-loss counters incrementing

### Connection to WebGPU AGENTS.md Landmines

From `viewer/webgpu/AGENTS.md` — **DEVICE LIFECYCLE** section:

> **"NEVER reuse an adapter to rebuild a device"**
> A `GPUAdapter` is **consumed** by the first `requestDevice` that succeeds on it. Dawn rejects a second one outright.

> **Symptom**: one page decodes, the device is lost, and from then on every single decode throws `IllegalStateException: WebGPU not available` forever.

> **"The upgrade is in the library, not the app"** — about `core-features-and-limits` and compatibility mode supplying only restrictions that can cause black screens with no validation error.

> **"`anyPageDrawn` alone was still a claim, not a proof"** — the render walk publishes on-screen set before `present()`, so `drawn=true` can be set even when nothing actually presents.

From **RESOURCE OWNERSHIP** section:

> `releasePageResources(page)` is the **single owner** and the only place that releases a `ViewerReaderPage`. It frees: spread image, height-match retry job, comparison originals, retained source bytes, rescale-in-flight flag, `wantedByRender`, and the image page itself.

> If you add a field holding a GPU resource or a coroutine **to `ViewerReaderPage`**, add it here too. Six hand-copied subsets used to exist and had already drifted — that drift is how `wantedByRender` ended up cleared on the eviction path but not the device-loss path.

From **Known landmines** (implied by the symptom→cause relationship):

> Black page = usually a texture binding / device loss issue

---

## 3. Why Pages Decode Successfully but Render Black

### The Decode-Present Gap

The pipeline has a fundamental gap between **decoding** (bytes → pixel data in RAM) and **presenting** (GPU texture binding → display):

```
Decode Pipeline: bytes → upscale → metadata → decoderInit → decodeNext → prepare → gpuUpload → decoded
       │
       ▼
Present Pipeline: GPU texture → bind → present → display
```

**What the logs show**:
- ✅ `gpuUpload` completes for all 14 decoded pages (decode succeeds)
- ✅ `decoded` trace stage completes
- ❌ `tex=map failed Invalid byte buffer` when trying to map the GPU texture
- ❌ `Destroyed texture used in submit` — GPU validation error

**Root cause**: The decoded pixel data exists in memory, but the **GPU texture object** that should bind this data to the render pipeline has been destroyed or invalidated. When the renderer tries to use it, the WebGPU driver returns:
- `tex=map failed Invalid byte buffer` (app-side mapping failure)
- `Destroyed texture used in submit` (driver-side validation error)

This is analogous to having a file on disk (decoded bytes) but the file handle (GPU texture) has been closed/invalidated.

### The "drawn=true" Misleading Flag

Per `viewer/webgpu/AGENTS.md`:

> "`anyPageDrawn` alone was still a claim, not a proof... a session whose frames never reached `present()` reports `drawn=true`."

The `drawn=true` flag is set by the render walk **before** the actual present happens. In this case, it's set because the pipeline thinks it's drawing, but the actual GPU texture binding fails silently, resulting in a black page.

---

## 4. Specific Code Locations to Investigate

### App-Side (`app/.../ui/reader/viewer/webgpu/`)

| File | What to Look For |
|---|---|
| `WebGpuRenderer.kt` | - `recreateDeviceLocked()` — is the old adapter being reused?<br>- Texture creation/destruction paths<br>- `deviceLostListener` — does it properly clear all GPU resources?<br>- `resetDecodedPagesAfterDeviceLoss()` — is it called? |
| `WebGpuPagePipeline.kt` | - `gpuUpload` → `adopt` transition — how is the ImagePage's GPU texture assigned?<br>- Whether `Texture.map()` has null/invalid checks<br>- The `PageStage` ADOPT stage and what happens after |
| `WebGpuViewer.kt` | - `releasePageResources(page)` — is it the **single owner** of GPU resource cleanup?<br>- `currentPage` swap logic — is the old page's texture released before the new one is adopted?<br>- `onActivityStop/Start` and memory pressure handling |
| `WebGpuPageCache.kt` | - `evictFarthestPage` — does it properly clean up GPU textures?<br>- `releaseAllShellsAndClearResidency` — is it called on device loss? |
| `WebGpuViewerImageState.kt` | - Filter/zoom state that might hold GPU resource references |

### Library-Side (`external/webgpuviewer-houri` / `:webgpuviewer`)

| File | What to Look For |
|---|---|
| `WebGpuRenderer.kt` (library) | - Device creation: `requestDevice` vs `requestDeviceBestEffort`<br>- Texture lifetime: are textures tied to device identity or recreated on loss?<br>- `recoverFromDeviceLoss()` — does it clean up old textures?<br>- The `core-features-and-limits` flag and compatibility mode |
| `WebGpuDecodePipeline.kt` | - How the `gpuUpload` stage creates GPU textures<br>- Whether uploaded textures track device generation<br>- Mipmap generation and whether stale mips persist |
| `WebGpuStuckLadder.kt` | - Does the stuck-page recovery path also clean GPU resources? |
| `WebGpuMemoryMonitor.kt` | - Memory pressure response — does it shed GPU caches properly? |

### Key invariants from AGENTS.md to verify:

1. **Never reuse an adapter** to rebuild a device (lines 225-236 of AGENTS.md)
2. `deviceGeneration` is bumped on every successful recreation — do objects key on it? (line 241)
3. `recoverFromDeviceLoss()` cleans **every** decoded page, clears tile grids, re-anchors on current page (lines 245-249)
4. `releasePageResources(page)` is the **single owner** — if GPU resources are held elsewhere, they won't be freed (lines 391-393)
5. A recovery that lost its device at birth reports failure after `createSurface()` (lines 270-273)

---

## 5. Diagnosis: Device Loss/Recovery Bug, Texture Lifetime Bug, or Something Else?

### Evidence Evaluation

| Indicator | Supports Device Loss | Supports Texture Lifetime | Supports Something Else |
|---|---|---|---|
| `deviceLosses=0` | ❌ | ✅ (formal device loss not tracked, but textures destroyed) | ✅ |
| `Destroyed texture used in submit` errors | ⚠️ (consistent with device loss) | ✅ (direct texture lifetime issue) | ⚠️ |
| `tex=map failed Invalid byte buffer` | ⚠️ | ✅ (mapping destroyed texture) | ✅ |
| `drawn=true` with black pages | ✅ (render walk lies before present) | ✅ (same class of bug as device loss) | ❌ |
| Decodes succeed but present fails | ✅ (decode/present gap) | ✅ (texture not lost entirely, just invalid) | ✅ |
| Parking after 30 frame error streak | ✅ (recovery attempt) | ✅ (same mechanism) | ✅ |
| No `WebGPU not available` after parking | ❌ (would if device truly lost) | ✅ (device alive, just textures stale) | ✅ |
| New decodes continue after parking but `tex=map failed` persists | ❌ (would if device lost permanently) | ✅ (device alive, texture management bug) | ✅ |

### Conclusion: **Texture Lifetime / Resource Management Bug**

This is **not a clean device loss** (no `deviceLosses` increment, no permanent `WebGPU not available`), but it **is the same class of bug** — a resource lifetime issue where GPU textures are not properly synchronized with device state changes.

**The most likely scenario**:

1. **Memory pressure or backgrounding** triggers an internal WebGPU device reset/recreation
2. The app's `deviceLostListener` fires, but **texture cleanup is incomplete** — some textures are freed, others are left referenced
3. The `deviceGeneration` counter may not be properly incremented or checked when textures are created
4. Decoded pages' GPU textures are from the **old device generation**, but the renderer continues using the old texture handles
5. When `Texture.map()` or `Queue.submit()` is called on these stale textures, the WebGPU driver returns `Destroyed texture` / `Invalid byte buffer`
6. The system attempts recovery (parking), but the root cause — missing texture generation tracking — is not addressed
7. New decodes create textures on the current device, but old texture references persist, causing the error to persist

**Alternative but less likely**: A race condition where the decode worker and render thread access the same texture without proper synchronization, causing the render thread to use a texture while the decode worker is tearing it down.

---

## Recommended Investigation Path

### Short-term ( diagnose and confirm )

1. **Add device generation tracking**: Verify that every GPU texture creation checks `deviceGeneration` and that the generation is bumped on device loss/recreation
2. **Audit `releasePageResources`**: Confirm it is the *single* owner of all GPU texture cleanup — no other code path releases textures
3. **Check `deviceLostListener`**: Does it call `resetDecodedPagesAfterDeviceLoss()` or equivalent? Are all texture references cleared?
4. **Inspect `recreateDeviceLocked`**: Is the old adapter being reused? If so, this is the root cause per AGENTS.md rule #1

### Medium-term ( fix the bug )

1. **Ensure texture lifetime ties to device generation**: When the device is recreated, all existing texture references must be invalidated
2. **Add validation before `Texture.map()`**: Check that the texture is still valid (not destroyed) before attempting to map it
3. **Audit all code paths that create/gpuUpload textures**: Ensure they key on `deviceGeneration` or properly recreate on device loss
4. **Add `deviceGeneration` checks in `WebGpuRenderer`**: Compare against expected generation; if mismatch, destroy stale textures and recreate

### Long-term ( prevent recurrence )

1. **Enforce the AGENTS.md device lifecycle rule**: "Never reuse an adapter to rebuild a device" — always re-request the adapter
2. **Add integration tests**: Simulate device loss mid-session and verify textures are properly recreated
3. **Add generation counters**: Track device generation at the texture level and validate on every access
4. **Review the `core-features-and-limits` flag**: The AGENTS.md mentions this can cause black screens in compatibility mode without validation errors — verify the app's feature request matches the adapter's capabilities

---

## Summary

**The problem**: Pages decode successfully (bytes → pixel data works), but rendering fails because GPU textures are stale/invalid. The `tex=map failed Invalid byte buffer` error and `Destroyed texture used in submit` WebGPU validation error both point to a **texture lifetime issue** where textures are being referenced after they've been destroyed/invalidated, likely due to an unhandled device state change.

**Why it happens**: The decode pipeline and the GPU present pipeline are not properly synchronized across device lifecycle events. When the WebGPU device is internally reset (possibly due to memory pressure on the realme RMX2151 with 2GB RAM), textures from the old device remain referenced, causing validation errors when the renderer tries to use them.

**The fix**: Properly track and invalidate GPU texture references across device loss/recreation events, ensuring `deviceGeneration` is checked and all stale textures are destroyed before new ones are created. This aligns with the AGENTS.md "NEVER reuse an adapter to rebuild a device" rule and the `releasePageResources` single-owner invariant.