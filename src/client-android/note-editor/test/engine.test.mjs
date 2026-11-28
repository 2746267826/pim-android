import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import {
  createEditor,
  createDocumentEditor,
  documentBlocks,
  documentInsertion,
  documentNeedsSource,
  insertionAtCaret,
  sourceOnlyReason,
} from '../src/engine.mjs'

const dom = new JSDOM('<!doctype html><body></body>', { pretendToBeVisual: true, url: 'https://appassets.androidplatform.net' })
for (const key of ['window', 'document', 'navigator', 'HTMLElement', 'Node', 'MutationObserver', 'DOMParser', 'getComputedStyle']) {
  Object.defineProperty(globalThis, key, { configurable: true, value: dom.window[key] })
}
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
const active = []
const open = (text, callbacks) => {
  const element = document.body.appendChild(document.createElement('div'))
  const editor = createEditor(element, text, callbacks)
  active.push(editor)
  return editor
}
const openDocument = (blocks, callbacks) => {
  const element = document.body.appendChild(document.createElement('div'))
  const editor = createDocumentEditor(element, blocks, callbacks)
  active.push(editor)
  return editor
}
afterEach(() => { active.splice(0).forEach(editor => editor.destroy()); document.body.replaceChildren() })

test('loading, selection, read-only and theme-neutral updates do not publish canonicalized source', () => {
  let changes = 0
  const editor = open('# 中文标题\n\n原文  \n第二行\n', { onUpdate: () => changes++ })
  editor.setEditable(false, false)
  editor.setEditable(true, false)
  editor.commands.setTextSelection(2)
  assert.equal(changes, 0)
})

test('headings, bold, lists, tables, tasks and code survive a Markdown round trip', () => {
  const editor = open('# 标题\n\n**结论**和`公式`\n\n- 项目一\n- 项目二\n\n- [x] 已完成\n\n| 项目 | 数量 |\n| --- | --- |\n| A | 42 |\n\n```txt\n原始数据\n```')
  const before = editor.getJSON()
  const output = editor.getMarkdown()
  assert.match(output, /\*\*结论\*\*/)
  assert.match(output, /42/)
  editor.commands.setContent(output, { contentType: 'markdown', emitUpdate: false })
  assert.deepEqual(editor.getJSON(), before)
})

test('formatted caret produces two valid fragments without slicing through bold markers', () => {
  const source = '**beforeafter**'
  const editor = open(source)
  editor.commands.setTextSelection(7)
  const split = insertionAtCaret(editor, source)
  assert.deepEqual(split, { expectedText: source, before: '**before**', after: '**after**' })
  assert.equal(open(split.before).getText(), 'before')
  assert.equal(open(split.after).getText(), 'after')
})

test('a caret inside a nested list preserves both sides as independently editable Markdown', () => {
  const source = '- **beforeafter**\n- 保留后项'
  const editor = open(source)
  let position
  editor.state.doc.descendants((node, pos) => { if (node.isText && node.text === 'beforeafter') position = pos + 6 })
  editor.commands.setTextSelection(position)
  const split = insertionAtCaret(editor, source)
  assert.match(open(split.before).getText(), /before/)
  assert.match(open(split.after).getText(), /after[\s\S]*保留后项/)
})

test('HTML, scripts, Markdown images and extended references request source editing instead of lossy conversion', () => {
  for (const source of ['<script>alert(1)</script>', '<span data-private="keep">原文</span>', '![保留](private.png)', '[链接][ref]\n\n[ref]: https://example.com', '[^n]: 扩展', '---\nkind: special\n---\n原文', '---\r\nkind: special\r\n---']) {
    assert.ok(sourceOnlyReason(source), source)
  }
  assert.equal(sourceOnlyReason('```html\n<script>只是代码</script>\n```'), null)
  assert.equal(sourceOnlyReason('正文 **加粗**\n\n- 列表'), null)
})

