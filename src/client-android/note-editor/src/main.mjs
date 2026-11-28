import {
  createEditor,
  createDocumentEditor,
  documentBlocks,
  documentInsertion,
  documentNeedsSource,
  insertionAtCaret,
  setDocumentBlocks,
  sourceOnlyReason,
} from './engine.mjs'

let editor
let source = ''
let sequence = 0
let epoch = 0
let session
let enabled = false
let frozen = false
let nativeToolbar = false
let geometryFrame = 0
let documentMode = false
let documentSignature = ''
let materialPreviews = {}
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
  if (documentMode) {
    const blocks = documentBlocks(editor)
    documentSignature = signature(blocks)
    const target = split ? documentInsertion(editor) : null
    return { type: 'snapshot', blocks, sequence, focused: editor.isFocused,
      caret, active: markNames.filter(name => editor.isActive(name)), ...(target ? { target } : {}) }
  }
  return { type: 'snapshot', text: source, sequence, focused: editor.isFocused,
    caret, active: markNames.filter(name => editor.isActive(name)), ...(split ? insertionAtCaret(editor, source) : {}) }
}

function refreshTools() {
  if (!editor) {
    tools.hidden = true
    return
  }
  for (const button of tools.querySelectorAll('button')) {
    const name = button.dataset.command
    button.setAttribute('aria-pressed', String(editor.isActive(name === 'heading' ? 'heading' : name)))
  }
  tools.hidden = nativeToolbar || !enabled || frozen || !editor.isFocused
}

function publishSelection() {
  // Tiptap can emit selection/focus callbacks while the constructor is still
  // running, before createDocumentEditor has returned to assign `editor`.
  if (!editor) return
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

function signature(blocks) {
  return JSON.stringify(blocks.map(block => [block.id, block.kind, block.text ?? '']))
}

function applyMaterialPreviews() {
  for (const [id, sourceUrl] of Object.entries(materialPreviews)) {
    const root = document.querySelector(`[data-pim-material="${id}"]`)
    if (!root) continue
    const image = root.querySelector('.pim-material-image')
    if (image && image.getAttribute('src') !== sourceUrl) {
      image.setAttribute('src', sourceUrl)
      root.classList.add('has-preview')
    }
  }
}

function configureDocument(config) {
  if (config.epoch > epoch) { epoch = config.epoch; sequence = 0 }
  const reason = documentNeedsSource(config.blocks)
  if (reason) {
    post({ type: 'sourceOnly', reason })
    return
  }
  const nextSignature = signature(config.blocks)
  if (!editor) {
    documentMode = true
    documentSignature = nextSignature
    editor = createDocumentEditor(document.querySelector('#editor'), config.blocks, {
      onUpdate() {
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
    applyMaterialPreviews()
  } else if (config.epoch === epoch && config.sequence < sequence) {
    // The native side still holds an older echo of a local transaction.
  } else if (nextSignature !== documentSignature) {
    documentSignature = nextSignature
    setDocumentBlocks(editor, config.blocks)
    applyMaterialPreviews()
  }
  editor.setEditable(enabled && !frozen, false)
  refreshTools()
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
    if (Array.isArray(config.blocks)) {
      if (editor && config.epoch === epoch && config.sequence < sequence) {
        editor.setEditable(enabled && !frozen, false); refreshTools(); return
      }
      configureDocument(config)
      return
    }
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
  setMaterialPreviews(previews) {
    materialPreviews = { ...materialPreviews, ...previews }
    applyMaterialPreviews()
  },
  scrollToMaterial(id) {
    document.querySelector(`[data-pim-material="${id}"]`)?.scrollIntoView({ block: 'center' })
  },
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
document.querySelector('#editor').addEventListener('click', event => {
  const material = event.target.closest?.('[data-pim-material]')
  if (material) post({ type: 'material', id: material.getAttribute('data-pim-material') })
})
document.addEventListener('compositionend', () => setTimeout(() => post(snapshot(true)), 0))
new ResizeObserver(() => {
  post({ type: 'height', height: Math.ceil(document.body.getBoundingClientRect().height) })
  if (editor?.isFocused) scheduleGeometry()
}).observe(document.body)
window.addEventListener('resize', () => { if (editor?.isFocused) scheduleGeometry() })
window.addEventListener('error', () => post({ type: 'error' }))
post({ type: 'ready' })
