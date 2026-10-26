import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { createEditor, insertionAtCaret, sourceOnlyReason } from '../src/engine.mjs'

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