test('paste uses visible plain Markdown, refuses unsupported payloads and does not import clipboard HTML', () => {
  const problems = []
  const editor = open('原文', { onProblem: value => problems.push(value) })
  const paste = text => editor.options.editorProps.handlePaste(editor.view, { clipboardData: { files: [], getData: format => format === 'text/plain' ? text : '<img src="https://private.test">' } })
  assert.equal(paste(' **加粗**'), true)
  assert.match(editor.getMarkdown(), /\*\*加粗\*\*/)
  const before = editor.getJSON()
  assert.equal(paste('<script>doNotRun()</script>'), true)
  assert.deepEqual(editor.getJSON(), before)
  assert.equal(problems.length, 1)
  assert.equal(editor.view.dom.querySelector('img'), null)
})

test('link clicks and dropped attachments are consumed without native navigation or file import', () => {
  const editor = open('[外部链接](https://example.com)')
  const link = editor.view.dom.querySelector('a')
  assert.ok(editor.options.editorProps.handleClick(editor.view, 1, { target: link }))
  assert.equal(editor.options.editorProps.handleDrop(), true)
})

test('edits use the mature history implementation and undo restores the original content', () => {
  const editor = open('原文')
  const before = editor.getJSON()
  editor.commands.insertContentAt(3, '新增')
  assert.notDeepEqual(editor.getJSON(), before)
  assert.equal(editor.commands.undo(), true)
  assert.deepEqual(editor.getJSON(), before)
  assert.equal(editor.commands.redo(), true)
})

test('one document editor hosts separate Markdown regions around native material nodes', () => {
  const inkId = '33333333-3333-4333-8333-333333333333'
  const editor = openDocument([
    { id: '11111111-1111-4111-8111-111111111111', kind: 'markdown', text: '## 左段\n\n第一段' },
    { id: inkId, kind: 'ink', label: '手写卡片 1' },
    { id: '22222222-2222-4222-8222-222222222222', kind: 'markdown', text: '右段保持原文  \n第二行' },
  ])
  assert.equal(document.querySelectorAll('.tiptap').length, 1)
  assert.equal(document.querySelectorAll('.pim-region').length, 2)
  assert.equal(document.querySelectorAll(`[data-pim-material="${inkId}"]`).length, 1)
  assert.deepEqual(documentBlocks(editor), [
    { id: '11111111-1111-4111-8111-111111111111', kind: 'markdown', text: '## 左段\n\n第一段' },
    { id: inkId, kind: 'ink' },
    { id: '22222222-2222-4222-8222-222222222222', kind: 'markdown', text: '右段保持原文  \n第二行' },
  ])
  editor.commands.setTextSelection(5)
  editor.commands.insertContent('补写')
  const blocks = documentBlocks(editor)
  assert.equal(blocks.length, 3)
  assert.match(blocks[0].text, /补写/)
  assert.equal(blocks[1].id, inkId)
  assert.equal(blocks[2].text, '右段保持原文  \n第二行')
})

test('document guard rejects deleting a native material identity', () => {
  const inkId = '44444444-4444-4444-8444-444444444444'
  const editor = openDocument([
    { id: '55555555-5555-4555-8555-555555555555', kind: 'markdown', text: 'before' },
    { id: inkId, kind: 'ink', label: '手写卡片 1' },
    { id: '66666666-6666-4666-8666-666666666666', kind: 'markdown', text: 'after' },
  ])
  let material
  editor.state.doc.forEach((node, offset) => {
    if (node.type.name === 'pimMaterial') material = { from: offset, to: offset + node.nodeSize }
  })
  assert.ok(material)
  editor.commands.deleteRange(material)
  assert.deepEqual(documentBlocks(editor).map(block => block.kind), ['markdown', 'ink', 'markdown'])
})

test('document guard rejects merging separate Markdown region identities', () => {
  const editor = openDocument([
    { id: '44444444-4444-4444-8444-444444444446', kind: 'markdown', text: '前段' },
    { id: '44444444-4444-4444-8444-444444444447', kind: 'markdown', text: '后段' },
  ])
  let position
  editor.state.doc.descendants((node, pos) => {
    if (node.isText && node.text === '后段') position = pos
  })
  editor.commands.setTextSelection(position)
  editor.commands.joinBackward()
  assert.deepEqual(documentBlocks(editor), [
    { id: '44444444-4444-4444-8444-444444444446', kind: 'markdown', text: '前段' },
    { id: '44444444-4444-4444-8444-444444444447', kind: 'markdown', text: '后段' },
  ])
})

