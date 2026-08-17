package com.lxmusic.tv.service.http

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.lxmusic.tv.data.model.MusicSource
import com.lxmusic.tv.data.source.ScriptParser
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

/**
 * HTTP服务器
 * 用于在TV端提供Web界面，支持手机/PC上传和管理播放源
 */
class HttpServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 5777
        private const val TAG = "HttpServer"
    }

    private var sourceManager: SourceManager? = null
    private var playlistManager: PlaylistManager? = null
    private val scriptParser = ScriptParser()
    // 2.8 Web 端推送搜索文字回调（/api/search 收到文字后调用，主线程由调用方自行调度）
    var onSearchText: ((String) -> Unit)? = null
    // 2.8 Web 端清空搜索框回调（/api/search/clear 调用）
    var onClearSearch: (() -> Unit)? = null

    /**
     * 设置播放源管理器
     */
    fun setSourceManager(manager: SourceManager) {
        this.sourceManager = manager
    }

    /**
     * 设置歌单管理器（Web 端添加收藏歌单）
     */
    fun setPlaylistManager(manager: PlaylistManager) {
        this.playlistManager = manager
    }

    /**
     * 获取服务器访问URL
     */
    fun getAccessUrl(): String {
        val ip = getDeviceIpAddress()
        return "http://$ip:$listeningPort"
    }

    /**
     * 获取设备IP地址
     */
    private fun getDeviceIpAddress(): String {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            if (ip != 0) {
                return Formatter.formatIpAddress(ip)
            }
        } catch (e: Exception) {
            // ignore
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return "0.0.0.0"
    }

    /**
     * 处理HTTP请求
     */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        Log.d(TAG, "收到请求: $method $uri (query: $params)")

        return when {
            uri == "/" || uri == "/index.html" -> serveIndexPage()
            uri == "/api/sources" && method == Method.GET -> serveSourcesList()
            uri == "/api/sources/upload" && method == Method.POST -> handleSourceUpload(session)
            // 注意：NanoHTTPD 的 session.parms 只含 query string，POST form body 需要 parseBody 解析，
            // 因此以下两个 handler 直接接收 session，在内部解析参数
            uri == "/api/sources/delete" && method == Method.POST -> handleSourceDelete(session)
            uri == "/api/sources/enable" && method == Method.POST -> handleSourceEnable(session)
            uri == "/api/sources/platforms" && method == Method.GET -> handleGetSourcePlatforms(params)
            uri == "/api/sources/platforms" && method == Method.POST -> handleSetSourcePlatforms(session)
            // Web 端添加收藏歌单（输入歌单链接）
            uri == "/api/playlists/add" && method == Method.POST -> handleAddPlaylist(session)
            // 2.8 Web 端扫码推送搜索文字（/search 页提交）→ 推送到 TV 搜索输入框
            uri == "/search" -> serveSearchPage()
            uri == "/api/search" && method == Method.POST -> handleSearchSubmit(session)
            uri == "/api/search/clear" && method == Method.POST -> handleSearchClear()
            // 2.8 异常日志导出（GET /log，?download=1 触发下载）
            uri == "/log" -> serveLog(session)
            uri == "/api/status" -> serveStatus()
            uri.startsWith("/static/") -> serveStaticFile(uri)
            else -> serveNotFound()
        }
    }

    /**
     * 解析 POST 请求参数
     *
     * 实测（NanoHTTPD 2.3.1）：urlencoded form body 经 parseBody 解析后
     * **参数写入 session.parms**（不是 files）；multipart 的文件路径才进 files。
     * 因此必须先 parseBody，再从 parms 读取，最后合并 files 中的字段。
     */
    private fun parsePostParams(session: IHTTPSession): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            // parseBody 之后 parms 才包含 form body 参数（query string 参数也在其中）
            session.parms?.forEach { (k, v) -> merged[k] = v }
            Log.d(TAG, "parseBody 完成: files=$files, parms=${session.parms}")
            files.forEach { (k, v) ->
                if (k != "postData") merged[k] = v
            }
        } catch (e: Exception) {
            // parseBody 失败时保留已有参数
            Log.w(TAG, "parseBody 失败: ${e.message}", e)
            session.parms?.forEach { (k, v) -> merged[k] = v }
        }
        Log.d(TAG, "parsePostParams 结果: $merged")
        return merged
    }

    /**
     * 服务首页
     */
    private fun serveIndexPage(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>LX Music TV - 播放源管理</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        color: #fff; min-height: 100vh; padding: 20px;
                    }
                    .container { max-width: 800px; margin: 0 auto; }
                    header { text-align: center; padding: 30px 0; }
                    h1 {
                        font-size: 2.5em; margin-bottom: 10px;
                        background: linear-gradient(90deg, #ff6b6b, #feca57);
                        -webkit-background-clip: text; -webkit-text-fill-color: transparent;
                    }
                    .subtitle { color: #888; font-size: 1.1em; }
                    .card {
                        background: rgba(255, 255, 255, 0.1); border-radius: 15px;
                        padding: 25px; margin-bottom: 20px; backdrop-filter: blur(10px);
                    }
                    .upload-area {
                        border: 2px dashed rgba(255, 255, 255, 0.3); border-radius: 10px;
                        padding: 40px; text-align: center; cursor: pointer; transition: all 0.3s ease;
                    }
                    .upload-area:hover { border-color: #ff6b6b; background: rgba(255, 107, 107, 0.1); }
                    .upload-area.dragover { border-color: #4ecdc4; background: rgba(78, 205, 196, 0.1); }
                    .upload-icon { font-size: 48px; margin-bottom: 15px; }
                    .source-list { list-style: none; }
                    .source-item {
                        display: flex; align-items: center; justify-content: space-between;
                        padding: 15px; border-bottom: 1px solid rgba(255, 255, 255, 0.1);
                    }
                    .source-item:last-child { border-bottom: none; }
                    .source-info { flex: 1; }
                    .source-name { font-size: 1.1em; font-weight: 500; margin-bottom: 5px; }
                    .source-meta { color: #888; font-size: 0.9em; }
                    .source-actions { display: flex; gap: 10px; }
                    .btn {
                        padding: 8px 16px; border: none; border-radius: 5px;
                        cursor: pointer; font-size: 0.9em; transition: all 0.3s ease;
                    }
                    .btn-primary { background: #ff6b6b; color: white; }
                    .btn-primary:hover { background: #ff5252; }
                    .btn-secondary { background: rgba(255, 255, 255, 0.2); color: white; }
                    .btn-danger { background: #e74c3c; color: white; }
                    .btn-danger:hover { background: #c0392b; }
                    .btn-platform { background: rgba(79, 195, 247, 0.25); color: #4fc3f7; }
                    .btn-platform:hover { background: rgba(79, 195, 247, 0.4); }
                    .platform-tag {
                        display: inline-block; padding: 2px 8px; margin-right: 6px;
                        border-radius: 10px; font-size: 0.78em;
                        background: rgba(79, 195, 247, 0.15); color: #4fc3f7;
                    }
                    .modal-overlay {
                        display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%;
                        background: rgba(0, 0, 0, 0.6); z-index: 100; align-items: center; justify-content: center;
                    }
                    .modal-overlay.show { display: flex; }
                    .modal {
                        background: #1a1a2e; border-radius: 12px; padding: 25px; width: 420px; max-width: 90vw;
                        border: 1px solid rgba(255, 255, 255, 0.15);
                    }
                    .modal h3 { margin-bottom: 6px; font-size: 1.2em; }
                    .modal .modal-sub { color: #888; font-size: 0.85em; margin-bottom: 18px; }
                    .platform-check {
                        display: flex; align-items: center; gap: 12px; padding: 12px 14px; margin-bottom: 8px;
                        background: rgba(255, 255, 255, 0.06); border-radius: 8px; cursor: pointer; user-select: none;
                    }
                    .platform-check:hover { background: rgba(255, 255, 255, 0.12); }
                    .platform-check .checkbox {
                        width: 20px; height: 20px; border-radius: 4px; border: 2px solid #666; flex-shrink: 0;
                        display: flex; align-items: center; justify-content: center; font-size: 13px; color: white;
                    }
                    .platform-check.checked .checkbox { background: #ff6b6b; border-color: #ff6b6b; }
                    .modal-actions { display: flex; gap: 12px; margin-top: 18px; }
                    .modal-actions .btn { flex: 1; padding: 10px; }
                    .status-bar {
                        display: flex; justify-content: space-between; align-items: center;
                        padding: 15px; background: rgba(0, 0, 0, 0.2); border-radius: 10px; margin-bottom: 20px;
                    }
                    .status-indicator { display: flex; align-items: center; gap: 10px; }
                    .status-dot { width: 10px; height: 10px; border-radius: 50%; background: #4ecdc4; }
                    .empty-state { text-align: center; padding: 40px; color: #888; }
                    .empty-state-icon { font-size: 48px; margin-bottom: 15px; }
                    .playlist-add-row { display: flex; gap: 10px; align-items: center; }
                    .text-input {
                        flex: 1; padding: 12px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.2);
                        background: rgba(255,255,255,0.08); color: #fff; font-size: 0.95em; outline: none;
                    }
                    .text-input:focus { border-color: #ff6b6b; }
                    .text-input::placeholder { color: #666; }
                    .playlist-result { margin-top: 12px; padding: 12px; border-radius: 8px; font-size: 0.9em; display: none; }
                    .playlist-result.success { display: block; background: rgba(78,205,196,0.15); color: #4ecdc4; }
                    .playlist-result.error { display: block; background: rgba(255,107,107,0.15); color: #ff6b6b; }
                    footer { text-align: center; padding: 20px; color: #666; font-size: 0.9em; }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>LX Music TV</h1>
                        <p class="subtitle">播放源管理面板</p>
                    </header>

                    <div class="status-bar">
                        <div class="status-indicator">
                            <div class="status-dot"></div>
                            <span>服务器运行中</span>
                        </div>
                        <div>
                            <span id="source-count">0</span> 个播放源
                        </div>
                    </div>

                    <div class="card">
                        <h2 style="margin-bottom: 20px;">上传播放源</h2>
                        <div class="upload-area" id="upload-area">
                            <div class="upload-icon">文件</div>
                            <p>点击或拖拽JS文件到此处上传</p>
                            <p style="color: #888; margin-top: 10px;">支持洛雪音乐播放源格式 (.js)</p>
                            <input type="file" id="file-input" accept=".js" style="display: none;">
                        </div>
                    </div>

                    <div class="card">
                        <h2 style="margin-bottom: 20px;">添加收藏歌单</h2>
                        <p style="color: #888; margin-bottom: 15px; font-size: 0.9em;">
                            粘贴歌单链接，点击添加即可收藏到 TV 端（支持 QQ音乐 / 网易云 / 酷狗 / 酷我 / 咪咕）
                        </p>
                        <div class="playlist-add-row">
                            <input type="text" id="playlist-url" class="text-input"
                                placeholder="例如: https://music.163.com/#/playlist?id=3778678"
                                onkeydown="if(event.key==='Enter') addPlaylist()">
                            <button class="btn btn-primary" onclick="addPlaylist()" style="white-space: nowrap;">添加歌单</button>
                        </div>
                        <div id="playlist-result" class="playlist-result"></div>
                        <p style="color: #888; margin-top: 12px; font-size: 0.85em;">
                            支持链接格式：<br>
                            QQ音乐：y.qq.com/n/ryqq/playlist/{id} 或 i.y.qq.com/n2/m/share/details/taoge.html?id={id}<br>
                            网易云：music.163.com/#/playlist?id={id} 或 y.music.163.com/m/playlist?id={id}<br>
                            酷狗：kugou.com/yy/special/single/{id}.html<br>
                            酷我：kuwo.cn/playlist_detail/{id}<br>
                            咪咕：music.migu.cn/v3/music/playlist/{id}
                        </p>
                    </div>

                    <div class="card">
                        <h2 style="margin-bottom: 20px;">已导入的播放源</h2>
                        <ul class="source-list" id="source-list"></ul>
                        <div class="empty-state" id="empty-state">
                            <div class="empty-state-icon">空</div>
                            <p>暂无播放源，请上传JS文件</p>
                        </div>
                    </div>

                    <footer>
                        <p>LX Music TV - Android TV 音乐播放器</p>
                        <p style="margin-top: 5px;">访问地址: <span id="access-url"></span></p>
                    </footer>
                </div>

                <!-- 平台配置弹窗 -->
                <div class="modal-overlay" id="platform-modal">
                    <div class="modal">
                        <h3>平台配置</h3>
                        <div class="modal-sub" id="platform-modal-source"></div>
                        <div class="modal-sub">勾选该播放源生效的平台；全部不勾选 = 对所有平台生效</div>
                        <div id="platform-list"></div>
                        <div class="modal-actions">
                            <button class="btn btn-secondary" onclick="closePlatformModal()">取消</button>
                            <button class="btn btn-primary" onclick="savePlatforms()">保存</button>
                        </div>
                    </div>
                </div>

                <script>
                    document.addEventListener('DOMContentLoaded', function() {
                        loadSources();
                        updateAccessUrl();
                        var uploadArea = document.getElementById('upload-area');
                        var fileInput = document.getElementById('file-input');
                        uploadArea.addEventListener('click', function() { fileInput.click(); });
                        uploadArea.addEventListener('dragover', function(e) {
                            e.preventDefault(); uploadArea.classList.add('dragover');
                        });
                        uploadArea.addEventListener('dragleave', function() {
                            uploadArea.classList.remove('dragover');
                        });
                        uploadArea.addEventListener('drop', function(e) {
                            e.preventDefault(); uploadArea.classList.remove('dragover');
                            if (e.dataTransfer.files.length > 0) uploadFile(e.dataTransfer.files[0]);
                        });
                        fileInput.addEventListener('change', function(e) {
                            if (e.target.files.length > 0) uploadFile(e.target.files[0]);
                        });
                    });

                    async function loadSources() {
                        try {
                            var response = await fetch('/api/sources');
                            var sources = await response.json();
                            var sourceList = document.getElementById('source-list');
                            var emptyState = document.getElementById('empty-state');
                            var sourceCount = document.getElementById('source-count');
                            sourceCount.textContent = sources.length;
                            if (sources.length === 0) {
                                sourceList.style.display = 'none';
                                emptyState.style.display = 'block';
                            } else {
                                sourceList.style.display = 'block';
                                emptyState.style.display = 'none';
                                sourceList.innerHTML = sources.map(function(s) {
                                    var platformTags = '';
                                    if (s.platforms && s.platforms.length > 0) {
                                        s.platforms.forEach(function(p) {
                                            platformTags += '<span class="platform-tag">' + platformNames[p] + '</span>';
                                        });
                                    } else {
                                        platformTags = '<span class="platform-tag">全部平台</span>';
                                    }
                                    return '<li class="source-item">' +
                                        '<div class="source-info">' +
                                        '<div class="source-name">' + s.name + '</div>' +
                                        '<div class="source-meta">' +
                                        (s.description || '') +
                                        (s.version ? ' v' + s.version : '') +
                                        (s.author ? ' by ' + s.author : '') +
                                        '</div>' +
                                        '<div style="margin-top: 6px;">' + platformTags + '</div>' +
                                        '</div>' +
                                        '<div class="source-actions">' +
                                        '<button class="btn btn-platform" data-action="platform" data-id="' + s.id + '" ' +
                                        'data-name="' + escapeAttr(s.name) + '" data-platforms="' + escapeAttr(JSON.stringify(s.platforms || [])) + '">平台</button>' +
                                        '<button class="btn ' + (s.isEnabled ? 'btn-secondary' : 'btn-primary') + '" ' +
                                        'data-action="toggle" data-id="' + s.id + '" data-enable="' + (!s.isEnabled) + '">' +
                                        (s.isEnabled ? '已启用' : '启用') + '</button>' +
                                        '<button class="btn btn-danger" data-action="delete" data-id="' + s.id + '">删除</button>' +
                                        '</div></li>';
                                }).join('');
                            }
                        } catch (error) {
                            console.error('加载播放源失败:', error);
                        }
                    }

                    async function uploadFile(file) {
                        if (!file.name.endsWith('.js')) {
                            alert('请选择JS格式的播放源文件'); return;
                        }
                        var formData = new FormData();
                        formData.append('file', file);
                        try {
                            var response = await fetch('/api/sources/upload', { method: 'POST', body: formData });
                            var result = await response.json();
                            if (result.success) {
                                alert('上传成功: ' + result.message); loadSources();
                            } else {
                                alert('上传失败: ' + result.message);
                            }
                        } catch (error) {
                            alert('上传失败: ' + error.message);
                        }
                    }

                    async function toggleSource(id, enable) {
                        try {
                            var response = await fetch('/api/sources/enable', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'id=' + encodeURIComponent(id) + '&enable=' + enable
                            });
                            var result = await response.json();
                            if (result.success) loadSources(); else alert('操作失败: ' + result.message);
                        } catch (error) { alert('操作失败: ' + error.message); }
                    }

                    async function deleteSource(id) {
                        if (!confirm('确定要删除这个播放源吗？')) return;
                        try {
                            var response = await fetch('/api/sources/delete', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'id=' + encodeURIComponent(id)
                            });
                            var result = await response.json();
                            if (result.success) loadSources(); else alert('删除失败: ' + result.message);
                        } catch (error) { alert('删除失败: ' + error.message); }
                    }

                    function updateAccessUrl() {
                        document.getElementById('access-url').textContent = window.location.href;
                    }

                    // ===== 播放源列表按钮事件委托（避免内联 onclick 引号问题）=====
                    function escapeAttr(s) {
                        return String(s == null ? '' : s)
                            .replace(/&/g, '&amp;')
                            .replace(/"/g, '&quot;')
                            .replace(/'/g, '&#39;')
                            .replace(/</g, '&lt;');
                    }

                    document.getElementById('source-list').addEventListener('click', function(e) {
                        var btn = e.target.closest('button[data-action]');
                        if (!btn) return;
                        var action = btn.getAttribute('data-action');
                        var id = btn.getAttribute('data-id');
                        if (action === 'toggle') {
                            toggleSource(id, btn.getAttribute('data-enable') === 'true');
                        } else if (action === 'delete') {
                            deleteSource(id);
                        } else if (action === 'platform') {
                            var platforms = [];
                            try { platforms = JSON.parse(btn.getAttribute('data-platforms') || '[]'); } catch (err) {}
                            openPlatformModal(id, btn.getAttribute('data-name') || '', platforms);
                        }
                    });

                    // ===== 平台配置 =====
                    var platformNames = { kw: '酷我', kg: '酷狗', tx: 'QQ音乐', wy: '网易云', mg: '咪咕' };
                    var platformKeys = ['kw', 'kg', 'tx', 'wy', 'mg'];
                    var currentPlatformSourceId = null;
                    var currentPlatformSelection = [];

                    function openPlatformModal(id, name, platforms) {
                        currentPlatformSourceId = id;
                        currentPlatformSelection = (platforms || []).slice();
                        document.getElementById('platform-modal-source').textContent = name;
                        renderPlatformList();
                        document.getElementById('platform-modal').classList.add('show');
                    }

                    function closePlatformModal() {
                        document.getElementById('platform-modal').classList.remove('show');
                    }

                    function renderPlatformList() {
                        var html = '';
                        platformKeys.forEach(function(key) {
                            var checked = currentPlatformSelection.indexOf(key) >= 0;
                            html += '<div class="platform-check' + (checked ? ' checked' : '') + '" ' +
                                'onclick="togglePlatform(\'' + key + '\')">' +
                                '<div class="checkbox">' + (checked ? '✓' : '') + '</div>' +
                                '<span>' + platformNames[key] + '</span></div>';
                        });
                        document.getElementById('platform-list').innerHTML = html;
                    }

                    function togglePlatform(key) {
                        var idx = currentPlatformSelection.indexOf(key);
                        if (idx >= 0) currentPlatformSelection.splice(idx, 1);
                        else currentPlatformSelection.push(key);
                        renderPlatformList();
                    }

                    async function savePlatforms() {
                        if (!currentPlatformSourceId) {
                            alert('请先选择要配置的播放源');
                            return;
                        }
                        try {
                            var response = await fetch('/api/sources/platforms', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'id=' + encodeURIComponent(currentPlatformSourceId) +
                                      '&platforms=' + encodeURIComponent(currentPlatformSelection.join(','))
                            });
                            var result = await response.json();
                            if (result.success) {
                                closePlatformModal();
                                loadSources();
                                alert('平台配置已保存');
                            } else {
                                alert('保存失败: ' + result.message);
                            }
                        } catch (error) { alert('保存失败: ' + error.message); }
                    }

                    // 点击遮罩层关闭
                    document.addEventListener('DOMContentLoaded', function() {
                        var overlay = document.getElementById('platform-modal');
                        overlay.addEventListener('click', function(e) {
                            if (e.target === overlay) closePlatformModal();
                        });
                    });

                    // ===== 添加收藏歌单 =====
                    async function addPlaylist() {
                        var input = document.getElementById('playlist-url');
                        var result = document.getElementById('playlist-result');
                        var url = (input.value || '').trim();
                        if (!url) {
                            result.className = 'playlist-result error';
                            result.textContent = '请先输入歌单链接';
                            return;
                        }
                        result.className = 'playlist-result';
                        result.textContent = '正在添加，请稍候...';
                        result.style.display = 'block';
                        try {
                            var response = await fetch('/api/playlists/add', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'url=' + encodeURIComponent(url)
                            });
                            var res = await response.json();
                            if (res.success) {
                                result.className = 'playlist-result success';
                                result.textContent = '✓ ' + res.message;
                                input.value = '';
                            } else {
                                result.className = 'playlist-result error';
                                result.textContent = '✗ ' + res.message;
                            }
                        } catch (error) {
                            result.className = 'playlist-result error';
                            result.textContent = '✗ 添加失败: ' + error.message;
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    /**
     * 服务播放源列表
     */
    private fun serveSourcesList(): Response {
        val sources = sourceManager?.getAllSources() ?: emptyList()
        // 2.8 与 TV 端管理页一致：启用的源按启用顺序排前（优先级高到低），未启用的按导入顺序排后
        val ordered = sources.filter { it.isEnabled }.sortedWith(compareBy { it.enabledAt ?: it.updatedAt }) +
                sources.filter { !it.isEnabled }
        val json = ordered.map { source ->
            mapOf(
                "id" to source.id,
                "name" to source.name,
                "description" to source.description,
                "version" to source.version,
                "author" to source.author,
                "homepage" to source.homepage,
                "isEnabled" to source.isEnabled,
                // 平台配置：空 = 全部平台
                "platforms" to (sourceManager?.getSourcePlatforms(source.id) ?: emptySet<String>()).toList()
            )
        }.let { com.lxmusic.tv.util.JsonUtil.toJson(it) }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * 处理播放源上传
     */
    private fun handleSourceUpload(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)

            // NanoHTTPD multipart上传: 文件key是表单字段名"file"，值是临时文件路径
            // 普通POST数据: key是"postData"
            val tempFilePath = files["file"]
                ?: files["content"]
                ?: files["postData"]
                ?: return createErrorResponse("没有收到文件")

            val scriptContent = if (tempFilePath.startsWith("/")) {
                java.io.File(tempFilePath).readText(Charsets.UTF_8)
            } else {
                tempFilePath
            }

            // 解析脚本
            val parseResult = scriptParser.parse(scriptContent)
            when (parseResult) {
                is com.lxmusic.tv.data.source.ParseResult.Success -> {
                    val source = MusicSource(
                        id = UUID.randomUUID().toString(),
                        name = parseResult.metadata.name,
                        description = parseResult.metadata.description,
                        version = parseResult.metadata.version,
                        author = parseResult.metadata.author,
                        homepage = parseResult.metadata.homepage,
                        scriptContent = scriptContent
                    )
                    sourceManager?.addSource(source)
                    createSuccessResponse("播放源 '${source.name}' 上传成功")
                }
                is com.lxmusic.tv.data.source.ParseResult.Error -> {
                    createErrorResponse(parseResult.message)
                }
            }
        } catch (e: Exception) {
            createErrorResponse("上传失败: ${e.message}")
        }
    }

    /**
     * 处理播放源删除
     */
    private fun handleSourceDelete(session: IHTTPSession): Response {
        val params = parsePostParams(session)
        val id = params["id"] ?: run {
            Log.w(TAG, "删除播放源失败: 缺少播放源ID, 解析参数=$params, sourceManager为空=${sourceManager == null}")
            return createErrorResponse("缺少播放源ID")
        }
        Log.d(TAG, "删除播放源: id=$id")
        val result = sourceManager?.deleteSource(id)
        return if (result == true) {
            createSuccessResponse("删除成功")
        } else {
            Log.w(TAG, "删除播放源失败: id=$id, 结果=$result, sourceManager为空=${sourceManager == null}")
            createErrorResponse("删除失败，播放源不存在")
        }
    }

    /**
     * 处理播放源启用/禁用
     */
    private fun handleSourceEnable(session: IHTTPSession): Response {
        val params = parsePostParams(session)
        val id = params["id"] ?: run {
            Log.w(TAG, "启用/禁用播放源失败: 缺少播放源ID, 解析参数=$params, sourceManager为空=${sourceManager == null}")
            return createErrorResponse("缺少播放源ID")
        }
        val enable = params["enable"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "启用/禁用播放源失败: 缺少启用参数, 解析参数=$params")
            return createErrorResponse("缺少启用参数")
        }
        Log.d(TAG, "启用/禁用播放源: id=$id enable=$enable, sourceManager为空=${sourceManager == null}")
        val result = sourceManager?.setSourceEnabled(id, enable)
        return if (result == true) {
            createSuccessResponse(if (enable) "已启用" else "已禁用")
        } else {
            Log.w(TAG, "启用/禁用播放源失败: id=$id enable=$enable, 结果=$result")
            createErrorResponse("操作失败，播放源不存在")
        }
    }

    /**
     * 获取播放源平台配置（GET /api/sources/platforms?id=xxx）
     */
    private fun handleGetSourcePlatforms(params: Map<String, String>): Response {
        val id = params["id"] ?: return createErrorResponse("缺少播放源ID")
        val platforms = sourceManager?.getSourcePlatforms(id) ?: emptySet()
        val json = com.lxmusic.tv.util.JsonUtil.toJson(mapOf("platforms" to platforms.toList()))
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * 设置播放源平台配置（POST /api/sources/platforms，body: id=xxx&platforms=kw,kg,tx）
     */
    private fun handleSetSourcePlatforms(session: IHTTPSession): Response {
        val params = parsePostParams(session)
        val id = params["id"] ?: run {
            Log.w(TAG, "设置平台配置失败: 缺少播放源ID, 解析参数=$params, sourceManager为空=${sourceManager == null}")
            return createErrorResponse("缺少播放源ID")
        }
        val platforms = (params["platforms"] ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        Log.d(TAG, "设置平台配置: id=$id platforms=$platforms, sourceManager为空=${sourceManager == null}")
        val result = sourceManager?.setSourcePlatforms(id, platforms)
        return if (result == true) {
            createSuccessResponse("平台配置已保存")
        } else {
            Log.w(TAG, "设置平台配置失败: id=$id platforms=$platforms, 结果=$result")
            createErrorResponse("操作失败，播放源不存在")
        }
    }

    /**
     * Web 端添加收藏歌单（POST /api/playlists/add，body: url=xxx）
     * 解析歌单链接 → 平台 + 歌单ID → 拉取歌单详情 → 加入收藏
     */
    private fun handleAddPlaylist(session: IHTTPSession): Response {
        val params = parsePostParams(session)
        val url = params["url"]?.trim() ?: run {
            Log.w(TAG, "添加歌单失败: 缺少歌单链接, 解析参数=$params, playlistManager为空=${playlistManager == null}")
            return createErrorResponse("缺少歌单链接")
        }
        if (url.isEmpty()) return createErrorResponse("歌单链接不能为空")
        Log.d(TAG, "Web添加歌单: url=$url, playlistManager为空=${playlistManager == null}")
        val result = playlistManager?.addPlaylistByUrl(url)
        return if (result != null && result.success) {
            createSuccessResponse(result.message)
        } else {
            Log.w(TAG, "Web添加歌单失败: url=$url, 结果=$result")
            createErrorResponse(result?.message ?: "添加失败：歌单管理器未初始化")
        }
    }

    /**
     * 2.8 Web 端搜索推送页（/search）：手机/PC 浏览器输入文字 → 推送到 TV 搜索输入框。
     * 与源管理页面（/）分离，扫码直达本页。
     */
    private fun serveSearchPage(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>搜索推送 - TV</title>
                <style>
                    body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; background:#f5f5f7; margin:0; display:flex; align-items:center; justify-content:center; min-height:100vh; }
                    .card { background:#fff; border-radius:16px; padding:32px 36px; width:min(520px, 90vw); box-shadow:0 8px 30px rgba(0,0,0,.08); text-align:center; }
                    h1 { font-size:22px; margin:0 0 6px; color:#1d1d1f; }
                    p { color:#86868b; font-size:14px; margin:0 0 20px; }
                    input { width:100%; box-sizing:border-box; padding:14px 16px; font-size:18px; border:2px solid #d2d2d7; border-radius:10px; outline:none; }
                    input:focus { border-color:#e94560; }
                    button { margin-top:16px; width:100%; padding:14px; font-size:17px; font-weight:600; color:#fff; background:#e94560; border:none; border-radius:10px; cursor:pointer; }
                    button:active { opacity:.8; }
                    .tip { margin-top:14px; font-size:13px; color:#86868b; }
                    .ok { color:#0a7a3d; }
                    .err { color:#c41d1d; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>搜索推送</h1>
                    <p>输入内容推送到电视端搜索框</p>
                    <input id="kw" type="text" placeholder="输入歌曲名、歌手、歌单关键词..." maxlength="40" autofocus>
                    <button id="btn" onclick="send()">推送到电视</button>
                    <button id="clr" style="margin-top:10px;background:#86868b;" onclick="clearKw()">清空电视搜索框</button>
                    <div class="tip" id="msg"></div>
                </div>
                <script>
                    async function send() {
                        var v = document.getElementById('kw').value.trim();
                        var msg = document.getElementById('msg');
                        if (!v) { msg.textContent = '请输入内容'; msg.className = 'tip err'; return; }
                        msg.textContent = '发送中...'; msg.className = 'tip';
                        try {
                            var r = await fetch('/api/search', { method: 'POST', body: 'text=' + encodeURIComponent(v), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
                            var j = await r.json();
                            if (j.success) { msg.textContent = '已推送到电视 ✓'; msg.className = 'tip ok'; document.getElementById('kw').value = ''; document.getElementById('kw').focus(); }
                            else { msg.textContent = '推送失败：' + (j.message || '未知错误'); msg.className = 'tip err'; }
                        } catch (e) { msg.textContent = '网络错误'; msg.className = 'tip err'; }
                    }
                    async function clearKw() {
                        var msg = document.getElementById('msg');
                        msg.textContent = '清空中...'; msg.className = 'tip';
                        try {
                            var r = await fetch('/api/search/clear', { method: 'POST' });
                            var j = await r.json();
                            msg.textContent = j.success ? '已清空电视搜索框 ✓' : '清空失败：' + (j.message || '未知错误');
                            msg.className = j.success ? 'tip ok' : 'tip err';
                        } catch (e) { msg.textContent = '网络错误'; msg.className = 'tip err'; }
                    }
                    document.getElementById('kw').addEventListener('keydown', function(e) { if (e.key === 'Enter') send(); });
                </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
    }

    /**
     * 2.8 接收 Web 端推送的搜索文字（POST /api/search，body: text=关键词）
     */
    private fun handleSearchSubmit(session: IHTTPSession): Response {
        val params = parsePostParams(session)
        val text = params["text"]?.trim() ?: return createErrorResponse("缺少搜索文字")
        if (text.isEmpty()) return createErrorResponse("搜索文字不能为空")
        Log.d(TAG, "Web推送搜索文字: $text")
        onSearchText?.invoke(text)
        return createSuccessResponse("已推送到电视")
    }

    /**
     * 2.8 清空 TV 搜索框（POST /api/search/clear）
     */
    private fun handleSearchClear(): Response {
        Log.d(TAG, "Web清空搜索框")
        onClearSearch?.invoke()
        return createSuccessResponse("已清空")
    }

    /**
     * 服务状态信息
     */
    private fun serveStatus(): Response {
        val status = mapOf(
            "server" to "running",
            "port" to listeningPort,
            "accessUrl" to getAccessUrl(),
            "sourceCount" to (sourceManager?.getAllSources()?.size ?: 0)
        )
        val json = com.lxmusic.tv.util.JsonUtil.toJson(status)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /**
     * 服务静态文件
     */
    private fun serveStaticFile(uri: String): Response {
        return try {
            val fileName = uri.removePrefix("/static/")
            val inputStream = context.assets.open("web/$fileName")
            val mimeType = getMimeType(fileName)
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: IOException) {
            serveNotFound()
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".html") -> "text/html"
            fileName.endsWith(".css") -> "text/css"
            fileName.endsWith(".js") -> "application/javascript"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun serveNotFound(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
    }

    /**
     * 2.8 异常日志导出（GET /log）
     * 导出内容：历史崩溃日志（crash.log）+ 本进程实时 logcat（LX-* 等应用日志）。
     * ?download=1 时附加 Content-Disposition 触发浏览器下载。
     */
    private fun serveLog(session: IHTTPSession): Response {
        return try {
            val exportFile = com.lxmusic.tv.data.log.LogExporter.exportToFile(context)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "text/plain; charset=utf-8",
                exportFile.inputStream(),
                exportFile.length()
            )
            if (session.parms?.get("download") == "1") {
                response.addHeader("Content-Disposition", "attachment; filename=\"lx_logs.txt\"")
            }
            response
        } catch (e: Exception) {
            Log.e(TAG, "导出日志失败: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "导出日志失败: ${e.message}"
            )
        }
    }

    private fun createSuccessResponse(message: String): Response {
        val response = mapOf("success" to true, "message" to message)
        val json = com.lxmusic.tv.util.JsonUtil.toJson(response)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun createErrorResponse(message: String): Response {
        val response = mapOf("success" to false, "message" to message)
        val json = com.lxmusic.tv.util.JsonUtil.toJson(response)
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", json)
    }
}

/**
 * 播放源管理器接口
 */
interface SourceManager {
    fun getAllSources(): List<MusicSource>
    fun getSourceById(id: String): MusicSource?
    fun addSource(source: MusicSource): Boolean
    fun deleteSource(id: String): Boolean
    fun setSourceEnabled(id: String, enabled: Boolean): Boolean
    fun getEnabledSource(): MusicSource?

    /**
     * 获取播放源启用的平台 key 集合（空 = 全部平台）
     */
    fun getSourcePlatforms(id: String): Set<String>

    /**
     * 设置播放源启用的平台 key 集合（空 = 全部平台）
     */
    fun setSourcePlatforms(id: String, platforms: Set<String>): Boolean
}

/**
 * 歌单管理器接口（Web 端添加收藏歌单）
 */
interface PlaylistManager {
    /**
     * 按歌单链接添加收藏歌单
     * 解析链接识别平台 + 歌单ID → 拉取歌单详情 → 存入收藏
     * @return 成功/失败结果
     */
    fun addPlaylistByUrl(url: String): PlaylistAddResult
}

/**
 * 歌单添加结果
 */
data class PlaylistAddResult(
    val success: Boolean,
    val message: String
)
