import { test } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'

test('host exposes one continuous document, material identity and previews across the bridge', async () => {
  const dom = new JSDOM('<!doctype html><body><nav id="tools" hidden></nav><main id="editor"></main></body>', {
    pretendToBeVisual: true,
    url: 'https://appassets.androidplatform.net/assets/note-editor/index.html',
  })
  for (const key of ['window', 'document', 'navigator', 'HTMLElement', 'Node', 'MutationObserver', 'DOMParser', 'getComputedStyle']) {
    Object.defineProperty(globalThis, key, { configurable: true, value: dom.window[key] })
  }
  globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
  globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
  globalThis.ResizeObserver = class { observe() {} }
  const messages = []
  window.PimNoteBridge = { postMessage: raw => messages.push(JSON.parse(raw)) }
  await import('../src/main.mjs')
  const blocks = [
    { id: '11111111-1111-4111-8111-111111111111', kind: 'markdown', text: '第一段' },
    { id: '22222222-2222-4222-8222-222222222222', kind: 'ink', label: '手写卡片 1' },
    { id: '33333333-3333-4333-8333-333333333333', kind: 'markdown', text: '第二段' },
  ]
  const config = {
    session: 'document-one', epoch: 0, sequence: 0, blocks,
    enabled: true, frozen: false, nativeToolbar: true, theme: {},
  }
  window.PimEditor.configure(config)
  assert.equal(document.querySelectorAll('.tiptap').length, 1)
  assert.equal(document.querySelectorAll('.pim-region').length, 2)
  assert.deepEqual(messages.filter(message => message.type === 'snapshot'), [])
  const snapshot = () => JSON.parse(window.PimEditor.snapshot(true))
  assert.deepEqual(snapshot().blocks, blocks.map(({ id, kind, text }) => ({ id, kind, ...(text == null ? {} : { text }) })))
  window.PimEditor.setMaterialPreviews({ '22222222-2222-4222-8222-222222222222': 'data:image/png;base64,AAAA' })
  const material = document.querySelector('[data-pim-material="22222222-2222-4222-8222-222222222222"]')
  assert.ok(material.classList.contains('has-preview'))
  assert.equal(material.querySelector('img').getAttribute('src'), 'data:image/png;base64,AAAA')
  material.dispatchEvent(new window.MouseEvent('click', { bubbles: true }))
  assert.equal(messages.filter(message => message.type === 'material').length, 1)
  assert.equal(messages.filter(message => message.type === 'material')[0].id, '22222222-2222-4222-8222-222222222222')

  document.querySelectorAll('.pim-region')[1].querySelector('p').textContent = '第二段已改'
  const changed = snapshot()
  assert.equal(changed.blocks[0].text, '第一段')
  assert.equal(changed.blocks[2].text, '第二段已改')
  assert.equal(changed.blocks[1].kind, 'ink')
  const editedRegion = document.querySelectorAll('.pim-region')[1]
  window.PimEditor.configure({ ...config, sequence: changed.sequence, blocks: [
    { ...blocks[0], text: changed.blocks[0].text },
    blocks[1],
    { ...blocks[2], text: changed.blocks[2].text },
  ] })
  assert.equal(document.querySelectorAll('.pim-region')[1], editedRegion)
  assert.equal(document.querySelectorAll('.pim-region')[1].querySelector('p').textContent, '第二段已改')
  assert.equal(snapshot().blocks[2].text, '第二段已改')

  window.PimEditor.configure({ ...config, epoch: 1, blocks: [
    blocks[0],
    blocks[1],
    { ...blocks[2], text: '原生新版本' },
  ] })
  assert.equal(snapshot().blocks[2].text, '原生新版本')
  dom.window.close()
})
