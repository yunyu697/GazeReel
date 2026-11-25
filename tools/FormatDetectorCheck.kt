// 格式判定的离线自检：只用 JVM，不需要设备。
//
// 为什么要有它：这一层被「时对时错」坑过一次，代价是每次打开都正对接缝。
// 现在的规则是确定性的，所以结论也必须是可以拿断言钉死的：
// 同样的文件名 + 同样的宽高 + 同样的元数据，永远得到同一个格式。
//
// 编的是 app 里那份真代码（core/FormatDetector.kt、core/VrFormat.kt、
// core/SphericalMetadata.kt），不是副本。见 run-format-check.sh。
package check

import com.gazereel.vr.core.FieldOfView
import com.gazereel.vr.core.FormatDetector
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.SphericalMetadata
import com.gazereel.vr.core.StereoLayout
import com.gazereel.vr.core.VrFormat

private var failed = 0
private var passed = 0

private fun short(f: VrFormat): String =
    "${f.projection.name}/${f.layout.name}/${f.fov.name}"

private fun expect(name: String, got: String, want: String) {
    if (got == want) {
        passed++
        println("  ok   $name")
    } else {
        failed++
        println("  FAIL $name")
        println("       期望 $want")
        println("       实际 $got")
    }
}

private fun probe(
    w: Int, h: Int,
    layout: StereoLayout? = null,
    spherical: Boolean = false,
    fov: FieldOfView? = null
) = SphericalMetadata.Probe(
    width = w, height = h, layout = layout, spherical = spherical, fov = fov, source = "test"
)

