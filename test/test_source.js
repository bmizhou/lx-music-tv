/**
 * @name 测试音乐源
 * @description 这是一个测试用的音乐源脚本
 * @version 1.0.0
 * @author LX Music Test
 * @homepage https://github.com/lyswhut/lx-music-desktop
 */

// 模拟 globalThis.lx 对象
const globalThis = globalThis || {};
globalThis.lx = {
    version: '1.0.0',
    env: 'desktop',
    currentScriptInfo: {
        name: '测试音乐源',
        description: '这是一个测试用的音乐源脚本',
        version: '1.0.0',
        author: 'LX Music Test',
        homepage: 'https://github.com/lyswhut/lx-music-desktop',
        rawScript: ''
    },
    EVENT_NAMES: {
        inited: 'inited',
        request: 'request',
        updateAlert: 'updateAlert'
    },
    on: function(event, handler) {
        console.log('注册事件:', event);
    },
    send: function(event, data) {
        console.log('发送事件:', event, data);
    },
    request: function(url, options, callback) {
        console.log('请求:', url);
        // 模拟请求
        setTimeout(() => {
            callback(null, { body: { url: 'https://example.com/music.mp3' } });
        }, 100);
    },
    utils: {
        buffer: {
            from: function(data) { return data; },
            bufToString: function(buf, format) { return buf.toString(format); }
        },
        crypto: {
            aesEncrypt: function(buffer, mode, key, iv) { return buffer; },
            md5: function(str) { return str; },
            randomBytes: function(size) { return 'random'; },
            rsaEncrypt: function(buffer, key) { return buffer; }
        },
        zlib: {
            inflate: function(buffer) { return Promise.resolve(buffer); },
            deflate: function(buffer) { return Promise.resolve(buffer); }
        }
    }
};

const { EVENT_NAMES, request, on, send } = globalThis.lx;

// 音质配置
const qualitys = {
    kw: {
        '128k': '128',
        '320k': '320',
        flac: 'flac',
        flac24bit: 'flac24bit'
    },
    local: {}
};

// HTTP请求封装
const httpRequest = (url, options) => new Promise((resolve, reject) => {
    request(url, options, (err, resp) => {
        if (err) return reject(err);
        resolve(resp.body);
    });
});

// API实现
const apis = {
    kw: {
        musicUrl({ songmid }, quality) {
            return httpRequest('https://api.example.com/music/url', {
                method: 'GET',
                params: { id: songmid, quality: quality }
            }).then(data => {
                return data.url;
            });
        }
    },
    local: {
        musicUrl(info) {
            return httpRequest('https://api.example.com/local/music/url', {
                method: 'POST',
                body: JSON.stringify(info)
            }).then(data => {
                return data.url;
            });
        },
        pic(info) {
            return httpRequest('https://api.example.com/local/pic', {
                method: 'POST',
                body: JSON.stringify(info)
            }).then(data => {
                return data.url;
            });
        },
        lyric(info) {
            return httpRequest('https://api.example.com/local/lyric', {
                method: 'POST',
                body: JSON.stringify(info)
            }).then(data => {
                return {
                    lyric: data.lyric || '',
                    tlyric: data.tlyric || null,
                    rlyric: data.rlyric || null,
                    lxlyric: data.lxlyric || null
                };
            });
        }
    }
};

// 注册请求事件处理
on(EVENT_NAMES.request, ({ source, action, info }) => {
    switch (action) {
        case 'musicUrl':
            return apis[source]?.musicUrl(info.musicInfo, qualitys[source]?.[info.type])
                .catch(err => {
                    console.error('获取音乐URL失败:', err);
                    return Promise.reject(err);
                }) || Promise.reject(new Error('不支持的源'));
        
        case 'lyric':
            return apis[source]?.lyric(info.musicInfo)
                .catch(err => {
                    console.error('获取歌词失败:', err);
                    return Promise.reject(err);
                }) || Promise.reject(new Error('不支持的源'));
        
        case 'pic':
            return apis[source]?.pic(info.musicInfo)
                .catch(err => {
                    console.error('获取封面失败:', err);
                    return Promise.reject(err);
                }) || Promise.reject(new Error('不支持的源'));
        
        default:
            return Promise.reject(new Error('不支持的操作'));
    }
});

// 脚本初始化完成后发送inited事件
send(EVENT_NAMES.inited, {
    openDevTools: false,
    sources: {
        kw: {
            name: '酷我音乐',
            type: 'music',
            actions: ['musicUrl'],
            qualitys: ['128k', '320k', 'flac', 'flac24bit']
        },
        local: {
            name: '本地音乐',
            type: 'music',
            actions: ['musicUrl', 'lyric', 'pic'],
            qualitys: []
        }
    }
});

console.log('测试音乐源脚本初始化完成');