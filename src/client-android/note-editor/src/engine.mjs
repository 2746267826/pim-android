import { Editor, Extension, Node, mergeAttributes } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { TableKit } from '@tiptap/extension-table'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { Plugin } from '@tiptap/pm/state'
import { marked } from 'marked'

export const MAX_MARKDOWN_LENGTH = 500_000
const TRANSPARENT_PIXEL = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=='

// These need a source-preserving editor until their own round-trip adapters exist.
export function sourceOnlyReason(text) {
  if (text.length > MAX_MARKDOWN_LENGTH) return '当前富文本块超过 50 万字符，使用源码编辑以保留全文。'
  let unsupported = false
  const tokens = marked.lexer(text)
  marked.walkTokens(tokens, token => {
    if (token.type === 'html' || token.type === 'image' || token.type === 'def') unsupported = true
  })
  if (unsupported || Object.keys(tokens.links).length || /^\s*\[\^[^\]]+\]:/m.test(text) || /^---\s*\r?\n[\s\S]*?\r?\n---(?:\s*\r?\n|\s*$)/.test(text)) {
    return '这段含 HTML、Markdown 图片或扩展引用，暂用源码保留原始内容；照片和手写仍可正常插入。'
  }
  return null
}

const PimRegion = Node.create({
  name: 'pimRegion',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes() {
    return {
      regionId: {
        default: null,
        parseHTML: element => element.getAttribute('data-pim-region'),
        renderHTML: attributes => ({ 'data-pim-region': attributes.regionId }),
      },
    }
  },
  parseHTML() { return [{ tag: 'section[data-pim-region]' }] },
  renderHTML({ HTMLAttributes }) {
    return ['section', mergeAttributes(HTMLAttributes, { class: 'pim-region' }), 0]
  },
})

const PimMaterial = Node.create({
  name: 'pimMaterial',
  group: 'block',
  atom: true,
  selectable: false,
  draggable: false,
  isolating: true,
  addAttributes() {
    return {
      materialId: {
        default: null,
        parseHTML: element => element.getAttribute('data-pim-material'),
        renderHTML: attributes => ({ 'data-pim-material': attributes.materialId }),
      },
      kind: { default: 'material', parseHTML: element => element.getAttribute('data-pim-material-kind') },
      label: { default: '材料', parseHTML: element => element.getAttribute('data-pim-material-label') },
    }
  },
  parseHTML() { return [{ tag: 'div[data-pim-material]' }] },
  renderHTML({ node }) {
    return ['div', {
      'data-pim-material': node.attrs.materialId,
      'data-pim-material-kind': node.attrs.kind,
      'data-pim-material-label': node.attrs.label,
      class: `pim-material pim-material-${node.attrs.kind}`,
      contenteditable: 'false',
    },
      ['div', { class: 'pim-material-preview' }, ['img', { class: 'pim-material-image', src: TRANSPARENT_PIXEL, alt: '' }]],
      ['div', { class: 'pim-material-copy' },
        ['span', { class: 'pim-material-label' }, node.attrs.label],
        ['span', { class: 'pim-material-hint' }, '点击查看或编辑材料'],
      ],
    ]
  },
})

function documentLayout(doc) {
  const result = []
  doc.descendants(node => {
    if (node.type.name === 'pimRegion') result.push(`region:${node.attrs.regionId}`)
    if (node.type.name === 'pimMaterial') result.push(`material:${node.attrs.materialId}:${node.attrs.kind}`)
  })
  return result
}

const PimDocumentGuard = Extension.create({
  name: 'pimDocumentGuard',
  addProseMirrorPlugins() {
    return [new Plugin({
      filterTransaction: transaction => {
        if (allowDocumentReplacement) return true
        if (!transaction.docChanged) return true
        return documentLayout(transaction.before).join('|') === documentLayout(transaction.doc).join('|')
      },
    })]
  },
})

let allowDocumentReplacement = false