test('one text selection can span a native material without changing its identity', () => {
  const inkId = '44444444-4444-4444-8444-444444444445'
  const editor = openDocument([
    { id: '55555555-5555-4555-8555-555555555551', kind: 'markdown', text: '前段' },
    { id: inkId, kind: 'ink' },
    { id: '55555555-5555-4555-8555-555555555552', kind: 'markdown', text: '后段' },
  ])
  let material
  editor.state.doc.descendants((node, pos) => {
    if (node.type.name === 'pimMaterial') material = { from: pos, to: pos + node.nodeSize }
  })
  assert.ok(material)
  let first
  let last
  editor.state.doc.descendants((node, pos) => {
    if (!node.isText) return
    if (node.text === '前段') first = pos + 1
    if (node.text === '后段') last = pos + node.nodeSize
  })
  editor.commands.setTextSelection({ from: first, to: last })
  assert.ok(editor.state.selection.from < material.from)
  assert.ok(editor.state.selection.to > material.to)
  assert.deepEqual(documentBlocks(editor), [
    { id: '55555555-5555-4555-8555-555555555551', kind: 'markdown', text: '前段' },
    { id: inkId, kind: 'ink' },
    { id: '55555555-5555-4555-8555-555555555552', kind: 'markdown', text: '后段' },
  ])
})

test('one document history crosses both Markdown regions around a material', () => {
  const editor = openDocument([
    { id: '77777777-7777-4777-8777-777777777771', kind: 'markdown', text: '前' },
    { id: '77777777-7777-4777-8777-777777777772', kind: 'ink' },
    { id: '77777777-7777-4777-8777-777777777773', kind: 'markdown', text: '后' },
  ])
  const find = text => {
    let position
    editor.state.doc.descendants((node, pos) => {
      if (node.isText && node.text === text) position = pos + node.nodeSize
    })
    return position
  }
  editor.commands.setTextSelection(find('前'))
  editor.commands.insertContent('甲')
  editor.commands.setTextSelection(find('后'))
  editor.commands.insertContent('乙')
  assert.deepEqual(documentBlocks(editor).map(block => block.text ?? block.kind), ['前甲', 'ink', '后乙'])
  assert.equal(editor.commands.undo(), true)
  assert.deepEqual(documentBlocks(editor).map(block => block.text ?? block.kind), ['前甲', 'ink', '后'])
  assert.equal(editor.commands.undo(), true)
  assert.deepEqual(documentBlocks(editor).map(block => block.text ?? block.kind), ['前', 'ink', '后'])
})

test('document caret insertion keeps the whole region Markdown fragments', () => {
  const editor = openDocument([
    { id: '77777777-7777-4777-8777-777777777777', kind: 'markdown', text: '**beforeafter**' },
  ])
  let position
  editor.state.doc.descendants((node, pos) => {
    if (node.isText && node.text === 'beforeafter') position = pos + 6
  })
  editor.commands.setTextSelection(position)
  assert.deepEqual(documentInsertion(editor), {
    blockId: '77777777-7777-4777-8777-777777777777',
    offset: 0,
    expectedText: '**beforeafter**',
    before: '**before**',
    after: '**after**',
  })
})

test('unsupported syntax is detected across the whole document before editing starts', () => {
  assert.match(documentNeedsSource([
    { id: '88888888-8888-4888-8888-888888888888', kind: 'markdown', text: '正常正文' },
    { id: '99999999-9999-4999-8999-999999999999', kind: 'markdown', text: '![图片](private.png)' },
  ]), /HTML|图片|源码/)
  assert.equal(documentNeedsSource([
    { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', kind: 'markdown', text: '正常 **正文**' },
  ]), null)
})
