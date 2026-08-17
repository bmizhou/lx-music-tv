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
  // __lx_native_call__(action, dataJson) 由 Java 层注册；
  // 同步型 action（aes_encrypt/md5）返回结果字符串，异步型返回 null
  function nativeCall(action, data) {
    var payload = (typeof data === 'string') ? data : JSON.stringify(data || {});
    if (typeof __lx_native_call__ === 'function') {
      return __lx_native_call__(action, payload);
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

  // ===== 洛雪兼容 utils（参考 lx-music-mobile user-api-preload.js）=====
  // 六音等混淆源在启用阶段就调用 utils.buffer.from / utils.crypto.aesEncrypt，
  // utils 缺失（空对象）会直接崩在脚本自举（cannot read property 'from' of undefined）。
  // buffer 纯 JS 实现（UTF-8/hex/base64）；AES/MD5 经 nativeCall 走 Java（Cipher/MessageDigest）。

  // UTF-8 字节数组 -> 字符串（手写循环，避免 apply 大数组爆栈）
  function bytesToString(bytes) {
    var result = '';
    var i = 0;
    while (i < bytes.length) {
      var byte = bytes[i];
      if (byte < 128) {
        result += String.fromCharCode(byte);
        i++;
      } else if (byte >= 192 && byte < 224) {
        result += String.fromCharCode(((byte & 31) << 6) | (bytes[i + 1] & 63));
        i += 2;
      } else {
        result += String.fromCharCode(((byte & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63));
        i += 3;
      }
    }
    return result;
  }

  // 字符串 -> UTF-8 字节数组
  function stringToBytes(inputString) {
    var bytes = [];
    for (var i = 0; i < inputString.length; i++) {
      var charCode = inputString.charCodeAt(i);
      if (charCode < 128) {
        bytes.push(charCode);
      } else if (charCode < 2048) {
        bytes.push((charCode >> 6) | 192);
        bytes.push((charCode & 63) | 128);
      } else {
        bytes.push((charCode >> 12) | 224);
        bytes.push(((charCode >> 6) & 63) | 128);
        bytes.push((charCode & 63) | 128);
      }
    }
    return bytes;
  }

  var B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  // base64 -> 字节数组（纯 JS，QuickJS 无 atob）
  function base64ToBytes(b64) {
    b64 = String(b64).replace(/[^A-Za-z0-9+/=]/g, '');
    var bytes = [];
    var buffer = 0;
    var bits = 0;
    for (var i = 0; i < b64.length; i++) {
      if (b64[i] === '=') break;
      var val = B64_CHARS.indexOf(b64[i]);
      if (val < 0) continue;
      buffer = (buffer << 6) | val;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        bytes.push((buffer >> bits) & 0xFF);
      }
    }
    return bytes;
  }

  // 字节数组 -> base64（纯 JS）
  function bytesToBase64(bytes) {
    var out = '';
    var i;
    for (i = 0; i + 2 < bytes.length; i += 3) {
      out += B64_CHARS[(bytes[i] >> 2) & 63];
      out += B64_CHARS[((bytes[i] & 3) << 4) | (bytes[i + 1] >> 4)];
      out += B64_CHARS[((bytes[i + 1] & 15) << 2) | (bytes[i + 2] >> 6)];
      out += B64_CHARS[bytes[i + 2] & 63];
    }
    if (i + 1 === bytes.length) {
      out += B64_CHARS[(bytes[i] >> 2) & 63];
      out += B64_CHARS[(bytes[i] & 3) << 4];
      out += '==';
    } else if (i + 2 === bytes.length) {
      out += B64_CHARS[(bytes[i] >> 2) & 63];
      out += B64_CHARS[((bytes[i] & 3) << 4) | (bytes[i + 1] >> 4)];
      out += B64_CHARS[(bytes[i + 1] & 15) << 2];
      out += '=';
    }
    return out;
  }

  // Buffer/字符串 -> base64（crypto 入参统一走这里，与洛雪 dataToB64 一致）
  function dataToB64(data) {
    if (typeof data === 'string') return bytesToBase64(stringToBytes(data));
    if (Array.isArray(data) || (data && data.length != null)) return bytesToBase64(Array.prototype.slice.call(data));
    throw new Error('data type error: ' + typeof data);
  }

  var lxUtils = {
    // buffer 完整实现（洛雪移动版语义）：六音源在自举阶段会直接调用 utils.buffer.from，
    // v243 曾改为「存在但调用即抛错」导致六音源启用失败，已回退为完整实现
    buffer: {
      from: function (input, encoding) {
        if (typeof input === 'string') {
          switch (encoding) {
            case 'binary':
              throw new Error('Binary encoding is not supported for input strings');
            case 'base64':
              return new Uint8Array(base64ToBytes(input));
            case 'hex':
              return new Uint8Array(input.match(/.{1,2}/g).map(function (byte) { return parseInt(byte, 16); }));
            default:
              return new Uint8Array(stringToBytes(input));
          }
        } else if (Array.isArray(input)) {
          return new Uint8Array(input);
        } else {
          throw new Error('Unsupported input type: ' + input + ' encoding: ' + encoding);
        }
      },
      bufToString: function (buf, format) {
        if (Array.isArray(buf) || (buf && buf.length != null)) {
          var arr = Array.prototype.slice.call(buf);
          switch (format) {
            case 'binary':
              return buf;
            case 'hex':
              return arr.reduce(function (str, byte) { return str + (byte < 16 ? '0' : '') + byte.toString(16); }, '');
            case 'base64':
              return bytesToBase64(arr);
            case 'utf8':
            case 'utf-8':
            default:
              return bytesToString(arr);
          }
        } else {
          throw new Error('Input is not a valid buffer: ' + buf + ' format: ' + format);
        }
      }
    },
    crypto: {
      aesEncrypt: function (buffer, mode, key, iv) {
        switch (mode) {
          case 'aes-128-cbc':
            return new Uint8Array(base64ToBytes(nativeCall('aes_encrypt', {
              data: dataToB64(buffer),
              key: dataToB64(key),
              iv: dataToB64(iv),
              mode: 'cbc'
            })));
          case 'aes-128-ecb':
            return new Uint8Array(base64ToBytes(nativeCall('aes_encrypt', {
              data: dataToB64(buffer),
              key: dataToB64(key),
              iv: '',
              mode: 'ecb'
            })));
          default:
            throw new Error('Binary encoding is not supported for input strings');
        }
      },
      md5: function (str) {
        if (typeof str !== 'string') throw new Error('param required a string');
        // 与洛雪移动版一致：先 encodeURIComponent 再求 MD5
        return nativeCall('md5', { str: encodeURIComponent(str) });
      },
      randomBytes: function (size) {
        var byteArray = new Uint8Array(size);
        for (var i = 0; i < size; i++) {
          byteArray[i] = Math.floor(Math.random() * 256);
        }
        return byteArray;
      }
    }
  };

  // ===== globalThis.lx =====
  globalThis.lx = {
    version: '2.0.0',
    env: 'mobile',
    EVENT_NAMES: EVENT_NAMES,
    request: lxRequest,
    on: lxOn,
    send: lxSend,
    utils: lxUtils,
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
          // 洛雪桌面版 needle 语义：body 尝试 JSON.parse（成功为对象，失败保持原始字符串）。
          // 六音等源在回调里直接访问 resp.body.xxx，body 为字符串时会取到 undefined 导致校验失败
          var body = d.body;
          try { body = JSON.parse(body); } catch (e) {}
          var resp = {
            statusCode: d.statusCode,
            statusMessage: d.statusMessage || '',
            headers: d.headers || {},
            body: body
          };
          cb(null, resp, body);
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
