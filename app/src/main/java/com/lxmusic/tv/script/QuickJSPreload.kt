package com.lxmusic.tv.script

/**
 * 洛雪音源运行时桥（参考 lx-music-mobile 的 user-api-preload.js）
 *
 * 在 QuickJS 引擎中为音源脚本提供 globalThis.lx 运行时：
 * - lx.EVENT_NAMES / lx.request / lx.on / lx.send / lx.utils
 * - 异步 HTTP 请求队列：JS 侧登记回调 -> nativeCall 发请求 -> Java 完成 -> __lx_on_response__ 注入
 * - setTimeout / clearTimeout（经 nativeCall schedule_timer 由引擎循环触发）
 * - __lx_dispatch__：Java 派发 request 事件，返回 holder{done,value,error}，Java 侧轮询等待
 *
 * 注意：本文件为纯 JS 文本，不要使用 Kotlin 模板语法（${}）或三引号嵌套。
 */
object QuickJSPreload {
    val SCRIPT: String = """
(function () {
  'use strict';

  // ===== 事件名 =====
  var EVENT_NAMES = {
    request: 'request',
    inited: 'inited',
    updateAlert: 'updateAlert'
  };

  // ===== JS -> Java 桥 =====
  // __lx_native_call__(action, dataJson) 由 Java 层注册
  function nativeCall(action, data) {
    var payload = (typeof data === 'string') ? data : JSON.stringify(data || {});
    if (typeof __lx_native_call__ === 'function') {
      __lx_native_call__(action, payload);
    }
    return null;
  }

  // ===== 异步 HTTP 请求队列 =====
  var requestQueue = {};
  var requestSeq = 0;

  function lxRequest(url, options, callback) {
    var opts = options || {};
    var key = 'r' + (++requestSeq);
    if (typeof callback === 'function') {
      requestQueue[key] = callback;
    }
    nativeCall('request', {
      key: key,
      url: String(url),
      method: (opts.method || 'get'),
      headers: opts.headers || {},
      body: opts.body || '',
      timeout: opts.timeout || 0
    });
    // 返回取消函数（简化实现：不真正取消）
    return function () {};
  }

  // ===== 定时器 =====
  var timerCallbacks = {};
  var timerSeq = 0;

  function lxSetTimeout(callback, delay) {
    var id = ++timerSeq;
    timerCallbacks[id] = callback;
    nativeCall('schedule_timer', { id: id, delay: Math.max(0, Number(delay) || 0) });
    return id;
  }

  function lxClearTimeout(id) {
    delete timerCallbacks[id];
    nativeCall('clear_timer', { id: id });
  }

  globalThis.setTimeout = lxSetTimeout;
  globalThis.clearTimeout = lxClearTimeout;

  // ===== 事件注册 =====
  var events = {};
  var isInited = false;
  var isShowedUpdateAlert = false;

  function lxOn(eventName, handler) {
    if (eventName === EVENT_NAMES.request) {
      events.request = handler;
      return Promise.resolve();
    }
    return Promise.reject(new Error('The event is not supported: ' + eventName));
  }

  function lxSend(eventName, data) {
    if (eventName === EVENT_NAMES.inited) {
      if (isInited) return Promise.resolve();
      isInited = true;
      nativeCall('inited', data || {});
      return Promise.resolve();
    }
    if (eventName === EVENT_NAMES.updateAlert) {
      if (isShowedUpdateAlert) return Promise.resolve();
      isShowedUpdateAlert = true;
      nativeCall('update_alert', data || {});
      return Promise.resolve();
    }
    return Promise.reject(new Error('The event is not supported: ' + eventName));
  }

  // ===== globalThis.lx =====
  globalThis.lx = {
    version: '2.0.0',
    env: 'mobile',
    EVENT_NAMES: EVENT_NAMES,
    request: lxRequest,
    on: lxOn,
    send: lxSend,
    utils: {},
    currentScriptInfo: {}
  };

  // ===== Java -> JS：填充 currentScriptInfo（源脚本元数据，参考洛雪 lx_setup）=====
  // 部分音源（如玉宁熙）会校验 currentScriptInfo.version/name，缺失则报"初始化失败"
  globalThis.__lx_setup__ = function (infoJson) {
    var info = (typeof infoJson === 'string') ? JSON.parse(infoJson) : (infoJson || {});
    globalThis.lx.currentScriptInfo = {
      id: info.id || '',
      name: info.name || '',
      description: info.description || '',
      version: info.version || '',
      author: info.author || '',
      homepage: info.homepage || '',
      rawScript: info.rawScript || ''
    };
    return null;
  };

  // ===== Java -> JS：派发 request 事件（dispatchEvent 使用）=====
  // 调用 events.request(data)，返回 holder{done,value,error}，Java 侧轮询 holder.done
  globalThis.__lx_dispatch__ = function (action, dataJson) {
    var holder = { done: false, value: null, error: null };
    var handler = events.request;
    if (typeof handler !== 'function') {
      holder.done = true;
      holder.error = 'request handler not registered';
      return holder;
    }
    var data = (typeof dataJson === 'string') ? JSON.parse(dataJson) : dataJson;
    var completed = false;
    function finish(value, error) {
      if (completed) return;
      completed = true;
      holder.done = true;
      holder.value = value;
      holder.error = error;
    }
    try {
      var p = handler(data);
      // handler 必须返回 Promise，统一用 Promise.resolve 包装
      Promise.resolve(p).then(
        function (v) { finish(v, null); },
        function (e) { finish(null, String((e && e.message) || e)); }
      );
    } catch (e) {
      finish(null, String((e && e.message) || e));
    }
    return holder;
  };

  // ===== Java -> JS：响应注入（HTTP 完成 / 定时器到期）=====
  globalThis.__lx_on_response__ = function (type, dataJson) {
    var d = (typeof dataJson === 'string') ? JSON.parse(dataJson) : dataJson;
    if (type === 'http') {
      var cb = requestQueue[d.key];
      if (typeof cb === 'function') {
        delete requestQueue[d.key];
        if (d.error) {
          cb(d.error, null, null);
        } else {
          var resp = {
            statusCode: d.statusCode,
            statusMessage: d.statusMessage || '',
            headers: d.headers || {},
            body: d.body
          };
          cb(null, resp, d.body);
        }
      }
    } else if (type === 'timer') {
      var tcb = timerCallbacks[d.id];
      if (typeof tcb === 'function') {
        delete timerCallbacks[d.id];
        tcb();
      }
    }
    return null;
  };

  // ===== Java -> JS：查询 request 处理器是否已注册 =====
  globalThis.__lx_has_handler__ = function (eventName) {
    return typeof events[eventName] === 'function';
  };
})();
    """.trimIndent()
}
