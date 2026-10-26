import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { TableKit } from '@tiptap/extension-table'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { marked } from 'marked'

export const MAX_MARKDOWN_LENGTH = 500_000

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

export function createEditor(element, text = '', callbacks = {}) {
  const editor = new Editor({
    element,
    extensions: [
      StarterKit.configure({
        underline: false,
        trailingNode: false,
        link: { openOnClick: false, autolink: false, linkOnPaste: false },
      }),
      Markdown,
      TableKit.configure({ table: { resizable: false } }),
      TaskList,
      TaskItem.configure({ nested: true }),
    ],
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

export function insertionAtCaret(editor, originalText) {
  const position = editor.state.selection.head
  const doc = editor.state.doc
  const before = editor.markdown.serialize(doc.cut(0, position).toJSON())
  const after = editor.markdown.serialize(doc.cut(position, doc.content.size).toJSON())
  return { expectedText: originalText, before, after }
}