function baseExtensions() {
  return [
    StarterKit.configure({
      underline: false,
      trailingNode: false,
      link: { openOnClick: false, autolink: false, linkOnPaste: false },
    }),
    Markdown,
    TableKit.configure({ table: { resizable: false } }),
    TaskList,
    TaskItem.configure({ nested: true }),
  ]
}

export function createEditor(element, text = '', callbacks = {}) {
  const editor = new Editor({
    element,
    extensions: baseExtensions(),
    content: text,
    contentType: 'markdown',
    editorProps: {
      attributes: {
        'aria-label': '笔记正文',
        'data-placeholder': '从这里开始记录…',
        spellcheck: 'false',
        autocapitalize: 'sentences',
        autocomplete: 'off',
      },
      handleClick: (_view, _pos, event) => Boolean(event.target.closest('a')),
      handleDrop: () => true,
      // Only native photo import can authorize access to an attachment.
      handlePaste: (_view, event) => {
        if (event.clipboardData?.files?.length) {
          callbacks.onProblem?.('请通过原生插入菜单添加照片，未从剪贴板导入文件。')
          return true
        }
        const text = event.clipboardData?.getData('text/plain')
        if (text == null) return true
        if (sourceOnlyReason(text) || editor.getMarkdown().length + text.length > MAX_MARKDOWN_LENGTH) {
          callbacks.onProblem?.('粘贴内容包含暂不支持的格式或过长，未插入；请切换 Markdown 源码后粘贴以保留全文。')
          return true
        }
        editor.commands.insertContent(text, { contentType: 'markdown' })
        return true
      },
    },
    onUpdate: callbacks.onUpdate ?? (() => {}),
    onSelectionUpdate: callbacks.onSelection ?? (() => {}),
    onFocus: callbacks.onFocus ?? (() => {}),
    onBlur: callbacks.onBlur ?? (() => {}),
  })
  return editor
}

function emptyRegion(regionId) {
  return { type: 'pimRegion', attrs: { regionId }, content: [{ type: 'paragraph' }] }
}

function documentFromBlocks(editor, blocks) {
  const content = blocks.map(block => {
    if (block.kind === 'markdown') {
      const parsed = editor.markdown.parse(block.text ?? '')
      const nodes = parsed.content?.length ? parsed.content : [{ type: 'paragraph' }]
      return { type: 'pimRegion', attrs: { regionId: block.id }, content: nodes }
    }
    return {
      type: 'pimMaterial',
      attrs: {
        materialId: block.id,
        kind: block.kind,
        label: block.label ?? (block.kind === 'photo' ? '照片' : block.kind === 'ink' ? '手写卡片' : '材料'),
      },
    }
  })
  return { type: 'doc', content: content.length ? content : [emptyRegion('empty')] }
}

function rememberDocumentBaseline(editor, blocks) {
  const originals = new Map()
  const json = new Map()
  editor.state.doc.forEach(node => {
    if (node.type.name !== 'pimRegion') return
    const id = node.attrs.regionId
    const source = blocks.find(block => block.id === id && block.kind === 'markdown')
    if (source) originals.set(id, source.text ?? '')
    json.set(id, JSON.stringify(node.content.toJSON()))
  })
  editor.__pimDocument = { originals, baseline: json, blocks }
}

function regionMarkdown(editor, node) {
  const id = node.attrs.regionId
  const state = editor.__pimDocument
  const current = JSON.stringify(node.content.toJSON())
  if (state?.baseline.get(id) === current && state.originals.has(id)) return state.originals.get(id)
  return editor.markdown.serialize({ type: 'doc', content: node.content.toJSON() })
}

export function documentBlocks(editor) {
  const blocks = []
  editor.state.doc.forEach(node => {
    if (node.type.name === 'pimRegion') {
      blocks.push({ id: node.attrs.regionId, kind: 'markdown', text: regionMarkdown(editor, node) })
    } else if (node.type.name === 'pimMaterial') {
      blocks.push({ id: node.attrs.materialId, kind: node.attrs.kind })
    }
  })
  return blocks
}

