/* 织页预览编辑桥：只注入在预览构建中。协议见 docs/api-contract.md 第 4 节。 */
(function () {
  'use strict'
  if (window.__zhiyeBridge) return
  window.__zhiyeBridge = true
  var CHANNEL = 'zhiye-editor'
  var doc = document
  var enabled = false
  var selected = null
  var hover = null
  var editing = null
  var patches = []
  var originals = new Map() // element -> { text: [textNode values], style, href, display }
  var observer = null
  var scheduled = 0
  var applying = false

  function send(type, data) {
    var msg = { channel: CHANNEL, type: type }
    if (data) for (var k in data) msg[k] = data[k]
    try { window.parent.postMessage(msg, '*') } catch (e) { /* ignore */ }
  }
  function isUi(el) { return !el || !el.closest || !!el.closest('[data-zhiye-editor-ui]') }
  function locOf(el) { return el && el.getAttribute && el.getAttribute('data-loc') }
  function target(el) { if (!el || isUi(el)) return null; return el.closest ? el.closest('[data-loc]') : null }
  function byLoc(loc) { return Array.prototype.slice.call(doc.querySelectorAll('[data-loc="' + String(loc).replace(/"/g, '\\"') + '"]')) }
  function ownTextNodes(el) { return Array.prototype.filter.call(el.childNodes, function (n) { return n.nodeType === 3 }) }
  function ownText(el) { return ownTextNodes(el).map(function (n) { return n.textContent }).join('').trim() }
  function kind(el) { return el.getAttribute('data-zt') || 'n' }

  // ---------- 选框 ----------
  var style = doc.createElement('style')
  style.setAttribute('data-zhiye-editor-ui', '1')
  style.textContent = 'html[data-zhiye-editing="true"] [data-loc]{cursor:crosshair!important}[data-zhiye-inline="true"]{cursor:text!important;outline:2px solid #4389ff!important;outline-offset:3px!important}'
  doc.head.appendChild(style)
  var outline = doc.createElement('div')
  var hint = doc.createElement('div')
  ;[outline, hint].forEach(function (el, i) {
    el.setAttribute('data-zhiye-editor-ui', '1')
    el.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483645;display:none;box-sizing:border-box;border-radius:2px;border:2px ' + (i ? 'dashed #92b7ff' : 'solid #4389ff') + ';background:' + (i ? 'transparent' : 'rgba(67,137,255,.04)')
  })
  function mountUi() { if (doc.body && !outline.isConnected) { doc.body.appendChild(outline); doc.body.appendChild(hint) } }
  function paint(el, box) {
    if (!enabled || !el || !el.isConnected) { box.style.display = 'none'; return }
    var r = el.getBoundingClientRect()
    box.style.display = 'block'; box.style.left = r.left + 'px'; box.style.top = r.top + 'px'; box.style.width = r.width + 'px'; box.style.height = r.height + 'px'
  }

  // ---------- 选中信息 ----------
  var STYLE_KEYS = ['fontSize', 'fontWeight', 'fontStyle', 'textDecoration', 'textAlign', 'color', 'backgroundColor', 'borderRadius', 'padding', 'lineHeight', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'marginTop', 'marginRight', 'marginBottom', 'marginLeft', 'opacity', 'display']
  function info(el) {
    var cs = getComputedStyle(el)
    var r = el.getBoundingClientRect()
    var k = kind(el)
    var styles = {}
    STYLE_KEYS.forEach(function (key) { styles[key] = cs[key] })
    var parent = el.parentElement ? el.parentElement.closest('[data-loc]') : null
    var html = el.outerHTML || ''
    return {
      loc: locOf(el),
      tag: el.tagName,
      text: (k === 'e' ? ownText(el) : (el.textContent || '')).trim().slice(0, 5000),
      canEditText: k === 's' || k === 'e',
      canInlineEdit: k === 's',
      dynamic: k === 'd',
      sameLocCount: byLoc(locOf(el)).length,
      instanceIndex: byLoc(locOf(el)).indexOf(el),
      instanceText: origText(el),
      href: el.tagName === 'A' ? el.getAttribute('href') : null,
      styles: styles,
      rect: { left: r.left, top: r.top, width: r.width, height: r.height },
      parentLoc: parent ? locOf(parent) : null,
      outerHTML: html.length > 2000 ? html.slice(0, 2000) + '…' : html
    }
  }
  function select(el) {
    if (!el) return
    selected = el
    paint(el, outline)
    hint.style.display = 'none'
    send('selected', { selection: info(el) })
  }

  // ---------- 应用修改（可完整重放，支持撤销） ----------
  // 元素未被补丁修改前的文字（同 loc 多实例时用于确认是哪一项）
  function origText(el) {
    var o = originals.get(el)
    return String(o ? o.origFull : (el.textContent || '')).trim().slice(0, 200)
  }
  // 补丁作用的元素：没有 index 时为该 loc 的全部实例；有 index 时只取那一项，
  // 并用 match（原始文字）校验，列表重排后按文字找回，找不到就跳过
  function targets(p) {
    var list = byLoc(p.loc)
    if (typeof p.index !== 'number') return list
    var el = list[p.index]
    if (typeof p.match !== 'string') return el ? [el] : []
    if (el && origText(el) === p.match) return [el]
    for (var i = 0; i < list.length; i++) if (origText(list[i]) === p.match) return [list[i]]
    // 列表项里通常还有删除按钮等子元素：退一步按前缀匹配
    if (el && origText(el).indexOf(p.match) === 0) return [el]
    for (var j = 0; j < list.length; j++) if (origText(list[j]).indexOf(p.match) === 0) return [list[j]]
    // 文字都对不上（生成时改写了文案）：index 仍是最可靠的定位，落回原位而不是放弃
    return el ? [el] : []
  }
  function remember(el) {
    if (originals.has(el)) return
    originals.set(el, {
      origFull: el.textContent || '',
      text: ownTextNodes(el).map(function (n) { return n.textContent }),
      full: kind(el) === 's' ? el.textContent : null,
      style: el.getAttribute('style'),
      href: el.getAttribute('href'),
    })
  }
  function restore(el) {
    var o = originals.get(el)
    if (!o) return
    if (o.full !== null && o.full !== undefined) { if (el.textContent !== o.full) el.textContent = o.full }
    else { var nodes = ownTextNodes(el); nodes.forEach(function (n, i) { if (o.text[i] !== undefined && n.textContent !== o.text[i]) n.textContent = o.text[i] }) }
    if (o.style === null) el.removeAttribute('style'); else el.setAttribute('style', o.style)
    if (o.href === null) el.removeAttribute('href'); else el.setAttribute('href', o.href)
    originals.delete(el)
  }
  function applyOne(el, p) {
    remember(el)
    var k = kind(el)
    if (p.text !== undefined && p.text !== null) {
      if (k === 's') { if (el.textContent !== p.text) el.textContent = p.text }
      else if (k === 'e') {
        var nodes = ownTextNodes(el).filter(function (n) { return n.textContent.trim() })
        if (nodes[0]) { nodes[0].textContent = p.text; nodes.slice(1).forEach(function (n) { n.textContent = '' }) }
      }
    }
    if (p.styles) for (var key in p.styles) { el.style[key] = p.styles[key] }
    if (p.href !== undefined && el.tagName === 'A') el.setAttribute('href', p.href)
    if (p.removed) el.style.display = 'none'
  }
  function applyAll() {
    if (editing) return
    applying = true
    if (observer) observer.disconnect()
    Array.from(originals.keys()).forEach(function (el) { if (el.isConnected) restore(el); else originals.delete(el) })
    patches.forEach(function (p) { targets(p).forEach(function (el) { applyOne(el, p) }) })
    if (selected && !selected.isConnected) { var again = byLoc(locOf(selected))[0]; selected = again || null }
    if (selected && selected.style.display === 'none') { selected = null; outline.style.display = 'none'; send('deselect') }
    observe()
    applying = false
    reposition()
  }
  function observe() { if (observer && doc.body) observer.observe(doc.body, { childList: true, subtree: true, characterData: true }) }

  function reposition() {
    paint(selected, outline); paint(hover, hint)
    if (enabled && selected && selected.isConnected) send('selection-position', { selection: info(selected) })
  }

  // ---------- 事件 ----------
  doc.addEventListener('pointermove', function (e) { if (enabled && !editing) { hover = target(e.target); paint(hover, hint) } }, true)
  doc.addEventListener('pointerleave', function () { hint.style.display = 'none' })
  doc.addEventListener('click', function (e) {
    if (!enabled) return
    if (editing && editing.contains(e.target)) return
    e.preventDefault(); e.stopImmediatePropagation()
    var el = target(e.target)
    if (el) select(el)
  }, true)
  ;['submit', 'mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchstart', 'keypress'].forEach(function (t) {
    doc.addEventListener(t, function (e) { if (enabled && !(editing && editing.contains(e.target))) { if (t === 'submit') e.preventDefault(); e.stopImmediatePropagation() } }, true)
  })
  doc.addEventListener('dblclick', function (e) {
    if (!enabled) return
    var el = target(e.target)
    if (!el || kind(el) !== 's') return
    e.preventDefault(); e.stopImmediatePropagation()
    select(el)
    editing = el
    el.contentEditable = 'true'
    el.setAttribute('data-zhiye-inline', 'true')
    el.focus()
    var range = doc.createRange(); range.selectNodeContents(el)
    var s = window.getSelection(); s.removeAllRanges(); s.addRange(range)
  }, true)
  doc.addEventListener('focusout', function (e) {
    if (editing !== e.target) return
    var el = editing
    el.removeAttribute('contenteditable'); el.removeAttribute('data-zhiye-inline')
    editing = null
    var text = el.textContent
    send('inline-edit', { loc: locOf(el), text: text, index: byLoc(locOf(el)).indexOf(el), match: origText(el) })
    select(el)
  }, true)
  doc.addEventListener('keydown', function (e) {
    if (editing && e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); editing.blur(); return }
    if (enabled && e.key === 'Escape') {
      if (editing) editing.blur()
      else { selected = null; outline.style.display = 'none'; send('deselect') }
    }
  }, true)
  window.addEventListener('scroll', reposition, { passive: true, capture: true })
  window.addEventListener('resize', reposition)

  window.addEventListener('message', function (e) {
    if (e.source !== window.parent) return
    var msg = e.data
    if (!msg || msg.channel !== CHANNEL) return
    if (msg.type === 'toggle') {
      enabled = !!msg.enabled
      doc.documentElement.setAttribute('data-zhiye-editing', String(enabled))
      if (!enabled) { if (editing) editing.blur(); selected = null; outline.style.display = 'none'; hint.style.display = 'none' }
      else if (msg.loc) { var el = byLoc(msg.loc)[0]; if (el) select(el) }
    } else if (msg.type === 'apply-patches') {
      patches = Array.isArray(msg.patches) ? msg.patches : []
      applyAll()
    } else if (msg.type === 'select-parent') {
      if (selected) { var p = selected.parentElement && selected.parentElement.closest('[data-loc]'); if (p) select(p) }
    } else if (msg.type === 'deselect') {
      selected = null; outline.style.display = 'none'
    }
  })

  function start() {
    mountUi()
    observer = new MutationObserver(function (records) {
      if (applying || editing) return
      if (records.every(function (r) { var t = r.target.nodeType === 1 ? r.target : r.target.parentElement; return t && isUi(t) })) return
      cancelAnimationFrame(scheduled)
      scheduled = requestAnimationFrame(function () { if (patches.length) applyAll(); else reposition() })
    })
    observe()
    // 等待框架首屏渲染完成后再报告 ready
    var tries = 0
    ;(function wait() {
      tries++
      if (doc.querySelector('[data-loc]') || tries > 40) send('ready', { editable: !!doc.querySelector('[data-loc]') })
      else setTimeout(wait, 50)
    })()
  }
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start, { once: true })
  else start()
})()
