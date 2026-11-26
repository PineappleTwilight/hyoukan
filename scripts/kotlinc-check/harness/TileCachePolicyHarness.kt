// Runs the REAL tile-cache and mip policies, compiled straight from the library's pure
// renderer/TileCachePolicy.kt and renderer/ImageMipPolicy.kt.
//
// Two rules decide what a PowerVR reader looks like without the tile cache:
//  - the cache must be off there regardless of the advanced switch, while every other adapter
//    follows the switch;
//  - the direct path must have a mip level for the scale it draws at, or halftone screentones
//    moiré exactly where the cache used to cover them.

import ca.mpreg.webgpuviewer.renderer.ImageMipPolicy
import ca.mpreg.webgpuviewer.renderer.TileCachePolicy

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private const val POWERV = "vendor=img-tec arch=rogue device=PowerVR Rogue GE8322 desc=PowerVR Rogue Vulkan Driver"
private const val MALI = "vendor=arm arch=mali device=Mali-G76 desc=Mali-G76"
private const val ADRENO = "vendor=qualcomm arch=adreno device=Adreno (TM) 640"

fun main() {
    // --- the PowerVR override ---------------------------------------------------------------
    check("PowerVR cannot turn the cache on", !TileCachePolicy.isEnabled(true, POWERV))
    check("PowerVR is recognised by its vendor id", TileCachePolicy.isPowerVr(POWERV))
    check("PowerVR is recognised case-insensitively", TileCachePolicy.isPowerVr(POWERV.uppercase()))
    check(
        "a plain Imagination description is PowerVR",
        TileCachePolicy.isPowerVr("vendor=imagination technologies"),
    )
    check(
        "a description with only the rogue architecture is PowerVR",
        TileCachePolicy.isPowerVr("arch=rogue"),
    )

    // --- the host preference on every other adapter -----------------------------------------
    check("the advanced switch can turn the cache off on Mali", !TileCachePolicy.isEnabled(false, MALI))
    check("Mali keeps the cache when the switch is on", TileCachePolicy.isEnabled(true, MALI))
    check("Adreno keeps the cache when the switch is on", TileCachePolicy.isEnabled(true, ADRENO))
    check("an unknown adapter follows the switch", TileCachePolicy.isEnabled(true, "unknown"))
    check("a non-PowerVR description is not mistaken for one", !TileCachePolicy.isPowerVr(MALI))

    // --- mip chain sizing --------------------------------------------------------------------
    check("a 960x1376 page builds a level", ImageMipPolicy.shouldBuildNextLevel(960, 1376))
    check("a 2048x2048 page builds a level", ImageMipPolicy.shouldBuildNextLevel(2048, 2048))
    check("an 8x8 page stops at the floor", !ImageMipPolicy.shouldBuildNextLevel(8, 8))
    check("a 15x21 page stops before a sub-8 level", !ImageMipPolicy.shouldBuildNextLevel(15, 21))
    check("a 16x16 page is the last allowed level", ImageMipPolicy.shouldBuildNextLevel(16, 16))
    check("a wide strip still stops on its short side", !ImageMipPolicy.shouldBuildNextLevel(1200, 7))

    // --- mip level selection -----------------------------------------------------------------
    // The levels a 960x1376 page gets: 960, 480, 240, 120, 60, 30, 15.
    val levels = 7
    check("1.0x samples level 0", ImageMipPolicy.levelForScale(1f, levels) == 0)
    check("0.9x still samples level 0", ImageMipPolicy.levelForScale(0.9f, levels) == 0)
    check("0.6x samples level 1 - the moiré case", ImageMipPolicy.levelForScale(0.6f, levels) == 1)
    check("0.5x samples level 1", ImageMipPolicy.levelForScale(0.5f, levels) == 1)
    check("0.3x samples level 2", ImageMipPolicy.levelForScale(0.3f, levels) == 2)
    check("0.1x samples level 3", ImageMipPolicy.levelForScale(0.1f, levels) == 3)
    check("a single-level image always samples level 0", ImageMipPolicy.levelForScale(0.2f, 1) == 0)
    check("a zero scale cannot index below zero", ImageMipPolicy.levelForScale(0f, levels) == 0)
    check("a non-finite scale cannot index below zero", ImageMipPolicy.levelForScale(Float.NaN, levels) == 0)
    check("the selection never leaves the chain", ImageMipPolicy.levelForScale(0.001f, levels) in 0 until levels)

    if (failures > 0) {
        println("FAILURES: $failures")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
