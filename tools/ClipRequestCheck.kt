// 验收入口（按片名点名起播）的离线自检：只用 JVM，不需要设备。
//
// 为什么值得断言：这一层决定「脚本点的是哪一部片」。它错了不会崩，只会**悄悄播另一部**，
// 而验收人戴着头显、看不到文件名，会把 V4 的结论记到别的投影上——错账比没账更糟。
// 规则全是纯字符串，因此可以一条条钉死。
//
// 编的是 app 里那份真代码（core/ClipRequest.kt），不是副本。见 run-format-check.sh。
package check

import com.gazereel.vr.core.ClipRequest

private var failed = 0
private var passed = 0

private fun expect(name: String, got: String?, want: String?) {
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

private fun expect(name: String, got: Int, want: Int) {
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

private fun expect(name: String, got: Boolean, want: Boolean) {
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

fun main() {
    println("· normalize：路径、扩展名、大小写都归一；太短的一律不认")
    expect(
        "带目录与扩展名 → 片名",
        ClipRequest.normalize("/sdcard/Movies/test-360-equirect-mono.mp4"),
        "test-360-equirect-mono"
    )
    expect(
        "大写扩展名与小写片名",
        ClipRequest.normalize("Test-180-SBS.MP4"),
        "test-180-sbs"
    )
    expect(
        "Windows 路径分隔符",
        ClipRequest.normalize("C:\\clips\\foo.MP4"),
        "foo"
    )
    expect("没有扩展名也认", ClipRequest.normalize("noext"), "noext")
    expect("空白 → null", ClipRequest.normalize("   "), null)
    expect("null → null", ClipRequest.normalize(null), null)
    // 单字符会命中片库里几乎任何一部片：宁可「没命中」，也不能「大概就是这一部」
    expect("单字符 → null", ClipRequest.normalize("a"), null)
    expect("目录下的单字符 → null", ClipRequest.normalize("x/a"), null)

    println("· matches：分隔符与大小写都无关，不相干就必须不命中")
    expect(
        "片库标题带扩展名，查询不带",
        ClipRequest.matches("test-360-equirect-mono.mp4", "test-360-equirect-mono"),
        true
    )
    expect(
        "下划线查询命中空格标题",
        ClipRequest.matches("test-180 SBS.mp4", "test_180_sbs"),
        true
    )
    expect(
        "不相干的片子不命中",
        ClipRequest.matches("test-2d-grid-720p.mp4", "fisheye"),
        false
    )
    expect("空查询不命中", ClipRequest.matches("any.mp4", ""), false)
    expect(
        "标题部分命中即可（不要求整名）",
        ClipRequest.matches("test-fisheye-circle-mono.mp4", "fisheye-circle"),
        true
    )

    println("· pick：全等 > 前缀 > 包含，同档取靠前的")
    val twins = listOf("test-360-equirect-mono-4k.mp4", "test-360-equirect-mono.mp4")
    expect(
        "全等压过排在前面的前缀命中",
        ClipRequest.pick(twins, "test-360-equirect-mono"),
        1
    )
    val grid = listOf("test-2d-grid-720p.mp4", "test-360-equirect-mono.mp4")
    expect(
        "只有包含关系时按顺序",
        ClipRequest.pick(grid, "360"),
        1
    )
    expect(
        "查询带扩展名、大小写任意也能全等",
        ClipRequest.pick(twins, "TEST-360-EQUIRECT-MONO.MP4"),
        1
    )
    expect("没有候选 → -1", ClipRequest.pick(grid, "insv"), -1)
    expect("空片库 → -1", ClipRequest.pick(emptyList(), "360"), -1)
    expect("太短的查询 → -1", ClipRequest.pick(grid, "a"), -1)

    println("· item：项号只进日志，写错要立刻看出来")
    expect("v4 → V4", ClipRequest.item("v4"), "V4")
    expect("裸数字 → V7", ClipRequest.item(" 7 "), "V7")
    expect("两位项号", ClipRequest.item("V10"), "V10")
    expect("不是项号 → null", ClipRequest.item("abc"), null)
    expect("空 → null", ClipRequest.item(null), null)

    println("· describe：脚本读回执的那一行格式固定")
    expect(
        "项号与片名都归一",
        ClipRequest.describe("v4", "test-fisheye-circle-mono.mp4"),
        "item=V4 clip=test-fisheye-circle-mono"
    )
    expect("两个都缺", ClipRequest.describe(null, null), "item=?- clip=-")

    println()
    println("通过 $passed · 失败 $failed")
    if (failed > 0) throw AssertionError("$failed 条断言未通过")
}
