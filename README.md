# GazeReel · 凝影

> 目光所及，皆是片库。

把手机塞进纸盒（Cardboard）就能看的 VR 播放器。不是电影点播应用——主要内容是**用户自己拍的片子**：
`VID_20260712_143022.mp4`、`GOPR0231.MP4` 这种无片名、同前缀成百上千、按文件夹和拍摄时间组织的自拍与相机片段，
混着 2D / 180 / 360 / SBS / TAB。

- 包名 `com.gazereel.vr`，Android 8.0+（minSdk 26）
- 片源：本地 / SMB2-3 / WebDAV / HTTP / DLNA
- 播放器：Media3 ExoPlayer + 自绘球面 / 半全景 / 2D 银幕网格

## 当前状态

**alpha，功能未完工，交互随时会改。** 正式完工之前只出 alpha。

进度与下一步看 [`CHECKLIST.md`](CHECKLIST.md)——那是唯一的推进依据，分「能用 / 好用 / 完整」三段。
设计推导在 `UI_VISUAL_DESIGN.html`，实施附录在 `UI_DESIGN_SPEC.html`。

## 安装

到 [Releases](../../releases) 下载 `GazeReel-<版本>.apk`，允许「安装未知来源应用」后直接安装。

alpha 阶段用固定签名（`keystore/gazereel-alpha.jks`），所以**装过旧 alpha 可以直接覆盖，不用卸载**——
卸载会丢掉片源配置、观看断点和格式记忆。

## 版本号

格式 `<major>.<minor>.<patch>-alpha<N>`，例如 `1.0.0-alpha1`。

| 段位 | 限制 | 说明 |
|---|---|---|
| `major` | 无上限 | 有重大更新时进位 |
| `minor` | **≤ 10** | 满 10 再升就进位到 `major` |
| `patch` | **≤ 10** | 满 10 再升就进位到 `minor` |
| `alpha` | 无上限 | 同一版本内继续迭代就 `+1` |

**合法**：`11.10.10-alpha11`　　**非法**：`1.11.11-alpha1`

段位越界不会「悄悄发出去」：`app/build.gradle.kts` 在配置阶段就会 `check()` 失败，
`tools/bump_version.py --check` 在 CI 里也会拦一道。

### 升版本

唯一真相源是根目录 `version.properties`。**不要手改那一行**，用脚本：

```bash
python3 tools/bump_version.py            # 看当前版本与 versionCode
python3 tools/bump_version.py alpha      # 1.0.0-alpha1 → 1.0.0-alpha2
python3 tools/bump_version.py patch      # 1.0.0-alpha3 → 1.0.1-alpha1
python3 tools/bump_version.py minor      # 1.0.10-alpha2 → 1.1.0-alpha1
python3 tools/bump_version.py major      # 1.10.10-alpha9 → 2.0.0-alpha1
```

`versionCode` 由 `major(1e8) | minor(1e6) | patch(1e4) | alpha` 拼成，
保证单调递增，且正式版（尾段 9000）永远大于自己的全部 alpha。

### 发版

推一个和版本号完全一致的 tag 就会自动构建并发布：

```bash
python3 tools/bump_version.py alpha
git add version.properties && git commit -m "chore: 版本升到 1.0.0-alpha2"
git tag v1.0.0-alpha2
git push && git push --tags
```

`.github/workflows/release.yml` 会先校验 `tag == v + version.properties`，不一致直接失败。

## 本地构建

```bash
# 需要 JDK 17 与 Android SDK（compileSdk 35）
python3 tools/check_structure.py          # 结构校验，秒级
./gradlew :app:assembleDebug              # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew -q :app:printVersion            # 打印当前版本号
```

Cardboard SDK 以本地 aar 形式随仓库提供（`app/libs/cardboard-sdk.aar`），CI 不需要额外下载。
不放这个 aar 也能编译，只是会退回纯传感器实现。

## 目录

```
app/src/main/java/com/gazereel/vr/
  core/      格式模型、动作、头显抽象
  data/      片源与索引（SMB / HTTP / 本地）、格式探测、数据库
  player/    Media3 封装、字幕
  render/    GL 渲染、UI 画布
  input/     凝视、触发键、头部手势
  ui/        状态机与 Activity
tools/       结构校验、版本号工具、布局对照图
keystore/    alpha 固定签名（正式发布前换成真正的 release keystore）
```
