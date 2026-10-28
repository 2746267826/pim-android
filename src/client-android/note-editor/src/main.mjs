import { createEditor, insertionAtCaret, sourceOnlyReason } from './engine.mjs'

let editor
let source = ''
let sequence = 0
let epoch = 0
let session
let enabled = false
let frozen = false
let nativeToolbar = false
let geometryFrame = 0
const markNames = ['bold', 'italic', 'heading', 'bulletList', 'taskList', 'blockquote', 'codeBlock', 'table']
const tools = document.querySelector('#tools')
const envelope = value => ({ protocol: 1, session, epoch, ...value })
const post = value => {
  if (session || value.type === 'ready') window.PimNoteBridge?.postMessage(JSON.stringify(envelope(value)))
}
const snapshot = split => {
  editor?.view.domObserver.flush()
  if (!editor || editor.view.composing) return { type: 'composing', composing: true }
  let caret
  if (editor.isFocused) {
    const { top, bottom } = editor.view.coordsAtPos(editor.state.selection.head)
    caret = { top, bottom }
  }
  return { type: 'snapshot', text: source, sequence, focused: editor.isFocused,
    caret, active: markNames.filter(name => editor.isActive(name)), ...(split ? insertionAtCaret(editor, source) : {}) }
}

function refreshTools() {
  for (const button of tools.querySelectorAll('button')) {
    const name = button.dataset.command
    button.setAttribute('aria-pressed', String(editor.isActive(name === 'heading' ? 'heading' : name)))
  }
  tools.hidden = nativeToolbar || !enabled || frozen || !editor.isFocused
}

function publishSelection() {
  post(snapshot(true)); refreshTools()
  scheduleGeometry()
}

function scheduleGeometry() {
  if (geometryFrame) return
  // Native IME focus and layout can settle after the selection transaction.
  geometryFrame = requestAnimationFrame(() => {
    geometryFrame = 0
    if (editor) { post(snapshot(true)); refreshTools() }
  })
}

window.PimEditor = {
  configure(config) {
    if (session && session !== config.session) throw new Error('Wrong editor session')
    session = config.session
    if (config.epoch < epoch) return
    for (const [key, value] of Object.entries(config.theme)) document.documentElement.style.setProperty(`--${key}`, value)
    enabled = config.enabled
    frozen = config.frozen
    nativeToolbar = config.nativeToolbar
    if (editor && config.epoch === epoch && config.sequence < sequence) {
      editor.setEditable(enabled && !frozen, false); refreshTools(); return
    }
    if (config.epoch > epoch) { epoch = config.epoch; sequence = 0 }
    if (!editor) {
      const reason = sourceOnlyReason(config.text)
      if (reason) { post({ type: 'sourceOnly', reason }); return }
      source = config.text
      editor = createEditor(document.querySelector('#editor'), source, {
        onUpdate() {
          source = editor.getMarkdown()
          sequence += 1
          post(snapshot(true))
          refreshTools()
          scheduleGeometry()
        },
        onSelection: publishSelection,
        onFocus: publishSelection,
        onBlur: publishSelection,
        onProblem(reason) { post({ type: 'notice', reason }) },
      })
    } else if (source !== config.text) {
      // Native updates are only applied outside an outstanding local transaction.
      const reason = sourceOnlyReason(config.text)
      if (reason) { post({ type: 'sourceOnly', reason }); return }
      source = config.text
      editor.commands.setContent(source, { contentType: 'markdown', emitUpdate: false })
    }
    editor.setEditable(enabled && !frozen, false)
    refreshTools()
  },
  snapshot(split = false, freeze = false) {
    if (freeze && editor) { frozen = true; editor.view.dom.blur() }
    const result = snapshot(split)
    if (freeze && !result.composing) { editor.setEditable(false, false); tools.hidden = true }
    return JSON.stringify(envelope(result))
  },
  command(command) { executeCommand(command) },
}

function executeCommand(command) {
  if (!command || !editor || !enabled || frozen || editor.view.composing) return
  const chain = editor.chain().focus()
  const actions = {
    bold: () => chain.toggleBold(), italic: () => chain.toggleItalic(),
    heading: () => chain.toggleHeading({ level: 2 }), bulletList: () => chain.toggleBulletList(),
    taskList: () => chain.toggleTaskList(), blockquote: () => chain.toggleBlockquote(),
    codeBlock: () => chain.toggleCodeBlock(), table: () => chain.insertTable({ rows: 3, cols: 2, withHeaderRow: true }),
    undo: () => chain.undo(), redo: () => chain.redo(),
  }
  actions[command]?.().run()
  post(snapshot(true)); refreshTools()
}
tools.addEventListener('pointerdown', event => event.preventDefault())
tools.addEventListener('click', event => executeCommand(event.target.closest('button')?.dataset.command))
document.addEventListener('click', event => { if (event.target.closest('a')) event.preventDefault() })
document.addEventListener('compositionend', () => setTimeout(() => post(snapshot(true)), 0))
new ResizeObserver(() => {
  post({ type: 'height', height: Math.ceil(document.body.getBoundingClientRect().height) })
  if (editor?.isFocused) scheduleGeometry()
}).observe(document.body)
window.addEventListener('resize', () => { if (editor?.isFocused) scheduleGeometry() })
window.addEventListener('error', () => post({ type: 'error' }))
post({ type: 'ready' })
