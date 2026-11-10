package com.gazereel.vr.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.gazereel.vr.data.SmbCredentials
import com.gazereel.vr.data.SmbSessionPool
import com.gazereel.vr.data.SourceAddress
import com.gazereel.vr.data.parseUncFull
import com.gazereel.vr.data.db.AppDatabase
import com.gazereel.vr.data.db.SourceEntity
import com.gazereel.vr.data.decodeCredentials
import com.gazereel.vr.data.encodeCredentials
import com.gazereel.vr.databinding.ActivitySetupBinding
import com.gazereel.vr.databinding.DialogEditSourceBinding
import com.gazereel.vr.databinding.DialogFolderBinding
import com.gazereel.vr.databinding.ItemFolderBinding
import com.gazereel.vr.databinding.ItemSourceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            addLocalVideos()
        } else {
            permissionDenied = true
            renderStatus(
                Status.ERR,
                getString(R.string.setup_permission_title),
                getString(R.string.setup_permission_detail),
                "需授权"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.get(this)
        credentials = CredentialStore(this)
        indexer = MediaIndexer(this, database, pool, credentials)
        browser = FolderBrowser(indexer, pool)

        // 两个大入口：都不需要用户输入任何东西
        binding.localCard.setOnClickListener { addLocalVideos() }
        binding.lanCard.setOnClickListener { discoverLan() }
        binding.manualToggle.setOnClickListener { toggleManual() }

        binding.scanButton.setOnClickListener { connectAndBrowse() }
        binding.enterVrButton.setOnClickListener {
            startActivity(Intent(this, VrActivity::class.java))
        }
        // V2.7.4 测试版：一页能截图的运行日志，不用连电脑也能反馈问题。
        binding.logEntry.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.shareType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) = syncFormForType()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        syncFormForType()

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
            renderStatus(Status.ERR, "上次进 VR 没成功", head, "看详情")
            binding.statusBar.setOnClickListener {
                AlertDialog.Builder(this, R.style.SetupDialog)
                    .setTitle("上次进入 VR 的失败记录")
                    .setMessage(raw.take(4000))
                    .setNegativeButton("知道了", null)
                    .setNeutralButton("清空并重试官方头追") { _, _ ->
                        CrashLog.clear(this)
                        CrashLog.unblockCardboard(this)
                        CrashLog.clearBootMarker(this)
                        binding.statusBar.setOnClickListener(null)
                        renderStatus(
                            Status.IDLE,
                            "点一个就行，不用填东西",
                            "手机里的视频，或者同一 Wi-Fi 下的设备。",
                            "未添加"
                        )
                    }
                    .show()
            }
            return
        }

        if (blocked) {
            renderStatus(
                Status.OK,
                "已切到兼容模式",
                "官方头追上次没能起来，现在用手机传感器，一样能看。想恢复点这里。",
                "兼容模式"
            )
            binding.statusBar.setOnClickListener {
                AlertDialog.Builder(this, R.style.SetupDialog)
                    .setTitle("恢复官方头追？")
                    .setMessage("恢复后如果还是进不去，会自动再退回兼容模式。")
                    .setNegativeButton("先这样", null)
                    .setPositiveButton("恢复") { _, _ ->
                        CrashLog.unblockCardboard(this)
                        binding.statusBar.setOnClickListener(null)
                        renderStatus(
                            Status.IDLE,
                            "点一个就行，不用填东西",
                            "手机里的视频，或者同一 Wi-Fi 下的设备。",
                            "未添加"
                        )
                    }
                    .show()
            }
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
            if (permissionDenied) openAppSettings() else mediaPermission.launch(mediaPermissionName())
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
                runCatching { indexer.scan(entity.copy(id = id)) }.getOrElse { -1 }
            }
            setBusy(false)
            binding.localChip.text = getString(R.string.setup_local_chip)
            when {
                count < 0 -> renderStatus(
                    Status.ERR,
                    "没能读取手机里的视频",
                    getString(R.string.setup_permission_detail),
                    "需检查"
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
                        "它们回了广播却没开媒体服务器，445 端口也没开。在下面的手动添加里选 SMB，" +
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
                expandManual()
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
                runCatching { indexer.scan(entity.copy(id = id)) }.getOrElse { -1 }
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
                    saveNew(SourceType.SMB, chosen, guest.user, guest.password)
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
            renderStatus(
                Status.ERR,
                "「${host.label}」要账号才能进",
                "IP 已经替你填好了，补上这台设备的账号密码，点「连接并选文件夹」就行。",
                "待填账号"
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
        if (binding.shareUser.text.isNotBlank() || binding.sharePass.text.isNotEmpty()) return
        val type = sourceTypeAt(binding.shareType.selectedItemPosition) ?: return
        if (type != SourceType.SMB) return
        val normalized = SourceAddress.normalize(type, binding.shareAddress.text.toString())
        val host = parseUncFull(normalized).first
        val known = credentialsForHost(host) ?: return
        binding.shareUser.setText(known.user)
        binding.sharePass.setText(known.password)
    }

    /** 把类型和地址替用户填好并展开表单——他只需要补自己知道的那部分。 */
    private fun prefillManual(type: SourceType, address: String) {
        val position = when (type) {
            SourceType.SMB -> 0
            SourceType.WEBDAV -> 1
            SourceType.HTTP -> 2
            SourceType.DLNA -> 3
            else -> 0
        }
        binding.shareType.setSelection(position)
        binding.shareAddress.setText(address)
        binding.shareUser.requestFocus()
        expandManual()
        syncFormForType()
    }

    // ------------------------------------------------------------ 手动兜底

    private fun toggleManual() {
        val open = binding.manualBlock.visibility == View.VISIBLE
        binding.manualBlock.visibility = if (open) View.GONE else View.VISIBLE
        binding.manualToggle.text =
            if (open) getString(R.string.setup_manual_toggle) else "收起手动添加 ▴"
    }

    private fun expandManual() {
        if (binding.manualBlock.visibility != View.VISIBLE) toggleManual()
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

    /** 类型一变，输入框该不该出现、提示写什么都跟着变。 */
    private fun syncFormForType() {
        val type = sourceTypeAt(binding.shareType.selectedItemPosition)
        val needsAddress = type != SourceType.DLNA && type != SourceType.LOCAL
        binding.addressBlock.visibility = if (needsAddress) View.VISIBLE else View.GONE
        binding.shareAddress.hint = SourceAddress.hint(type)
        binding.addressHelp.text = SourceAddress.help(type)
    }

    private fun statusColor(status: Status): Int = getColor(
        when (status) {
            Status.IDLE -> R.color.setup_muted
            Status.BUSY -> R.color.setup_busy
            Status.OK -> R.color.setup_cyan
            Status.ERR -> R.color.setup_err
        }
    )

    /** 四态共用同一块区域：颜色、圆点、chip 一起变，位置永远不动。 */
    private fun renderStatus(status: Status, title: String, detail: String, chip: String) {
        val color = statusColor(status)
        binding.statusDot.background?.mutate()?.setTint(color)
        binding.statusChip.background?.mutate()?.setTint(color)
        binding.statusChip.setTextColor(color)
        binding.statusTitle.text = title
        binding.statusDetail.text = detail
        binding.statusChip.text = chip
    }

    private fun setBusy(busy: Boolean, detail: String = "") {
        binding.scanButton.isEnabled = !busy
        binding.localCard.isEnabled = !busy
        binding.lanCard.isEnabled = !busy
        if (busy) {
            binding.scanButton.text = getString(R.string.setup_scan_busy)
            renderStatus(Status.BUSY, "正在处理…", detail, "进行中")
        } else {
            binding.scanButton.text = getString(R.string.setup_scan)
        }
    }

    // ------------------------------------------------------------ 添加片库

    private fun connectAndBrowse() {
        val type = sourceTypeAt(binding.shareType.selectedItemPosition)
        if (type == null) {
            renderStatus(Status.ERR, "先选一种设备", "从上面的列表里挑一个。", "未选")
            return
        }
        prefillKnownCredentials()
        val raw = binding.shareAddress.text.toString().trim()
        val user = binding.shareUser.text.toString().trim()
        val pass = binding.sharePass.text.toString()
        val creds = SmbCredentials(user, pass)
        setBusy(true, "正在连接 ${raw.ifBlank { "这台设备" }}…")

        lifecycleScope.launch {
            try {
                val state = browser.root(type, raw, creds)
                showFolderDialog(state, creds) { chosen ->
                    saveNew(type, chosen, user, pass)
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
        pass: String
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
                    recursive = !binding.scanNoSubfolders.isChecked
                )
                val id = database.sourceDao().upsert(entity)
                runCatching { indexer.scan(entity.copy(id = id)) }.getOrElse { -1 }
            }
            setBusy(false)
            renderScanResult(result, "已添加「${state.displayName}」")
        }
    }

    private fun renderScanResult(count: Int, prefix: String) {
        when {
            count < 0 -> renderStatus(
                Status.ERR,
                "$prefix，但扫描没成功",
                "片库已保存。检查设备是否在线，然后点这一行的「重扫」。",
                "需重扫"
            )
            count == 0 -> renderStatus(
                Status.OK,
                "$prefix · 这一层暂时没有视频",
                "下层文件夹不会自动展开；想换一层点「改」→「换个文件夹」。",
                "已就绪"
            )
            else -> renderStatus(
                Status.OK,
                "$prefix，扫描到 $count 个视频",
                "只收这一层的视频。换文件夹点「改」，不用重新添加。",
                "已就绪"
            )
        }
    }

    // -------------------------------------------------------------- 选文件夹

    /** 深色对话框：自定义视图自带背景，不依赖系统按钮栏配色。 */
    private fun darkDialog(view: View): AlertDialog {
        val dialog = AlertDialog.Builder(this, R.style.SetupDialog).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(getColor(R.color.setup_card)))
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
        view.editNoSubfolders.isChecked = !source.recursive
        view.editName.setText(source.displayName)
        view.editAddress.text = source.address
        view.editNote.text = "换文件夹不用重新添加；账号密码留空表示保持原样。"

        view.editBrowse.setOnClickListener {
            val type = runCatching { SourceType.valueOf(source.type) }
                .getOrDefault(SourceType.LOCAL)
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
                        view.editNote.text = "已选「${state.displayName}」· 点保存生效"
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
                runCatching { indexer.scan(updated) }.getOrElse { -1 }
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

    private fun rescan(source: SourceEntity) {
        setBusy(true, "正在重新扫描「${source.displayName}」…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { indexer.scan(source) }.getOrElse { -1 }
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
                purgeMedia(source.id)
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

    /** 顺序要紧：断点与格式记忆依赖 media_item，必须先清再删媒体行。 */
    private suspend fun purgeMedia(sourceId: Long) {
        database.progressDao().deleteBySource(sourceId)
        database.formatOverrideDao().deleteBySourcePrefix("$sourceId:")
        database.mediaDao().deleteBySource(sourceId)
        indexer.deletePosters(sourceId)
    }

    // ------------------------------------------------------------ 片库列表

    private fun renderSources(sources: List<SourceEntity>) {
        currentSources = sources
        binding.sourceCount.text = "${sources.size} 个片源"
        binding.sourceList.removeAllViews()

        val hasLocal = sources.any { it.type == SourceType.LOCAL.name }
        binding.localChip.text = getString(
            if (hasLocal) R.string.setup_local_added else R.string.setup_local_chip
        )

        if (sources.isEmpty()) {
            val row = ItemSourceBinding.inflate(layoutInflater, binding.sourceList, false)
            row.sourceTitle.text = "还没有片库"
            row.sourceSubtitle.text = "点上面任意一个入口就行，不用填东西"
            row.sourceChip.text = "空"
            row.sourceEdit.visibility = View.GONE
            row.sourceRescan.visibility = View.GONE
            row.sourceDelete.visibility = View.GONE
            tintRow(row, getColor(R.color.setup_muted))
            binding.sourceList.addView(row.root)
            return
        }

        val cyan = getColor(R.color.setup_cyan)
        sources.forEach { source ->
            val row = ItemSourceBinding.inflate(layoutInflater, binding.sourceList, false)
            row.sourceTitle.text = source.displayName
            row.sourceSubtitle.text = source.address.ifBlank { "整机视频" }
            row.sourceChip.text = chipFor(source)
            row.sourceChip.setTextColor(cyan)
            tintRow(row, cyan)
            row.sourceHide.text = getString(
                if (source.enabled) R.string.setup_hide else R.string.setup_show
            )
            row.sourceHide.setTextColor(
                getColor(if (source.enabled) R.color.setup_muted else R.color.setup_cyan)
            )
            if (!source.enabled) {
                // 隐藏的片库压暗一点，但仍然留在列表里，随时能点回来。
                row.sourceTitle.alpha = 0.55f
                row.sourceSubtitle.alpha = 0.55f
            }
            row.sourceHide.setOnClickListener { toggleVisible(source) }
            row.sourceEdit.setOnClickListener { showEditDialog(source) }
            row.sourceRescan.setOnClickListener { rescan(source) }
            row.sourceDelete.setOnClickListener { confirmDelete(source) }
            binding.sourceList.addView(row.root)
        }
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

    private fun tintRow(row: ItemSourceBinding, color: Int) {
        row.sourceDot.background?.mutate()?.setTint(color)
        row.sourceChip.background?.mutate()?.setTint(color)
        row.sourceEdit.background?.mutate()?.setTint(getColor(R.color.setup_cyan))
        row.sourceRescan.background?.mutate()?.setTint(getColor(R.color.setup_cyan))
        row.sourceDelete.background?.mutate()?.setTint(getColor(R.color.setup_err))
    }
}
