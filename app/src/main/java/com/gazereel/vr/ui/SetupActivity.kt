package com.gazereel.vr.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.gazereel.vr.R
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.data.BrowseState
import com.gazereel.vr.data.CrashLog
import com.gazereel.vr.data.CredentialStore
import com.gazereel.vr.data.DlnaScanner
import com.gazereel.vr.data.FolderBrowser
import com.gazereel.vr.data.LanScan
import com.gazereel.vr.data.LanSmbScanner
import com.gazereel.vr.data.MediaIndexer
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.SmbCredentials
import com.gazereel.vr.data.SmbSessionPool
import com.gazereel.vr.data.SourceAddress
import com.gazereel.vr.data.parseUncFull
import com.gazereel.vr.data.db.AppDatabase
import com.gazereel.vr.data.db.SourceEntity
import com.gazereel.vr.data.decodeCredentials
import com.gazereel.vr.data.encodeCredentials
import com.gazereel.vr.databinding.ActivitySetupBinding
import com.gazereel.vr.databinding.DialogAddSourceBinding
import com.gazereel.vr.databinding.DialogEditSourceBinding
import com.gazereel.vr.databinding.DialogFolderBinding
import com.gazereel.vr.databinding.DialogSettingsBinding
import com.gazereel.vr.databinding.ItemFolderBinding
import com.gazereel.vr.databinding.ItemSourceBinding
import com.gazereel.vr.input.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 戴镜前的唯一入口。
 *
 * V2.7 的判断：产品是给人用的，不是把协议矩阵摊开给用户做选择题。
 * 第一屏只留两个动作——「手机里的视频」「同一 Wi-Fi 下的设备」，都不需要输入：
 * 前者走系统视频索引（一次授权，点一下收进来），后者自动发现局域网里的媒体服务器。
 * 只有自动发现不到时，才展开「手动填地址」作为兜底；协议头、斜杠、共享名、
 * 文件夹名一律由程序补齐或由用户点选，不让用户回忆语法。
 *
 * 已添加的片库可以改名、换账号、换文件夹、重扫、删除，不需要为了改一个目录重加一遍。
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        /** 通知权限只主动问一次，拒绝后不再每次冷启动都弹。 */
        private const val KEY_NOTIF_ASKED = "notif_permission_asked"
    }

    private enum class Status { IDLE, BUSY, OK, ERR }

    private lateinit var binding: ActivitySetupBinding
    private val pool = SmbSessionPool()
    private lateinit var credentials: CredentialStore
    private lateinit var indexer: MediaIndexer
    private lateinit var browser: FolderBrowser
    private lateinit var database: AppDatabase

    /** 当前片库列表的镜像，用来判断「手机里的视频」是不是已经加过。 */
    private var currentSources: List<SourceEntity> = emptyList()

    /** 用户拒绝过一次之后，再点就直接把他送到系统设置，而不是反复弹同一个框。 */
    private var permissionDenied = false

    /** V2.18：横竖屏切换会换掉整套 view，状态条的内容要能重画回去。 */
    private var lastStatus: StatusLine? = null

    /**
     * V2.45：加片库表单搬到独立对话框后，activity 布局里不再有 shareType / shareAddress…
     * 这里持有当前那一层视图；旋转时先记下内容、关掉、重绑之后再原样开回来。
     */
    private var addBinding: DialogAddSourceBinding? = null
    private var addDialog: AlertDialog? = null

    /** V2.45：配置页显示「扫到几个视频」，不是含糊的「几个片源」。 */
    private var mediaCounts: Map<Long, Int> = emptyMap()

    /**
     * V2.45：对话框关掉之后 saveNew 才真正入库，那时 addBinding 已为 null，
     * 所以「不扫描子文件夹」要在点连接的当下就记下来，不能事后再去读视图。
     */
    private var pendingRecursive = true

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            addLocalVideos()
        } else {
            permissionDenied = true
            // V2.46 / P0：原来这一句只有 4 个参数，action 为 null，
            // applyStatus 会把状态条的点击监听置空；可 strings.xml 偏偏写着
            // 「点这里重新授权」——文案承诺了一个根本不能点的控件。
            renderStatus(
                Status.ERR,
                getString(R.string.setup_permission_title),
                getString(R.string.setup_permission_detail),
                getString(R.string.setup_permission_action),
                action = { requestMediaPermission() }
            )
        }
    }

    /** V2.46 / P0：通知权限在戴镜前申请，不再塞进沉浸全屏的 VrActivity。 */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        RunLog.append("perm", "通知权限 ${if (granted) "已授权" else "被拒绝"}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.get(this)
        credentials = CredentialStore(this)
        indexer = MediaIndexer(this, database, pool, credentials)
        browser = FolderBrowser(indexer, pool)

        bindViews()

        renderStatus(
            Status.IDLE,
            "点一个就行，不用填东西",
            "手机里的视频，或者同一 Wi-Fi 下的设备。",
            "未添加"
        )
        showLastCrash()
        lifecycleScope.launch {
            database.sourceDao().observeAll().collect { renderSources(it) }
        }
        // V2.45：视频数独立一条流，扫描 / 重扫后立刻反映到片库标题与每张卡片，
        // 不用用户手动刷新，也不用把整张 media_item 表读进内存。
        lifecycleScope.launch {
            database.mediaDao().observeCountsBySource().collect { rows ->
                mediaCounts = rows.associate { it.sourceId to it.videoCount }
                renderSources(currentSources)
            }
        }
        refreshStalePosters()
        // V2.46 / P0：Android 13+ 的通知权限必须在戴镜前问完。
        // 原来放在 VrActivity.boot()，那时手机已在纸盒里、屏幕是沉浸全屏。
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // V2.46 / P0：从系统设置里打开权限再返回，界面要立刻自己恢复，
        // 不能让用户「再点一下卡片」才知道授权生效了。
        if (permissionDenied && hasMediaPermission()) {
            permissionDenied = false
            addLocalVideos()
        }
        renderSources(currentSources)
    }

    /**
     * V2.19：封面换代后必须主动补一次，不能等用户重扫。
     *
     * 真机反馈「封面没换」。查下来：`PosterRepository` 换了生成规则（文件名带 `g2`），
     * 旧路径自然全部失效，但**重生成只挂在 `scan()` 后面**——用户不重新扫描，
     * 库里那批「一个 V」的旧封面就永远躺在手机上。
     *
     * 这里在配置页启动时补一次：串行、跑在 IO、只处理「路径不是当前代号」的条目，
     * 已经换好的下次直接跳过。进 VR 时 Activity 暂停，协程随 lifecycleScope 取消，
     * 不会在戴镜后继续抽帧抢 CPU。
     */
    private fun refreshStalePosters() {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { database.sourceDao().all() }
            if (sources.isEmpty()) return@launch
            RunLog.append("poster", "开始补封面 · ${sources.size} 个片源")
            var total = 0
            for (source in sources) {
                // 点「进入 VR」之后立刻停手：抽帧是重活，不能和播放抢 CPU。
                total += runCatching {
                    withContext(Dispatchers.IO) {
                        indexer.refreshPosters(source.id) {
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        }
                    }
                }.getOrDefault(0)
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
            }
            RunLog.append("poster", "补封面完成 · 共 $total 张")
        }
    }

    override fun onDestroy() {
        // V2.18：退出配置页也把会话还回去，不然它和 VR 各留一条。
        // V2.28：改到后台，close() 现在与 SMB 调用共用一把锁。
        // 先取局部引用，别让这个短线程一直抓着 Activity。
        val closing = pool
        Thread { runCatching { closing.close() } }.start()
        super.onDestroy()
    }

    /**
     * V2.18：横屏真的重排，而不是被拉伸。
     *
     * 上一版在 manifest 里加了 `configChanges="orientation|screenSize"`，本意是
     * 「旋转时不重建，正在填的表单别丢」。但 `setContentView` 只在 onCreate 里调过一次，
     * 旋转时 Activity 不重建 → **仍是竖屏那套 layout 被横向拉宽**，用户看到的就是
     * 「完全没有重排，就只是简单拉伸了一下」。
     *
     * 正确做法：方向变化时自己重新 inflate 一套（横屏会用 layout-land），
     * 再把视图绑一遍、按当前数据重画一次。表单里的文字按 id 搬过去，不丢输入。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // V2.45：手动表单现在是一层独立对话框。旋转时先记下内容、关掉，
        // 重绑之后再原样开回来；否则旧视图会挂在被替换掉的 activity 布局上。
        val keptForm = if (addDialog?.isShowing == true) captureAddFormState() else null
        dismissAddDialog()

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindViews()

        // 状态条也要重画：它不在 renderSources 里，漏了就是一片空白。
        lastStatus?.let { applyStatus(it) }
        renderSources(currentSources)
        keptForm?.let { showAddSourceDialog(it) }
    }

    /**
     * 视图绑定集中在这里，因为 [onConfigurationChanged] 换了一整套 view 之后
     * 必须重新绑一遍——漏掉任何一个，横屏下那个按钮就是死的。
     */
    private fun bindViews() {
        // 两个大入口：都不需要用户输入任何东西
        binding.localCard.setOnClickListener { addLocalVideos() }
        binding.lanCard.setOnClickListener { discoverLan() }
        // V2.45：两处「添加」开的是同一个对话框，不再滚页面展开折叠区。
        binding.manualToggle.setOnClickListener { showAddSourceDialog() }
        binding.sourceAdd.setOnClickListener { showAddSourceDialog() }
        binding.enterVrButton.setOnClickListener {
            // V2.46 / P0：空片库不能带着用户进 VR。原来按钮永远可用，
            // 戴镜后先见「戴稳了？」遮罩、再被叫回配置页，白折腾一趟。
            if (currentSources.isEmpty()) {
                renderStatus(
                    Status.ERR,
                    "先加一个片库",
                    "点上面的「手机里的视频」或「同一 Wi-Fi 下的设备」，加好之后再把手机放进纸盒。",
                    "未添加"
                )
                return@setOnClickListener
            }
            startActivity(Intent(this, VrActivity::class.java))
        }
        // V2.7.4 测试版：一页能截图的运行日志，不用连电脑也能反馈问题。
        binding.logEntry.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        // V2.34 / B13 补齐：戴镜前的设置。瞳距是戴镜前就该定好的参数，
        // V2.45 起入口挪到顶栏 48dp 图标，不再藏在底部主按钮下面。
        binding.settingsEntry.setOnClickListener { showSettings() }
    }

    /**
     * V2.7：上次进 VR 失败过，就把原因直接摆回第一屏。
     * 用户不需要连电脑、不需要懂 adb，点一下就能把内容发出来。
     * 如果是初始化把进程带崩的（native 崩溃接不住），这里还会告诉他
     * 已经自动切到兼容模式，以及怎么恢复。
     */
    private fun showLastCrash() {
        val blocked = CrashLog.isCardboardBlocked(this)
        val raw = CrashLog.read(this)

        if (raw != null) {
            val head = CrashLog.headline(raw) ?: "原因没记下来"
            // V2.45：状态条的点击动作记进 StatusLine。横竖屏换整套 view 之后
            // applyStatus 会重新挂上，不会出现「转一下横屏详情就点不开了」。
            val showDetail: () -> Unit = {
                AlertDialog.Builder(this, R.style.SetupDialog)
                    .setTitle("上次进入 VR 的失败记录")
                    .setMessage(raw.take(4000))
                    .setNegativeButton("知道了", null)
                    .setNeutralButton("清空并重试官方头追") { _, _ ->
                        CrashLog.clear(this)
                        CrashLog.unblockCardboard(this)
                        CrashLog.clearBootMarker(this)
                        renderStatus(
                            Status.IDLE,
                            "点一个就行，不用填东西",
                            "手机里的视频，或者同一 Wi-Fi 下的设备。",
                            "未添加"
                        )
                    }
                    .show()
            }
            renderStatus(Status.ERR, "上次进 VR 没成功", head, "看详情", showDetail)
            return
        }

        if (blocked) {
            val restore: () -> Unit = {
                AlertDialog.Builder(this, R.style.SetupDialog)
                    .setTitle("恢复官方头追？")
                    .setMessage("恢复后如果还是进不去，会自动再退回兼容模式。")
                    .setNegativeButton("先这样", null)
                    .setPositiveButton("恢复") { _, _ ->
                        CrashLog.unblockCardboard(this)
                        renderStatus(
                            Status.IDLE,
                            "点一个就行，不用填东西",
                            "手机里的视频，或者同一 Wi-Fi 下的设备。",
                            "未添加"
                        )
                    }
                    .show()
            }
            renderStatus(
                Status.OK,
                "已切到兼容模式",
                "官方头追上次没能起来，现在用手机传感器，一样能看。想恢复点这里。",
                "兼容模式",
                restore
            )
        }
    }

    // ------------------------------------------------------------ 权限

    private fun mediaPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermissionName()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * V2.46 / P0：读视频权限的唯一入口。
     *
     * 原来点卡片直接弹系统框、一个字解释都没有；拒绝后「再点一次」是静默跳到
     * 系统设置页，用户不知道该打开哪一项。现在区分三种情况：
     * 首次 = 先说用途再请求；拒绝过 = 给「去授权 / 稍后」对话框；
     * 勾了「不再询问」= 明说要去系统设置，并直接送到应用详情页。
     */
    private fun requestMediaPermission() {
        val name = mediaPermissionName()
        if (ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            addLocalVideos()
            return
        }
        val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, name)
        val goToSettings = permissionDenied && !rationale
        if (!rationale && !permissionDenied) {
            mediaPermission.launch(name)
            return
        }
        AlertDialog.Builder(this, R.style.SetupDialog)
            .setTitle(R.string.setup_permission_title)
            .setMessage(
                getString(
                    if (goToSettings) R.string.setup_permission_settings
                    else R.string.setup_permission_rationale
                )
            )
            .setNegativeButton(R.string.setup_permission_later, null)
            .setPositiveButton(R.string.setup_permission_action) { _, _ ->
                if (goToSettings) openAppSettings() else mediaPermission.launch(name)
            }
            .show()
    }

    /** V2.46 / P0：戴镜前问通知权限，只问一次，拒绝也不再反复打扰。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("gazereel", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        AlertDialog.Builder(this, R.style.SetupDialog)
            .setTitle(R.string.setup_notify_title)
            .setMessage(R.string.setup_notify_rationale)
            .setNegativeButton(R.string.setup_notify_later, null)
            .setPositiveButton(R.string.setup_notify_ok) { _, _ ->
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    // -------------------------------------------------- 一键：手机里的视频

    /**
     * 零输入入口。已经加过就只重扫，不会在片库里堆出第二个「手机里的视频」。
     */
    private fun addLocalVideos() {
        if (!hasMediaPermission()) {
            requestMediaPermission()
            return
        }
        val existing = currentSources.firstOrNull { it.type == SourceType.LOCAL.name }
        if (existing != null) {
            rescan(existing)
            return
        }
        setBusy(true, "正在读取手机里的视频…")
        binding.localChip.text = "读取中"
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val entity = SourceEntity(
                    type = SourceType.LOCAL.name,
                    address = "",
                    displayName = getString(R.string.setup_local_title)
                )
                val id = database.sourceDao().upsert(entity)
                runCatching { indexer.scan(entity.copy(id = id)) }.getOrElse { error ->
                    // V2.18：失败原因必须落到日志里，否则用户只能看到「扫描没成功」。
                    RunLog.append("scan", "扫描失败 · ${entity.address} · ${error.message}")
                    -1
                }
            }
            setBusy(false)
            // V2.46 / P0：不再硬写回「点一下加入」。片源 upsert 会触发
            // observeAll() → renderSources()，chip 只有一个真相源；
            // 原来这行会把它改回未添加，转一下横屏又变回「已加入」。
            when {
                count < 0 -> renderStatus(
                    Status.ERR,
                    "没能读取手机里的视频",
                    "片库已保存。点这一行的「重扫」再试一次；运行日志里有具体原因。",
                    "需重扫"
                )
                count == 0 -> renderStatus(
                    Status.OK,
                    "这台手机上还没有视频",
                    getString(R.string.setup_local_empty),
                    "0 个"
                )
                else -> renderStatus(
                    Status.OK,
                    "已加入手机里的视频 · $count 个",
                    "进 VR 就能看到；以后拍了新视频，点这一行的「重扫」就行。",
                    "已就绪"
                )
            }
        }
    }

    // -------------------------------------------------- 一键：局域网设备

    private fun discoverLan() {
        setBusy(true, "正在找同一 Wi-Fi 下的设备…（约 4 秒）")
        binding.lanChip.text = "找…"
        lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) {
                runCatching { indexer.lanScan() }.getOrElse { LanScan() }
            }
            setBusy(false)
            binding.lanChip.text = if (scan.total == 0) {
                getString(R.string.setup_lan_chip)
            } else "${scan.total} 台"
            // V2.15：以前这里只有 DLNA 一条路，只开 SMB 的 NAS 永远发现不了，
            // 于是「局域网里有两台 NAS」和「这个 Wi-Fi 下没找到设备」同时成立。
            if (scan.total == 0) {
                when {
                    scan.responders > 0 -> renderStatus(
                        Status.ERR,
                        "这个 Wi-Fi 下有 ${scan.responders} 台设备，但都没开共享",
                        "它们回了广播却没开媒体服务器，445 端口也没开。点「＋ 加片库」选 SMB，" +
                            "地址只填 IP 就行，连上之后会列出共享让你点选。",
                        "0 台可用"
                    )
                    else -> renderStatus(
                        Status.ERR,
                        "这个 Wi-Fi 下没找到设备",
                        getString(R.string.setup_lan_none),
                        "0 台"
                    )
                }
                // V2.45：不再自动展开页面下方的折叠表单。状态条已经写清楚下一步，
                // 用户自己点「＋ 加片库」即可，避免一次失败叠两层界面。
                return@launch
            }
            renderStatus(
                Status.OK,
                "找到 ${scan.total} 台设备",
                detailFor(scan),
                "${scan.total} 台"
            )
            showDevicePicker(scan)
        }
    }

    /** 找到的组合不同，下一步也不同，别用一句话糊过去。 */
    private fun detailFor(scan: LanScan): String = when {
        scan.servers.isNotEmpty() && scan.smbHosts.isNotEmpty() ->
            "媒体服务器点一下直接进；共享设备会问你要账号，选好文件夹就存下了。"
        scan.smbHosts.isNotEmpty() ->
            "它们只开了共享文件夹，没有开媒体服务器。点一台，选好文件夹就加上了。"
        else -> "点一台就能加入，不用填 IP。"
    }

    private fun showDevicePicker(scan: LanScan) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        scan.servers.forEach { server ->
            labels += "${server.label} · 媒体服务器"
            actions += { addDlnaServer(server) }
        }
        scan.smbLabels().forEachIndexed { index, label ->
            val host = scan.smbHosts[index]
            labels += label
            actions += { openSmbHost(host) }
        }
        AlertDialog.Builder(this, R.style.SetupDialog)
            .setTitle(R.string.setup_lan_pick)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.folder_cancel, null)
            .show()
    }

    private fun addDlnaServer(server: DlnaScanner.Server) {
        setBusy(true, "正在加入「${server.label}」…")
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val entity = SourceEntity(
                    type = SourceType.DLNA.name,
                    address = server.controlUrl,
                    displayName = server.label
                )
                val id = database.sourceDao().upsert(entity)
                runCatching { indexer.scan(entity.copy(id = id)) }.getOrElse { error ->
                    // V2.18：失败原因必须落到日志里，否则用户只能看到「扫描没成功」。
                    RunLog.append("scan", "扫描失败 · ${entity.address} · ${error.message}")
                    -1
                }
            }
            setBusy(false)
            renderScanResult(count, "已加入「${server.label}」")
        }
    }

    /**
     * 点一台只开了共享的设备。地址已经探到了，账号还是用户自己的东西：
     * 先用空账号试一次（不少 NAS 开着访客访问），进得去就直接选文件夹；
     * 进不去就把 IP 和类型替用户填好，只让他补账号密码。
     */
    private fun openSmbHost(host: LanSmbScanner.Host) {
        setBusy(true, "正在连「${host.label}」…")
        lifecycleScope.launch {
            // V2.17：这台设备之前配过，就直接用记下的账号，不再走一遍空账号试探。
            val known = credentialsForHost(host.address)
            val guest = known ?: SmbCredentials("", "")
            val opened = withContext(Dispatchers.IO) {
                runCatching { browser.root(SourceType.SMB, host.address, guest) }
            }
            setBusy(false)
            opened.getOrNull()?.let { state ->
                showFolderDialog(state, guest) { chosen ->
                    // 自动发现进来的路径没有「不扫子文件夹」选项，保持默认递归。
                    saveNew(SourceType.SMB, chosen, guest.user, guest.password, true)
                }
                return@launch
            }
            if (known != null) {
                prefillManual(SourceType.SMB, host.address)
                renderStatus(
                    Status.ERR,
                    "「${host.label}」这次没连上",
                    "用的是上次记下的账号。检查设备是否在线，或者点「编辑」改一下账号密码。",
                    "需检查"
                )
                return@launch
            }
            prefillManual(SourceType.SMB, host.address)
            // V2.18：两条死路都写清楚，并且给一条**不用记语法**的出路。
            // 共享名不是「机器的语法」——用户在 NAS 管理界面里就叫它「影片」，
            // 这里只让他补这一个看得见的名字，分隔符由程序补。
            renderStatus(
                Status.ERR,
                "「${host.label}」没能进到共享列表",
                "要么它要账号密码，要么它不允许程序枚举共享（Windows 常见）。" +
                    "补上账号试一次；还不行就把地址写成「${host.address}/共享名」" +
                    "——共享名在设备的管理界面里能看到。",
                "待补信息"
            )
        }
    }

    /**
     * V2.17：同一台设备已经配过片库，就不该再让用户输一次 IP 和账号。
     * 返回这台设备记下的账号；没配过、或者本来就是访客访问，返回 null。
     */
    private fun credentialsForHost(rawHost: String): SmbCredentials? {
        val host = rawHost.trim().trimStart('\\')
        if (host.isBlank()) return null
        val existing = currentSources.firstOrNull {
            it.type == SourceType.SMB.name && it.credentialRef.isNotBlank() &&
                parseUncFull(it.address).first.equals(host, true)
        } ?: return null
        val known = decodeCredentials(credentials.get(existing.credentialRef))
        if (known.user.isBlank()) return null
        return known
    }

    /** 地址一填、设备一认出来，就把记下的账号补进表单，用户只剩「连接」这一下。 */
    private fun prefillKnownCredentials() {
        val form = addBinding ?: return
        if (form.shareUser.text.isNotBlank() || form.sharePass.text.isNotEmpty()) return
        val type = sourceTypeAt(form.shareType.selectedItemPosition) ?: return
        if (type != SourceType.SMB) return
        val normalized = SourceAddress.normalize(type, form.shareAddress.text.toString())
        val host = parseUncFull(normalized).first
        val known = credentialsForHost(host) ?: return
        form.shareUser.setText(known.user)
        form.sharePass.setText(known.password)
    }

    /** 把类型和地址替用户填好并开对话框——他只需要补自己知道的那部分。 */
    private fun prefillManual(type: SourceType, address: String) {
        val position = when (type) {
            SourceType.SMB -> 0
            SourceType.WEBDAV -> 1
            SourceType.HTTP -> 2
            SourceType.DLNA -> 3
            else -> 0
        }
        showAddSourceDialog(AddFormState(position, address, "", "", false))
        addBinding?.shareUser?.requestFocus()
    }

    // ------------------------------------------------------- 加片库对话框

    /** V2.45：旋转时把表单内容整体搬过去，不丢已经填了一半的 IP / 账号。 */
    private data class AddFormState(
        val typePosition: Int,
        val address: String,
        val user: String,
        val pass: String,
        val noSubfolders: Boolean
    )

    private fun captureAddFormState(): AddFormState {
        val form = addBinding ?: return AddFormState(0, "", "", "", false)
        return AddFormState(
            form.shareType.selectedItemPosition,
            form.shareAddress.text?.toString().orEmpty(),
            form.shareUser.text?.toString().orEmpty(),
            form.sharePass.text?.toString().orEmpty(),
            form.scanNoSubfolders.isChecked
        )
    }

    private fun dismissAddDialog() {
        runCatching { addDialog?.dismiss() }
        addDialog = null
        addBinding = null
    }

    /**
     * V2.45：加片库 = 一层对话框，不再把表单折进首屏。
     * 两个入口（「＋ 加片库」与「找不到设备？手动填地址」）开的是同一层；
     * 选设备失败时 prefillManual 也走这里，顺手替用户填好类型和地址。
     */
    private fun showAddSourceDialog(state: AddFormState? = null) {
        dismissAddDialog()
        val form = DialogAddSourceBinding.inflate(layoutInflater)
        val dialog = darkDialog(form.root)
        addBinding = form
        addDialog = dialog

        state?.let {
            form.shareType.setSelection(it.typePosition)
            form.shareAddress.setText(it.address)
            form.shareUser.setText(it.user)
            form.sharePass.setText(it.pass)
            form.scanNoSubfolders.isChecked = it.noSubfolders
        }

        form.shareType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) = syncFormForType()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        form.scanButton.setOnClickListener { connectAndBrowse() }
        form.addSourceCancel.setOnClickListener { dismissAddDialog() }
        dialog.setOnDismissListener {
            // 返回键 / 点外面收掉对话框也走这里，只清引用，状态条内容不动。
            addBinding = null
            addDialog = null
        }
        syncFormForType()
        dialog.show()
    }

    // ---------------------------------------------------------------- 表单

    /** 与 arrays.xml 的 share_types 顺序一一对应；FTP 与本地视频都不在这里。 */
    private fun sourceTypeAt(position: Int): SourceType? = when (position) {
        0 -> SourceType.SMB
        1 -> SourceType.WEBDAV
        2 -> SourceType.HTTP
        3 -> SourceType.DLNA
        else -> null
    }

    /** 类型一变，输入框该不该出现、提示写什么都跟着变。DLNA 不需要地址也不需要账号。 */
    private fun syncFormForType() {
        val form = addBinding ?: return
        val type = sourceTypeAt(form.shareType.selectedItemPosition)
        val needsAddress = type != SourceType.DLNA && type != SourceType.LOCAL
        form.addressBlock.visibility = if (needsAddress) View.VISIBLE else View.GONE
        form.addressHelp.visibility = if (needsAddress) View.VISIBLE else View.GONE
        form.addAccountLabel.visibility = if (needsAddress) View.VISIBLE else View.GONE
        form.addAccountBlock.visibility = if (needsAddress) View.VISIBLE else View.GONE
        form.shareAddress.hint = SourceAddress.hint(type)
        form.addressHelp.text = SourceAddress.help(type)
    }

    private fun statusColor(status: Status): Int = getColor(
        when (status) {
            Status.IDLE -> R.color.setup_muted
            Status.BUSY -> R.color.setup_busy
            Status.OK -> R.color.setup_cyan
            Status.ERR -> R.color.setup_err
        }
    )

    /**
     * 四态共用同一块区域：颜色、圆点、chip 一起变，位置永远不动。
     *
     * V2.18：最后画的那一条要记下来。横竖屏切换会换掉整套 view，
     * 不记的话旋转之后状态条就是一片空白——真机反馈「转横屏之后内容会消失，
     * 切回竖屏也不正常」就是这么来的。
     * V2.45：空闲时整块隐藏；点击动作也随状态一起记住，旋转后照样能点开崩溃详情。
     */
    private fun renderStatus(
        status: Status,
        title: String,
        detail: String,
        chip: String,
        action: (() -> Unit)? = null
    ) {
        lastStatus = StatusLine(status, title, detail, chip, action)
        applyStatus(lastStatus!!)
    }

    private fun applyStatus(line: StatusLine) {
        val color = statusColor(line.status)
        binding.statusDot.background?.mutate()?.setTint(color)
        binding.statusChip.background?.mutate()?.setTint(color)
        binding.statusChip.setTextColor(color)
        binding.statusTitle.text = line.title
        binding.statusDetail.text = line.detail
        binding.statusChip.text = line.chip
        binding.statusBar.visibility =
            if (line.status == Status.IDLE) View.GONE else View.VISIBLE
        binding.statusBar.setOnClickListener(
            if (line.action != null) View.OnClickListener { line.action?.invoke() } else null
        )
        // 加片库对话框开着时，状态条在它背后。忙 / 出错必须就地写一份，
        // 否则用户只会看到一个灰掉的按钮，不知道发生了什么。
        addBinding?.let { form ->
            val inDialog = line.status == Status.BUSY || line.status == Status.ERR
            form.addSourceNote.visibility = if (inDialog) View.VISIBLE else View.GONE
            form.addSourceNote.text = if (inDialog) "${line.title} · ${line.detail}" else ""
            form.addSourceNote.setTextColor(color)
        }
    }

    private data class StatusLine(
        val status: Status,
        val title: String,
        val detail: String,
        val chip: String,
        val action: (() -> Unit)? = null
    )

    private fun setBusy(busy: Boolean, detail: String = "") {
        binding.localCard.isEnabled = !busy
        binding.lanCard.isEnabled = !busy
        addBinding?.scanButton?.isEnabled = !busy
        if (busy) {
            addBinding?.scanButton?.text = getString(R.string.setup_scan_busy)
            renderStatus(Status.BUSY, "正在处理…", detail, "进行中")
        } else {
            addBinding?.scanButton?.text = getString(R.string.setup_scan)
        }
    }

    // ------------------------------------------------------------ 添加片库

    private fun connectAndBrowse() {
        val form = addBinding ?: return
        val type = sourceTypeAt(form.shareType.selectedItemPosition)
        if (type == null) {
            renderStatus(Status.ERR, "先选一种设备", "从上面的列表里挑一个。", "未选")
            return
        }
        prefillKnownCredentials()
        val raw = form.shareAddress.text.toString().trim()
        val user = form.shareUser.text.toString().trim()
        val pass = form.sharePass.text.toString()
        // 对话框在选完文件夹时就关掉了，递归范围要在这一刻记下来。
        pendingRecursive = !form.scanNoSubfolders.isChecked
        val creds = SmbCredentials(user, pass)
        setBusy(true, "正在连接 ${raw.ifBlank { "这台设备" }}…")

        lifecycleScope.launch {
            try {
                val state = browser.root(type, raw, creds)
                // 选文件夹是另一层对话框：先把加片库这层收掉，别叠两层。
                dismissAddDialog()
                renderStatus(
                    Status.OK,
                    "已连上，选一个文件夹",
                    "在列表里点进目录，然后按「就用这一层」。",
                    "选文件夹"
                )
                showFolderDialog(state, creds) { chosen ->
                    saveNew(type, chosen, user, pass, pendingRecursive)
                }
            } catch (error: Exception) {
                renderStatus(
                    Status.ERR,
                    "没连上",
                    error.message ?: "检查 IP、Wi-Fi 和账号密码；也可以直接粘贴完整地址。",
                    "需检查"
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun saveNew(
        type: SourceType,
        state: BrowseState,
        user: String,
        pass: String,
        recursive: Boolean
    ) {
        setBusy(true, "正在扫描「${state.displayName}」…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // V2.17：同一台设备上再开一个文件夹时，不必再造一份凭证。
                val reusable = currentSources.firstOrNull {
                    it.type == type.name && it.credentialRef.isNotBlank() &&
                        parseUncFull(it.address).first ==
                        parseUncFull(state.address).first &&
                        decodeCredentials(credentials.get(it.credentialRef)).let { known ->
                            known.user == user && known.password == pass
                        }
                }?.credentialRef.orEmpty()
                val alias = when {
                    reusable.isNotBlank() -> reusable
                    user.isNotBlank() || pass.isNotEmpty() -> credentials.put(
                        "src_${type.name.lowercase()}_${System.currentTimeMillis()}",
                        encodeCredentials(user, pass)
                    )
                    else -> ""
                }
                val entity = SourceEntity(
                    type = type.name,
                    address = state.address,
                    displayName = state.displayName,
                    credentialRef = alias,
                    recursive = recursive
                )
                val id = database.sourceDao().upsert(entity)
                runCatching { indexer.scan(entity.copy(id = id)) }.getOrElse { error ->
                    // V2.18：失败原因必须落到日志里，否则用户只能看到「扫描没成功」。
                    RunLog.append("scan", "扫描失败 · ${entity.address} · ${error.message}")
                    -1
                }
            }
            setBusy(false)
            renderScanResult(result, "已添加「${state.displayName}」")
        }
    }

    private fun renderScanResult(count: Int, prefix: String) {
        when {
            // V2.18：真机反馈「再取消勾选之后提示扫描不成功」——但当时那句话没给任何线索，
            // 用户只能退出 App 重开。现在把原因写进运行日志，并在界面上指路。
            count < 0 -> renderStatus(
                Status.ERR,
                "$prefix，但扫描没成功",
                "片库已保存。点右上角「运行日志」能看到具体原因；" +
                    "常见原因是设备刚才断开了，点这一行的「重扫」再试一次。",
                "需重扫"
            )
            count == 0 -> renderStatus(
                Status.OK,
                "$prefix · 这个片库里还没有视频",
                "确认设备在线、文件夹里确实有视频；也可以点这一行的「重扫」再试一次。",
                "0 个"
            )
            else -> renderStatus(
                Status.OK,
                "$prefix · 共 $count 个视频",
                "子文件夹也一起扫了。想只收当前这一层，进「编辑」勾上「不扫描子文件夹」。",
                "已就绪"
            )
        }
    }

    // -------------------------------------------------------------- 选文件夹

    /** 深色对话框：自定义视图自带背景，不依赖系统按钮栏配色。 */
    /**
     * V2.34 / B13 补齐：戴镜前的设置，目前只有瞳距一项。
     *
     * 为什么补在平面层：`VrSettings.ipdHalfMm` 已经是全工程唯一真相源（0.5mm 整数），
     * VR 内那条「边看边调」是为「看着画面调」设计的补救路径；但瞳距本身是戴镜前
     * 就该定好的参数——手机屏能看见、能拖、不用摸着侧键猜。
     *
     * 写盘走 `SettingsRepository.update()`（它顺带完成 `ipd_mm` → `ipd_half_mm` 迁移）；
     * `VrActivity.onCreate` 每次都新建一份 repository，所以这里改完直接进 VR 就生效，
     * 不需要重启，也不存在两套缓存。
     */
    private fun showSettings() {
        val view = DialogSettingsBinding.inflate(layoutInflater)
        val dialog = darkDialog(view.root)
        val repo = SettingsRepository(this)
        var half = repo.current.ipdHalfMm

        fun render() {
            view.ipdValue.text = VrSettings.ipdLabel(half)
            // Slider 的值必须落在 50.0 + n×0.5 的网格上，half/2f 天然满足；
            // 用差值判断而不是 `!=`，避免浮点抖动把监听器打成死循环。
            val mm = half / 2f
            if (abs(view.ipdSlider.value - mm) > 0.01f) view.ipdSlider.value = mm
            view.ipdMinus.isEnabled = half > VrSettings.IPD_MIN_HALF
            view.ipdPlus.isEnabled = half < VrSettings.IPD_MAX_HALF
        }

        fun apply(next: Int) {
            val clamped = next.coerceIn(VrSettings.IPD_MIN_HALF, VrSettings.IPD_MAX_HALF)
            if (clamped != half) {
                half = clamped
                repo.update { it.copy(ipdHalfMm = clamped) }
                RunLog.append("ipd", "戴镜前调瞳距 · ${VrSettings.ipdLabel(clamped)}")
            }
            render()
        }

        // 拖动就是粗调（0.5mm 步进已经够细），± 是落到精确值用的微调，
        // 两个入口写的是同一个 `ipdHalfMm`，不存在「粗调微调各存一份」。
        view.ipdSlider.addOnChangeListener { _, value, _ -> apply(VrSettings.ipdHalfOf(value)) }
        view.ipdMinus.setOnClickListener { apply(half - VrSettings.IPD_FINE_HALF) }
        view.ipdPlus.setOnClickListener { apply(half + VrSettings.IPD_FINE_HALF) }
        view.ipdReset.setOnClickListener { apply(VrSettings.IPD_DEFAULT_HALF) }

        view.settingsDone.setOnClickListener { dialog.dismiss() }

        // V2.38：重定「正前方」的倒计时时长。它是「在限定时间内调整并稳定姿态」里的
        // 那个限定时间——摆好只需确认的人给 3–5 秒，躺下再找角度的人给 15 秒。
        // 和瞳距一样走 SettingsRepository，VrActivity 每次都新建一份，进 VR 即生效。
        var alignMs = repo.current.alignCountdownMs.coerceIn(
            VrSettings.ALIGN_MIN_MS, VrSettings.ALIGN_MAX_MS
        )

        fun renderAlign() {
            view.alignValue.text = "${VrSettings.alignSeconds(alignMs)} 秒"
            view.alignMinus.isEnabled = alignMs > VrSettings.ALIGN_MIN_MS
            view.alignPlus.isEnabled = alignMs < VrSettings.ALIGN_MAX_MS
        }

        fun applyAlign(delta: Long) {
            val next = (alignMs + delta)
                .coerceIn(VrSettings.ALIGN_MIN_MS, VrSettings.ALIGN_MAX_MS)
            if (next != alignMs) {
                alignMs = next
                repo.update { it.copy(alignCountdownMs = next) }
                RunLog.append("pose", "重定倒计时 → ${VrSettings.alignSeconds(next)} 秒")
            }
            renderAlign()
        }

        view.alignMinus.setOnClickListener { applyAlign(-VrSettings.ALIGN_STEP_MS) }
        view.alignPlus.setOnClickListener { applyAlign(VrSettings.ALIGN_STEP_MS) }
        renderAlign()

        render()
        dialog.show()
    }

    private fun darkDialog(view: View): AlertDialog {
        val dialog = AlertDialog.Builder(this, R.style.SetupDialog).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(getColor(R.color.setup_card)))
        // V2.45：默认对话框很窄，表单里的两个输入框会被挤成两截。
        // show 之后统一给到屏宽的 92%，横屏也留边，不会被拉伸成一条。
        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun noteFor(state: BrowseState): String {
        val base = if (state.canChoose) {
            "这一层有 ${state.videoCount} 个视频、${state.folders.size} 个文件夹。" +
                "选「就用这一层」只收这一层的视频，下层不会自动展开。"
        } else state.note
        return listOf(base, state.note).filter { it.isNotBlank() }
            .distinct().joinToString("  ")
    }

    private fun showFolderDialog(
        initial: BrowseState,
        creds: SmbCredentials,
        onChoose: (BrowseState) -> Unit
    ) {
        val view = DialogFolderBinding.inflate(layoutInflater)
        val dialog = darkDialog(view.root)
        var state = initial

        fun render() {
            view.folderCrumb.text = "当前位置 · ${state.crumb}"
            view.folderNote.text = noteFor(state)
            view.folderUp.isEnabled = state.canGoUp
            view.folderUp.alpha = if (state.canGoUp) 1f else 0.4f
            view.folderChoose.isEnabled = state.canChoose
            view.folderChoose.alpha = if (state.canChoose) 1f else 0.45f
            view.folderList.removeAllViews()

            if (state.folders.isEmpty()) {
                val empty = ItemFolderBinding.inflate(layoutInflater, view.folderList, false)
                // V2.12：这行原来是写死的「这一层没有可再展开的文件夹」，
                // 共享层为空时用户看不出是设备没共享、账号没权限，还是枚举没成功。
                empty.folderName.text =
                    state.emptyHint.ifBlank { "这一层没有可再展开的文件夹" }
                empty.folderDetail.text = ""
                view.folderList.addView(empty.root)
            }
            state.folders.forEach { choice ->
                val row = ItemFolderBinding.inflate(layoutInflater, view.folderList, false)
                row.folderName.text = choice.name
                row.folderDetail.text = choice.detail
                row.root.setOnClickListener {
                    view.folderNote.text = "正在打开「${choice.name}」…"
                    lifecycleScope.launch {
                        try {
                            state = browser.enter(state, choice, creds)
                            render()
                        } catch (error: Exception) {
                            view.folderNote.text = "打不开：${error.message}"
                        }
                    }
                }
                view.folderList.addView(row.root)
            }
        }

        view.folderUp.setOnClickListener {
            lifecycleScope.launch {
                try {
                    state = browser.up(state, creds)
                    render()
                } catch (error: Exception) {
                    view.folderNote.text = "返回失败：${error.message}"
                }
            }
        }
        view.folderCancel.setOnClickListener { dialog.dismiss() }
        view.folderChoose.setOnClickListener {
            dialog.dismiss()
            onChoose(state)
        }
        render()
        dialog.show()
    }

    // -------------------------------------------------------------- 改 / 删

    private fun showEditDialog(source: SourceEntity) {
        val view = DialogEditSourceBinding.inflate(layoutInflater)
        val dialog = darkDialog(view.root)
        var chosen = source.address
        val type = runCatching { SourceType.valueOf(source.type) }
            .getOrDefault(SourceType.LOCAL)
        // V2.45：本机视频 / 媒体服务器没有账号、目录层级这些概念。
        // 上一版一律把账号密码和「换文件夹」摆出来，用户看到的都是填不了也没用的框。
        val folderable = type == SourceType.SMB ||
            type == SourceType.WEBDAV || type == SourceType.HTTP
        val accountVisibility = if (folderable) View.VISIBLE else View.GONE
        view.editAccountLabel.visibility = accountVisibility
        view.editUser.visibility = accountVisibility
        view.editPass.visibility = accountVisibility
        view.editBrowse.visibility = accountVisibility
        view.editNoSubfolders.visibility = accountVisibility
        view.editNoSubfolders.isChecked = !source.recursive
        view.editName.setText(source.displayName)
        view.editAddress.text = source.address.ifBlank { getString(R.string.setup_local_title) }
        view.editNote.text = getString(
            if (folderable) R.string.edit_source_browse_note
            else R.string.edit_source_local_note
        )

        view.editBrowse.setOnClickListener {
            val creds = decodeCredentials(credentials.get(source.credentialRef))
            view.editNote.text = "正在连接…"
            lifecycleScope.launch {
                try {
                    val initial = browser.at(type, chosen, creds)
                    showFolderDialog(initial, creds) { state ->
                        chosen = state.address
                        view.editAddress.text = state.address
                        // V2.17：换了文件夹，名字跟着换 —— 用户没自己改过名字时才动，
                        // 否则会把他特意起的名字冲掉。
                        if (view.editName.text.toString().trim() == source.displayName) {
                            view.editName.setText(state.displayName)
                        }
                        view.editNote.text = getString(R.string.edit_source_ready, state.displayName)
                    }
                } catch (error: Exception) {
                    view.editNote.text = "没连上：${error.message}"
                }
            }
        }
        view.editRescan.setOnClickListener {
            dialog.dismiss()
            rescan(source)
        }
        view.editCancel.setOnClickListener { dialog.dismiss() }
        view.editSave.setOnClickListener {
            // 名字留在原来的值上（= 用户没自己改过），就交给 saveEdit 按新地址重新生成，
            // 免得换了文件夹之后卡片上还写着旧文件夹的名字。
            val typed = view.editName.text.toString().trim()
            val name = if (typed == source.displayName) "" else typed
            val user = view.editUser.text.toString().trim()
            val pass = view.editPass.text.toString()
            val recursive = !view.editNoSubfolders.isChecked
            dialog.dismiss()
            saveEdit(source, chosen, name, user, pass, recursive)
        }
        dialog.show()
    }

    private fun saveEdit(
        source: SourceEntity,
        address: String,
        name: String,
        user: String,
        pass: String,
        recursive: Boolean
    ) {
        setBusy(true, "正在保存并重新扫描…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val type = runCatching { SourceType.valueOf(source.type) }
                    .getOrDefault(SourceType.LOCAL)
                val newRef = if (user.isNotBlank() || pass.isNotEmpty()) {
                    val alias = "src_${type.name.lowercase()}_${System.currentTimeMillis()}"
                    credentials.put(alias, encodeCredentials(user, pass))
                    if (source.credentialRef.isNotBlank() &&
                        source.credentialRef != alias
                    ) credentials.remove(source.credentialRef)
                    alias
                } else source.credentialRef
                val label = name.ifBlank { SourceAddress.displayName(type, address) }
                val updated = source.copy(
                    address = address, displayName = label, credentialRef = newRef,
                    recursive = recursive
                )
                database.sourceDao().update(updated)
                // 换了目录、或者改了递归范围，旧索引都要丢掉，否则会留下打不开的幽灵条目。
                if (address != source.address || recursive != source.recursive) {
                    purgeMedia(source.id)
                }
                runCatching { indexer.scan(updated) }.getOrElse { error ->
                    RunLog.append("scan", "扫描失败 · ${updated.address} · ${error.message}")
                    -1
                }
            }
            setBusy(false)
            renderScanResult(result, "「${name.ifBlank { source.displayName }}」已更新")
        }
    }

    /**
     * V2.17：临时在 VR 里藏起一个片库。
     * 只改 enabled，索引、断点、凭证全部保留 —— 用户要的是「先别看它」，不是「删掉」。
     */
    private fun toggleVisible(source: SourceEntity) {
        val next = !source.enabled
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { database.sourceDao().setEnabled(source.id, next) }
            renderStatus(
                Status.OK,
                if (next) "「${source.displayName}」已显示" else "「${source.displayName}」已隐藏",
                if (next) "进 VR 就能看到它。" else "索引和断点都还在；点「显示」就回来。",
                if (next) "已显示" else "已隐藏"
            )
        }
    }

    /**
     * V2.18：在同一台设备上再选一个文件夹 —— 不填 IP、不填账号。
     *
     * 上一版我理解错了：加了个「＋ 加片库」，点开还是要用户自己填地址。
     * 用户原话是「我说的是不用重新填 IP 和凭据，不是不知道怎么手动添加」。
     * 所以这个入口必须**从已有片库出发**：拿它的主机和它已经记住的凭证直接连回去，
     * 用户只做一件事——在看得见的目录里点一个文件夹。
     */
    private fun addFolderTo(source: SourceEntity) {
        val type = runCatching { SourceType.valueOf(source.type) }
            .getOrDefault(SourceType.SMB)
        val creds = decodeCredentials(credentials.get(source.credentialRef))
        // SMB 要从主机开始（root 会自己列出共享）；网盘 / 网页目录从当前地址往上都一样。
        val start = if (type == SourceType.SMB) {
            parseUncFull(source.address).first
        } else {
            source.address
        }
        setBusy(true, "正在连回「${source.displayName}」…")
        lifecycleScope.launch {
            // 注意：runCatching 放在 withContext 外面再 getOrNull()。
            // 写在里面会让整个 lambda 的类型推导绕一圈，编译期容易把返回值认成别的类型。
            val opened = withContext(Dispatchers.IO) {
                runCatching { browser.root(type, start, creds) }
            }.getOrNull()
            setBusy(false)
            if (opened == null) {
                renderStatus(
                    Status.ERR,
                    "「${source.displayName}」这次没连上",
                    "用的是它已经记住的账号。检查设备是否在线，或者点这一行的「编辑」看看。",
                    "需检查"
                )
                return@launch
            }
            showFolderDialog(initial = opened, creds = creds) { chosen ->
                // 复用同一套凭证：saveNew 会比对同主机同账号，直接接住 credentialRef。
                // 扫描范围沿用这个片库自己的设置，别悄悄把递归改成默认值。
                saveNew(type, chosen, creds.user, creds.password, source.recursive)
            }
        }
    }

    private fun rescan(source: SourceEntity) {
        setBusy(true, "正在重新扫描「${source.displayName}」…")
        // V2.29：用户显式点「重扫」= 强制刷新共享列表 / 目录缓存，重新枚举一次。
        RunLog.append("smb", "重扫 · ${source.address} · 强制刷新缓存")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { indexer.scan(source, forceRefresh = true) }.getOrElse { error ->
                    RunLog.append("scan", "重扫失败 · ${source.address} · ${error.message}")
                    -1
                }
            }
            setBusy(false)
            renderScanResult(result, "「${source.displayName}」已重扫")
        }
    }

    private fun confirmDelete(source: SourceEntity) {
        AlertDialog.Builder(this, R.style.SetupDialog)
            .setTitle("删除「${source.displayName}」？")
            .setMessage("只从本机移除这个片库和它的索引、断点记录；设备里的视频文件不会被动到。")
            .setNegativeButton(R.string.folder_cancel, null)
            .setPositiveButton(R.string.setup_delete) { _, _ -> performDelete(source) }
            .show()
    }

    private fun performDelete(source: SourceEntity) {
        setBusy(true, "正在移除片库…")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                purgeEverything(source.id)
                database.sourceDao().deleteById(source.id)
                if (source.credentialRef.isNotBlank()) {
                    credentials.remove(source.credentialRef)
                }
            }
            setBusy(false)
            renderStatus(
                Status.OK,
                "已删除「${source.displayName}」",
                "设备里的文件没动；想加回来再点一次入口就行。",
                "已移除"
            )
        }
    }

    /**
     * V2.18：**只清索引，不清断点。**
     *
     * 真机反馈「隐藏之后再显示回来，断点被清除了」——隐藏本身没动断点，
     * 是改递归范围 / 换文件夹时这里把断点一起删了。断点是用户攒下来的东西，
     * 改一次扫描范围不该丢；断点现在挂在 `sourceId:path` 上，路径没变就还能续上。
     * 只有真正删除片库（[performDelete]）才连断点一起清。
     */
    private suspend fun purgeMedia(sourceId: Long) {
        database.formatOverrideDao().deleteBySourcePrefix("$sourceId:")
        database.mediaDao().deleteBySource(sourceId)
        indexer.deletePosters(sourceId)
    }

    /** 删片库才用这个：断点也一起清，不留孤儿数据。 */
    private suspend fun purgeEverything(sourceId: Long) {
        purgeMedia(sourceId)
        database.progressDao().deleteBySourcePrefix("$sourceId:")
    }

    // ------------------------------------------------------------ 片库列表

    private fun renderSources(sources: List<SourceEntity>) {
        currentSources = sources
        // V2.45：标题行写真实的片库数与视频总数，不再用含糊的「N 个片源」。
        binding.sourceCount.text = getString(
            R.string.source_library_count, sources.size, mediaCounts.values.sum()
        )
        binding.sourceList.removeAllViews()
        // V2.46 / P0：主按钮自己说明「现在还不能进」，不用等点了才被告知。
        binding.enterVrButton.text = getString(
            if (sources.isEmpty()) R.string.setup_enter_first else R.string.setup_enter
        )

        val hasLocal = sources.any { it.type == SourceType.LOCAL.name }
        binding.localChip.text = getString(
            if (hasLocal) R.string.setup_local_added else R.string.setup_local_chip
        )

        if (sources.isEmpty()) {
            val row = ItemSourceBinding.inflate(layoutInflater, binding.sourceList, false)
            row.sourceTitle.text = "还没有片库"
            row.sourceSubtitle.text = "点上面任意一个入口就行，不用填东西"
            row.sourceChip.text = "空"
            row.sourceChip.setTextColor(getColor(R.color.setup_muted))
            row.sourceMore.visibility = View.GONE
            row.sourceRescan.visibility = View.GONE
            row.sourceAddFolder.visibility = View.GONE
            row.sourceHide.visibility = View.GONE
            tintRow(row, getColor(R.color.setup_muted))
            binding.sourceList.addView(row.root)
            return
        }

        val cyan = getColor(R.color.setup_cyan)
        val muted = getColor(R.color.setup_muted)
        sources.forEach { source ->
            val row = ItemSourceBinding.inflate(layoutInflater, binding.sourceList, false)
            val count = mediaCounts[source.id] ?: 0
            row.sourceTitle.text = source.displayName
            row.sourceSubtitle.text = getString(R.string.source_video_count, count)
            row.sourceChip.text =
                if (source.enabled) chipFor(source) else getString(R.string.source_hidden)
            row.sourceChip.setTextColor(if (source.enabled) cyan else muted)
            if (source.address.isNotBlank()) {
                row.sourceAddress.text = source.address
                row.sourceAddress.visibility = View.VISIBLE
            } else {
                row.sourceAddress.visibility = View.GONE
            }
            tintRow(row, if (source.enabled) cyan else muted)
            row.sourceHide.text = getString(
                if (source.enabled) R.string.setup_hide else R.string.setup_show
            )
            row.sourceHide.setTextColor(
                getColor(if (source.enabled) R.color.setup_text else R.color.setup_cyan)
            )
            if (!source.enabled) {
                // 隐藏的片库压暗一点，但仍然留在列表里，随时能点回来。
                row.sourceTitle.alpha = 0.55f
                row.sourceSubtitle.alpha = 0.55f
                row.sourceAddress.alpha = 0.55f
            }
            // 整行可点 = 编辑（V2.18 真机反馈后定的，保留）。
            row.root.setOnClickListener { showEditDialog(source) }
            row.sourceHide.setOnClickListener { toggleVisible(source) }
            // 只有网络片源才有「再加一个文件夹」这回事；本机与媒体服务器没有目录概念。
            val folderable = source.type == SourceType.SMB.name ||
                source.type == SourceType.WEBDAV.name ||
                source.type == SourceType.HTTP.name
            row.sourceAddFolder.visibility = if (folderable) View.VISIBLE else View.GONE
            row.sourceAddFolder.setOnClickListener { addFolderTo(source) }
            row.sourceRescan.setOnClickListener { rescan(source) }
            row.sourceMore.setOnClickListener { showSourceMenu(source) }
            binding.sourceList.addView(row.root)
        }
    }

    /**
     * V2.45：删除不再和「重扫」并排放在卡片上。
     * 以前它是 32dp 的小字，紧挨着常用动作，误触就是一次不可逆的清理。
     * 现在收进「更多」，而且 confirmDelete 仍有二次确认。
     */
    private fun showSourceMenu(source: SourceEntity) {
        val labels = arrayOf(
            getString(R.string.source_more_edit),
            getString(R.string.source_more_delete)
        )
        AlertDialog.Builder(this, R.style.SetupDialog)
            .setTitle(getString(R.string.source_more_title, source.displayName))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showEditDialog(source)
                    1 -> confirmDelete(source)
                }
            }
            .setNegativeButton(R.string.folder_cancel, null)
            .show()
    }

    /** 卡片上不写协议缩写，写「这是什么」。 */
    private fun chipFor(source: SourceEntity): String = when (source.type) {
        SourceType.LOCAL.name -> "本机"
        SourceType.SMB.name -> "共享"
        SourceType.WEBDAV.name -> "网盘"
        SourceType.HTTP.name -> "网页目录"
        SourceType.DLNA.name -> "媒体服务器"
        else -> source.type
    }

    /** V2.45：状态点跟着片库显隐变色；胶囊与动作按钮改用固定底，不再整体染色。 */
    private fun tintRow(row: ItemSourceBinding, color: Int) {
        row.sourceDot.background?.mutate()?.setTint(color)
    }
}
