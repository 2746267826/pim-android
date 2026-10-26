import { test } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

test('host keeps exact initial source, ignores delayed echoes, separates native epochs and commits DOM changes before freeze', async () => {
  const dom = new JSDOM('<!doctype html><body><nav id="tools" hidden></nav><main id="editor"></main></body>', { pretendToBeVisual: true, url: 'https://appassets.androidplatform.net/assets/note-editor/index.html' })
  for (const key of ['window', 'document', 'navigator', 'HTMLElement', 'Node', 'MutationObserver', 'DOMParser', 'getComputedStyle']) {
    Object.defineProperty(globalThis, key, { configurable: true, value: dom.window[key] })
  }
  globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
  globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
  globalThis.ResizeObserver = class { observe() {} }
  const messages = []
  window.PimNoteBridge = { postMessage: raw => messages.push(JSON.parse(raw)) }
  await import('../src/main.mjs')
  assert.equal(messages[0].type, 'ready')
  const config = { session: 'one', epoch: 0, sequence: 0, text: '原文  \n第二行\n', enabled: true, frozen: false, theme: {} }
  window.PimEditor.configure(config)
  const snapshot = (freeze = false) => JSON.parse(window.PimEditor.snapshot(true, freeze))
  assert.equal(snapshot().text, config.text)
  assert.equal(messages.filter(item => item.type === 'snapshot').length, 0)
  document.querySelector('.tiptap').innerHTML = '<p>快速输入一</p>'
  const first = snapshot()
  assert.equal(first.text, '快速输入一')
  assert.ok(first.sequence > 0)
  document.querySelector('.tiptap').innerHTML = '<p>快速输入二</p>'
  const latest = snapshot()
  window.PimEditor.configure({ ...config, sequence: first.sequence, text: first.text })
  assert.equal(snapshot().text, latest.text, 'an old native echo must not overwrite a newer local transaction')
  window.PimEditor.configure({ ...config, epoch: 1, text: '**材料插入后**' })
  assert.equal(snapshot().epoch, 1)
  assert.equal(snapshot().text, '**材料插入后**')
  window.PimEditor.configure({ ...config, text: '旧版本' })
  assert.equal(snapshot().text, '**材料插入后**')
  snapshot(true)
  assert.equal(document.querySelector('.tiptap').getAttribute('contenteditable'), 'false')
  window.PimEditor.configure({ ...config, epoch: 1, text: '**材料插入后**' })
  assert.equal(document.querySelector('.tiptap').getAttribute('contenteditable'), 'true')
  assert.throws(() => window.PimEditor.configure({ ...config, session: 'another' }), /Wrong editor session/)
  dom.window.close()
})