fun main() {
    val d = FormatDetector()

    println("[1] 2:1 裸流：几何二义 → 取立体（180 SBS），不能再判 360 Mono")
    run {
        val r = d.detect("KAVR-332-CD1.mp4", 4096, 2048)
        // 这一条是这次事故的原点：判 MONO 就等于把整幅铺满球面、正前方是接缝。
        expect("4096x2048 无标记", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
        expect("4096x2048 不弹确认", r.needsConfirmation.toString(), "false")
        expect("4096x2048 证据来源", r.source, "几何二义-取SBS")
    }
    run {
        val r = d.detect("KAVR-332-CD1.mp4", 3840, 1920)
        expect("3840x1920 无标记", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
    }

    println("[2] 文件名优先级最高：名字说什么就是什么")
    run {
        val r = d.detect("KAVR-332-CD1_VR180_SBS_LR.mp4", 4096, 2048)
        expect("名字 180 SBS", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
        expect("名字 180 SBS 来源", r.source, "文件名视场")
    }
    run {
        // 名字说 360：即便 4096x2048 在几何上也能解释成 180 SBS，也按 360 走，
        // 且布局必须跟着变成 MONO —— 2:1 的 360 只可能是 Mono。
        val r = d.detect("家庭全景_360.mp4", 4096, 2048)
        expect("名字 360 + 2:1", short(r.format), "SPHERE_360/MONO/FOV_360")
        expect("名字 360 不做二义猜测", r.source, "文件名视场")
    }
    run {
        val r = d.detect("demo_3dh_LR.mp4", 4096, 2048)
        expect("名字 3dh+LR", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
    }
    run {
        val r = d.detect("show_180_TB.mp4", 3840, 7680)
        expect("名字 180 TAB", short(r.format), "HALF_SPHERE_180/TAB/FOV_180")
    }
    run {
        val r = d.detect("vr180_test.mp4", 3840, 1920)
        expect("vr180 连写", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
    }

    println("[3] 元数据次之：st3d / 裁剪比说了算")
    run {
        val r = d.detect("KAVR-332-CD1.mp4", 4096, 2048, probe(4096, 2048, StereoLayout.SBS))
        expect("st3d=SBS", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
        expect("st3d 来源", r.source, "容器 st3d")
    }
    run {
        // st3d 明确写 mono：不能被「二义取立体」翻掉。
        val r = d.detect("KAVR-332-CD1.mp4", 4096, 2048, probe(4096, 2048, StereoLayout.MONO))
        expect("st3d=MONO", short(r.format), "SPHERE_360/MONO/FOV_360")
        expect("st3d=MONO 来源", r.source, "容器 st3d")
    }
    run {
        val r = d.detect("clip.mp4", 5760, 2880, probe(5760, 2880, spherical = true, fov = FieldOfView.FOV_180))
        expect("XMP 裁剪比 180", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
    }

    println("[4] 360 SBS 用 4:1，几何唯一解")
    run {
        val r = d.detect("unknown.mp4", 7680, 1920)
        expect("7680x1920 无标记", short(r.format), "SPHERE_360/SBS/FOV_360")
        expect("7680x1920 唯一解", r.source, "几何唯一")
    }

    println("[5] 180 Mono 用 1:1，不要切开")
    run {
        val r = d.detect("flat180_mono.mp4", 3840, 3840)
        expect("3840x3840 名字 mono", short(r.format), "HALF_SPHERE_180/MONO/FOV_180")
    }
    run {
        val r = d.detect("nv_1to1.mp4", 3840, 3840)
        expect("3840x3840 无标记", short(r.format), "HALF_SPHERE_180/MONO/FOV_180")
    }

    println("[6] 平面视频仍走 2D 银幕，并保留一次确认")
    run {
        val r = d.detect("家庭录像.mp4", 1920, 1080)
        expect("1920x1080", short(r.format), "PLANE_2D/MONO/FLAT")
        expect("1920x1080 要确认", r.needsConfirmation.toString(), "true")
    }
    run {
        val r = d.detect("movie_2d_screen.mkv", 1920, 1080)
        expect("名字 2D", short(r.format), "PLANE_2D/MONO/FLAT")
        expect("名字 2D 不问", r.needsConfirmation.toString(), "false")
    }

    println("[7] 文件名解析：数字边界，别把 1080p 当成 180")
    run {
        val m = FormatDetector.Markers.of("holiday_1080p_x264.mp4")
        expect("1080p 不是 180", m.is180.toString(), "false")
        expect("1080p 不是立体", m.layout.name, "MONO")
    }
    run {
        val m = FormatDetector.Markers.of("baodoinikvr_scene.mp4")
        expect("品牌名里的 vr 不算标记", m.isVr.toString(), "false")
    }
    run {
        val m = FormatDetector.Markers.of("[180][3dh][LR] sample.mp4")
        expect("[180][3dh][LR] 认出 180", m.is180.toString(), "true")
        expect("[180][3dh][LR] 认出 SBS", m.sbs.toString(), "true")
    }

    println("[8] 确定性：同一输入连判 200 次，结论必须完全一致")
    run {
        var base: String? = null
        var diff = 0
        repeat(200) {
            val s = d.detect("KAVR-332-CD1.mp4", 4096, 2048).format
            if (base == null) base = short(s) else if (base != short(s)) diff++
        }
        expect("200 次同一结论", "$diff 次不一致", "0 次不一致")
    }

    println("[9] 19:2x：未缝合双鱼眼 360 明确拒绝，不能当单镜头鱼眼播")
    run {
        // `.insv` 是 Insta360 的机内原始片：两个圆 = 同一只眼的前后两支镜头。
        val r = d.detect("VID_20260712_160512.insv", 2880, 2880)
        expect("insv 被拒绝", (r.refusal != null).toString(), "true")
        expect("insv 拒绝原因", r.refusal?.key ?: "-", "dual_fisheye_360")
        // 拒绝不等于不给看：降级成 2D 银幕原样显示，且布局一定是 MONO
        expect("insv 降级 2D", short(r.format), "PLANE_2D/MONO/FLAT")
        expect("insv 不再弹确认", r.needsConfirmation.toString(), "false")
    }
    run {
        val r = d.detect("MKX200_dual_fisheye.mp4", 3840, 1920)
        expect("dual fisheye 被拒绝", r.refusal?.key ?: "-", "dual_fisheye_360")
    }
    run {
        // 单镜头鱼眼（VR180 相机 / 圆图）不能被拒绝规则误伤。
        val r = d.detect("MKX200_fisheye_sbs.mp4", 3840, 1920)
        expect("单镜头鱼眼不拒绝", (r.refusal == null).toString(), "true")
        expect("单镜头鱼眼仍是鱼眼", short(r.format), "FISHEYE/SBS/FOV_FISHEYE")
    }
    run {
        // 只认 `dual` 一个词会把普通片误杀：`Dual Audio` 是音轨，不是镜头。
        val r = d.detect("Dual Audio Movie 1080p.mp4", 1920, 1080)
        expect("Dual Audio 不拒绝", (r.refusal == null).toString(), "true")
        expect("Dual Audio 仍是 2D", r.format.projection.name, "PLANE_2D")
    }
    run {
        val m = FormatDetector.Markers.of("Gear360_raw.mp4")
        expect("Gear360 原始片认双鱼眼", m.isDualFish.toString(), "true")
        val n = FormatDetector.Markers.of("vrca220_fisheye.mp4")
        expect("VRCA 圆图不是双鱼眼", n.isDualFish.toString(), "false")
        val c = FormatDetector.Markers.of("双鱼眼测试.mp4")
        expect("中文「双鱼眼」也认", c.isDualFish.toString(), "true")
    }

    println("[10] 19:3x：真实相机命名语料（品牌名 ≠ 未缝合）")
    run {
        // Insta360 机内原片：后缀 .insv 本身就是「没缝」，最硬的证据。
        val r = d.detect("VID_20260712_160512_00_001.insv")
        expect("Insta360 原片被拒", r.refusal?.key ?: "-", "dual_fisheye_360")
    }
    run {
        // 关键回归：Insta360 Studio 导出已缝合的 360，文件名照样常带 INSTA360。
        // 一律拒绝等于把用户导出好的成品片挡在门外。
        val r = d.detect("INSTA360_X3_360_export.mp4", 5760, 2880)
        expect("Insta360 已缝合导出不拒", (r.refusal == null).toString(), "true")
        expect("Insta360 已缝合导出是球面", r.format.projection.name, "SPHERE_360")
    }
    run {
        // 同一品牌，但名字里没有任何「已缝合」标记 → 只能当机内原片拒。
        val r = d.detect("INSTA360_myvideo.mp4", 3840, 1920)
        expect("INSTA360 无 360 标记仍被拒", r.refusal?.key ?: "-", "dual_fisheye_360")
    }
    run {
        // 容器自己声明是球面时，品牌名不该覆盖容器证据。
        val r = d.detect("INSTA360_myvideo.mp4", 3840, 1920, probe(3840, 1920, spherical = true))
        expect("容器说球面时不拒品牌名", (r.refusal == null).toString(), "true")
        expect("容器说球面时按球面播", r.format.projection.name, "SPHERE_360")
    }
    run {
        // GoPro Max 的 .360 是**已缝合**的等距柱状，扩展名里的 360 就是证据。
        val r = d.detect("GX010001.360", 5376, 2688)
        expect("GoPro .360 认 360", r.format.projection.name, "SPHERE_360")
        expect("GoPro .360 不拒", (r.refusal == null).toString(), "true")
    }
    run {
        val r = d.detect("MKX200_fisheye_sbs.mp4", 3840, 1920)
        expect("MKX 圆图仍认鱼眼", short(r.format), "FISHEYE/SBS/FOV_FISHEYE")
    }
    run {
        // VRCA220 里的 220 是镜头型号，不是等距柱状 220° 张角。
        val r = d.detect("VRCA220_fisheye.mp4", 3840, 1920)
        expect("VRCA 的 220 不当张角", r.format.fov.name, "FOV_FISHEYE")
    }
    run {
        // rf52 分不出「机身原片（双圆）」还是「导出成品（单眼圆图）」→ 必须问一次。
        val r = d.detect("rf52_export_sbs.mp4", 3840, 1920)
        expect("rf52 给鱼眼", r.format.projection.name, "FISHEYE")
        expect("rf52 要求确认", r.needsConfirmation.toString(), "true")
    }
    run {
        val r = d.detect("全景视频_测试.mp4", 5760, 2880)
        expect("中文「全景」认 360", r.format.projection.name, "SPHERE_360")
    }
    run {
        val r = d.detect("VR180_test_sbs.mp4", 3840, 1920)
        expect("VR180 认 180 SBS", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
    }
    run {
        // 陷阱：1800p 里的 180 不能被当成 180°，否则整幅按半球贴。
        val r = d.detect("1800p_4k.mp4", 1920, 1080)
        expect("1800p 不认作 180°", r.format.projection.name, "PLANE_2D")
        expect("1800p 要问一次", r.needsConfirmation.toString(), "true")
    }
    run {
        // 陷阱：QooCam 原片命名无任何提示，只能落几何 + 确认，不能猜成鱼眼。
        val r = d.detect("QooCam_QCM_001.mp4", 3840, 2160)
        expect("QooCam 无提示不猜鱼眼", r.format.projection.name, "PLANE_2D")
        expect("QooCam 要问一次", r.needsConfirmation.toString(), "true")
    }
    run {
        val r = d.detect("equirect_360_mono.mp4", 5760, 2880)
        expect("equirect 360 mono 不切幅", r.format.layout.name, "MONO")
    }

    println("[11] 11:3x：离线测试片源（tools/make_testclips.py）必须落到它宣称的格式")
    // 这段不是重复覆盖：[10] 用的是「相机怎么命名」，这里用的是**我们真的会放到手机上
    // 那六段片子的文件名 + 真实宽高**。片源生成了、识别却判成别的格式，
    // 验收人会以为「播放器坏了」，其实是指针错了。两边必须钉在同一张表上。
    run {
        val r = d.detect("test-360-equirect-mono.mp4", 2048, 1024)
        expect("片源 360 → 整球不切幅", short(r.format), "SPHERE_360/MONO/FOV_360")
    }
    run {
        val r = d.detect("test-180-sbs.mp4", 2048, 1024)
        expect("片源 180 → 半球 SBS", short(r.format), "HALF_SPHERE_180/SBS/FOV_180")
    }
    run {
        val r = d.detect("test-2d-grid-720p.mp4", 1280, 720)
        expect("片源 2D → 银幕", short(r.format), "PLANE_2D/MONO/FLAT")
    }
    run {
        val r = d.detect("test-fisheye-circle-mono.mp4", 1024, 1024)
        expect("片源鱼眼圆 → 鱼眼不切幅", short(r.format), "FISHEYE/MONO/FOV_FISHEYE")
    }
    run {
        val r = d.detect("test-fisheye-fullframe-mono.mp4", 1280, 720)
        expect("片源鱼眼全画幅 → 鱼眼", short(r.format), "FISHEYE/MONO/FOV_FISHEYE")
    }
    run {
        val r = d.detect("test-dual-fisheye-unstitched.mp4", 2048, 1024)
        expect("片源双鱼眼 → 明确拒绝", r.refusal?.name ?: "null", "DUAL_FISHEYE_360")
        expect("片源双鱼眼 → 降级 2D", r.format.projection.name, "PLANE_2D")
    }

    println()
    println("通过 $passed · 失败 $failed")
    if (failed > 0) throw AssertionError("$failed 条断言未通过")
}
