import { build } from 'esbuild'
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises'
import path from 'node:path'

const out = path.resolve(process.env.NOTE_EDITOR_OUT ?? '../app/build/generated/note-editor-assets/note-editor')
await mkdir(out, { recursive: true })
const result = await build({
  entryPoints: ['src/main.mjs'], bundle: true, minify: true, format: 'iife',
  target: 'chrome100', outfile: path.join(out, 'editor.js'), metafile: true,
})
await copyFile('src/index.html', path.join(out, 'index.html'))
await copyFile('src/editor.css', path.join(out, 'editor.css'))

const packages = [...new Set(Object.keys(result.metafile.inputs).flatMap(file => {
  const match = file.match(/^node_modules\/((?:@[^/]+\/)?[^/]+)/)
  return match ? [match[1]] : []
}))].sort()
const notices = []
for (const name of packages) {
  const folder = path.join('node_modules', name)
  const pkg = JSON.parse(await readFile(path.join(folder, 'package.json'), 'utf8'))
  let license
  for (const file of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'license', 'license.md']) {
    try { license = await readFile(path.join(folder, file), 'utf8'); break } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  if (!license) throw new Error(`Missing license for runtime dependency ${name}`)
  notices.push(`${name}@${pkg.version} (${pkg.license})\n${license}`)
}
await writeFile(path.join(out, 'THIRD-PARTY-NOTICES.txt'), notices.join('\n\n---\n\n'))
console.log(`Built offline editor and ${packages.length} dependency notices: ${out}`)