function documentLength(editor) {
  let length = 0
  editor.state.doc.forEach(node => {
    if (node.type.name === 'pimRegion') length += regionMarkdown(editor, node).length
  })
  return length
}

export function documentInsertion(editor) {
  const position = editor.state.selection.head
  let found = null
  editor.state.doc.forEach((node, offset) => {
    if (found || node.type.name !== 'pimRegion') return
    const start = offset + 1
    const end = start + node.content.size
    if (position >= start && position <= end) found = { node, start, end }
  })
  if (!found) return null
  const blockId = found.node.attrs.regionId
  const text = regionMarkdown(editor, found.node)
  const relative = position - found.start
  const before = found.node.cut(0, relative)
  const after = found.node.cut(relative, found.node.content.size)
  return {
    blockId,
    offset: 0,
    expectedText: text,
    before: editor.markdown.serialize({ type: 'doc', content: before.content.toJSON() }),
    after: editor.markdown.serialize({ type: 'doc', content: after.content.toJSON() }),
  }
}

export function createDocumentEditor(element, blocks, callbacks = {}) {
  const editor = new Editor({
    element,
    extensions: [...baseExtensions(), PimRegion, PimMaterial, PimDocumentGuard],
    content: { type: 'doc', content: [emptyRegion('initial')] },
    editorProps: {
      attributes: {
        'aria-label': '笔记正文',
        'data-placeholder': '从这里开始记录…',
        spellcheck: 'false',
        autocapitalize: 'sentences',
        autocomplete: 'off',
      },
      handleClick: (_view, _pos, event) => Boolean(event.target.closest?.('a')),
      handleDrop: () => true,
      handlePaste: (_view, event) => {
        if (event.clipboardData?.files?.length) {
          callbacks.onProblem?.('请通过原生插入菜单添加照片，未从剪贴板导入文件。')
          return true
        }
        const text = event.clipboardData?.getData('text/plain')
        if (text == null) return true
        if (sourceOnlyReason(text) || documentLength(editor) + text.length > MAX_MARKDOWN_LENGTH) {
          callbacks.onProblem?.('粘贴内容包含暂不支持的格式或过长，未插入；请切换 Markdown 源码后粘贴以保留全文。')
          return true
        }
        editor.commands.insertContent(text, { contentType: 'markdown' })
        return true
      },
    },
    onUpdate: callbacks.onUpdate ?? (() => {}),
    onSelectionUpdate: callbacks.onSelection ?? (() => {}),
    onFocus: callbacks.onFocus ?? (() => {}),
    onBlur: callbacks.onBlur ?? (() => {}),
  })
  replaceDocument(editor, blocks)
  return editor
}

export function setDocumentBlocks(editor, blocks) {
  replaceDocument(editor, blocks)
}

function replaceDocument(editor, blocks) {
  allowDocumentReplacement = true
  try {
    const content = editor.schema.nodeFromJSON(documentFromBlocks(editor, blocks)).content
    editor.view.dispatch(
      editor.state.tr
        .replaceWith(0, editor.state.doc.content.size, content)
        .setMeta('addToHistory', false)
        .setMeta('preventUpdate', true),
    )
    rememberDocumentBaseline(editor, blocks)
  } finally {
    allowDocumentReplacement = false
  }
}

export function documentNeedsSource(blocks) {
  for (const block of blocks) {
    if (block.kind === 'markdown') {
      const reason = sourceOnlyReason(block.text ?? '')
      if (reason) return reason
    }
  }
  return null
}

export function insertionAtCaret(editor, originalText) {
  const position = editor.state.selection.head
  const doc = editor.state.doc
  const before = editor.markdown.serialize(doc.cut(0, position).toJSON())
  const after = editor.markdown.serialize(doc.cut(position, doc.content.size).toJSON())
  return { expectedText: originalText, before, after }
}
