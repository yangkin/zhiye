// 织页云服务 SDK（唯一副本）：页面注入 window.__ZHIYE_CLOUD__ 后加载本文件；Vite 复制到 dist/cloud/sdk.js，由 /cloud/sdk.js 和发布站点的 /__zhiye/sdk.js 提供。
/* 两种模式：
   - 预览：带有时效的应用令牌（30 分钟，过期刷新页面即可）调用 api 域的 /cloud/{appId}
   - 发布站点：同源 /__zhiye/*，不放令牌，身份来自站点登录会话 */
(function () {
  var cfg = window.__ZHIYE_CLOUD__;
  if (!cfg) return;
  var site = !!cfg.site;

  async function call(method, path, body, headers, url) {
    var h = cfg.token ? { Authorization: 'Bearer ' + cfg.token } : {};
    if (site) h['X-Zhiye-Request'] = '1';
    var opt = { method: method, headers: Object.assign(h, headers || {}), credentials: site ? 'same-origin' : 'omit' };
    if (body !== undefined && body !== null && !(body instanceof Blob) && !(body instanceof ArrayBuffer)) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    } else if (body !== undefined && body !== null) {
      opt.body = body;
    }
    var res = await fetch(url || cfg.api + path, opt);
    if (res.status === 204) return null;
    var text = await res.text();
    var data = text;
    try { data = JSON.parse(text); } catch (e) { /* 文件下载返回二进制/文本 */ }
    if (!res.ok) {
      var err = new Error((data && data.message) || ('请求失败（' + res.status + '）'));
      err.code = data && data.code;
      err.status = res.status;
      throw err;
    }
    return data;
  }

  function qs(params) {
    if (!params) return '';
    var s = new URLSearchParams(params).toString();
    return s ? '?' + s : '';
  }

  window.zhiye = window.zhiye || {};
  /* 当前用户：{id, loginName, name, email, admin}；内网公开且没登录时为 null。
     预览里是工作台选择的模拟身份（作者 / 普通员工 / 未登录）。 */
  window.zhiye.user = cfg.user || null;
  window.zhiye.auth = {
    login: function () {
      if (!site) return console.info('[织页] 预览里请用工作台的「预览身份」切换登录状态');
      location.href = '/__zhiye/auth/login?next=' + encodeURIComponent(location.pathname + location.search + location.hash);
    },
    logout: function () {
      if (!site) return console.info('[织页] 预览里请用工作台的「预览身份」切换登录状态');
      location.href = '/__zhiye/auth/logout';
    },
  };
  /* 数据源：平台代理调用已绑定的只读接口，返回接口原始 JSON。 */
  window.zhiye.source = {
    query: async function (key, params) {
      var url = cfg.source ? cfg.source + '/' + encodeURIComponent(key) : cfg.api + '/source/' + encodeURIComponent(key);
      var out = await call('POST', '', { params: params || {} }, null, url);
      return out ? out.data : null;
    },
  };
  /* 应用内 AI：平台代为调用模型，密钥不在页面里。
     chat('一句话') 或 chat({ messages: [{role:'user', content}], system, images: [dataURL], json: true, maxTokens }) → 回复文本 */
  window.zhiye.ai = {
    chat: async function (input) {
      var body = typeof input === 'string' ? { messages: [{ role: 'user', content: input }] } : Object.assign({}, input || {});
      if (!body.messages && body.prompt) body.messages = [{ role: 'user', content: body.prompt }];
      var out = await call('POST', '/ai/chat', body);
      return out ? out.content : '';
    },
  };
  /* 云函数：执行应用源码里的 functions/{name}.ts（服务端运行，身份由平台传入），返回函数的返回值 */
  window.zhiye.fn = {
    call: async function (name, args) {
      var out = await call('POST', '/fn/' + encodeURIComponent(name), { args: args || {} });
      if (out && out.logs && out.logs.length) console.info('[织页云函数 ' + name + ']', out.logs.join('\n'));
      return out ? out.result : null;
    },
  };
  if (cfg.cloud === false || (!site && !cfg.token)) return;
  window.zhiye.cloud = {
    appId: cfg.appId,
    db: {
      /** 列表：{limit, offset, order: 'id'|'-col', after, 字段: 值, 字段__gte/__lte/__gt/__lt/__ne/__like/__in} → {rows, total, next} */
      list: function (table, params) { return call('GET', '/db/' + table + qs(params)); },
      insert: function (table, row) { return call('POST', '/db/' + table, row); },
      update: function (table, id, row) { return call('PATCH', '/db/' + table + '/' + id, row); },
      remove: function (table, id) { return call('DELETE', '/db/' + table + '/' + id); },
      /** 原子加减（库存、计数）：opts = {min, max}，越界时报错不改 */
      increment: function (table, id, column, delta, opts) {
        return call('POST', '/db/' + table + '/' + id + '/increment', Object.assign({ column: column, delta: delta }, opts || {}));
      },
      /** 聚合：{groupBy:[列], count:true, sum:[列], avg:[列], min:[列], max:[列], where:{同 list 的过滤}} → 行数组 */
      aggregate: async function (table, spec) {
        var out = await call('POST', '/db/' + table + '/aggregate', spec || {});
        return out ? out.rows : [];
      },
    },
    storage: {
      upload: function (path, blob) { return call('POST', '/storage?path=' + encodeURIComponent(path), blob); },
      /** 返回可直接给 img/a 用的 blob URL */
      download: async function (path) {
        var res = await fetch(cfg.api + '/storage?path=' + encodeURIComponent(path), {
          headers: cfg.token ? { Authorization: 'Bearer ' + cfg.token } : {},
          credentials: site ? 'same-origin' : 'omit',
        });
        if (!res.ok) throw new Error('文件不存在');
        return URL.createObjectURL(await res.blob());
      },
      list: function () { return call('GET', '/storage'); },
    },
  };
})();
