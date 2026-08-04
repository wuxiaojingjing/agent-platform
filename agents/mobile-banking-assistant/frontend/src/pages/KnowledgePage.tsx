import { useEffect, useMemo, useState } from 'react'
import { api, FileEntry, Finding, Overview } from '../api'
import { ResponsePolicyPanel } from '../components/ResponsePolicyPanel'

/**
 * 知识配置页。
 *
 * 编辑的是资产文件原文，不是表单模型。理由见后端 AssetEditor：资产归 Git 管，
 * 页面若把 YAML 拆成表单再序列化回去，注释与顺序会被改写，同一个文件的 Git diff
 * 就再也读不了。
 */
export function KnowledgePage({ overview, refresh }: { overview: Overview | null; refresh: () => void }) {
  const [files, setFiles] = useState<FileEntry[]>([])
  const [path, setPath] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [original, setOriginal] = useState('')
  const [findings, setFindings] = useState<Finding[]>([])
  const [message, setMessage] = useState<{ kind: 'ok' | 'warn' | 'danger'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.files().then(setFiles).catch((e) => setMessage({ kind: 'danger', text: String(e) }))
  }, [])

  const grouped = useMemo(() => {
    const map = new Map<string, FileEntry[]>()
    for (const file of files) {
      const list = map.get(file.category) ?? []
      list.push(file)
      map.set(file.category, list)
    }
    return [...map.entries()].sort(([a], [b]) => categoryRank(a) - categoryRank(b) || a.localeCompare(b, 'zh'))
  }, [files])

  /** 列表项：域路径已按 Agent 分组，只展示 assets 内相对路径，避免一排重复 basename。 */
  function fileLabel(filePath: string): string {
    const agent = filePath.match(/^agents\/[^/]+\/(.+)$/)
    if (agent) return agent[1]
    return filePath.split('/').pop() ?? filePath
  }

  /** 共享类目靠前，域能力卡按名称排在后段。 */
  function categoryRank(category: string): number {
    if (category.startsWith('能力卡 ·')) return 40
    if (category.startsWith('菜单 ·')) return 50
    if (category.startsWith('域资产 ·')) return 60
    const order = ['规则与融合', '话术模板', '合规话题', '提示词', '目录与菜单', '能力卡', '其他']
    const i = order.indexOf(category)
    return i >= 0 ? i : 30
  }

  async function open(target: string) {
    const loaded = await api.file(target)
    setPath(target)
    setContent(loaded.content)
    setOriginal(loaded.content)
    setFindings([])
    setMessage(null)
  }

  async function save() {
    if (!path) return
    setBusy(true)
    setMessage(null)
    try {
      const result = await api.save(path, content)
      setOriginal(content)
      setFindings(result.findings)
      setMessage({
        kind: result.indexStale ? 'warn' : 'ok',
        text: result.indexStale
          ? `已生效，资产版本 ${result.assetVersion}。检索索引未跟上（重建失败或已关闭自动重建）——BM25 与语义仍服务旧索引，可点「重建索引」补一刀。`
          : `已生效，资产版本 ${result.assetVersion}${
              result.indexDocumentCount != null
                ? `；索引已同步（${result.indexDocumentCount} 张卡${
                    result.vectorsIndexed ? '，含向量' : '，无向量'
                  }）`
                : ''
            }。`,
      })
      refresh()
    } catch (e: any) {
      setFindings(e.body?.findings ?? [])
      setMessage({ kind: 'danger', text: e.body?.message ?? String(e) })
    } finally {
      setBusy(false)
    }
  }

  async function reindex() {
    setBusy(true)
    try {
      const result = await api.reindex()
      setMessage({
        kind: 'ok',
        text: `索引已重建：${result.documentCount} 张卡，向量${result.vectorsIndexed ? '已写入' : '缺失（语义通道不可用）'}。`,
      })
      refresh()
    } catch (e: any) {
      setMessage({ kind: 'danger', text: e.body?.message ?? String(e) })
    } finally {
      setBusy(false)
    }
  }

  const dirty = content !== original
  const writable = overview?.writeEnabled ?? false

  return (
    <>
      {!writable && (
        <div className="banner warn">
          控制台写入未开启，当前只读。资产归 Git 管，生产上应经 MR 与 CI 的 AssetLint 改动；
          本地联调设 <code className="mono">huawei.finance.mobile-banking.console.write-enabled=true</code>。
        </div>
      )}
      {overview?.index.stale && (
        <div className="banner warn">
          能力卡已改但索引未跟上（自动重建失败或已关闭）：BM25 与语义通道仍在服务旧索引，新写的问法召不回来，且不会报错。
          <div className="spacer" />
          <button className="action" onClick={reindex} disabled={busy || !writable}>
            重建索引
          </button>
        </div>
      )}
      {message && <div className={`banner ${message.kind}`}>{message.text}</div>}

      <ResponsePolicyPanel writable={writable} onSaved={refresh} />

      <div className="knowledge-layout">
        <div className="panel file-list">
          <h3>资产文件</h3>
          <p className="file-list-hint">
            共享规则在根目录；各域能力卡在 <span className="mono">agents/&lt;域&gt;/</span> 下，按域分组。
          </p>
          {grouped.map(([category, list]) => (
            <div className="group" key={category}>
              <div className="group-name">{category}</div>
              {list.map((file) => (
                <div
                  key={file.path}
                  className={`file ${file.path === path ? 'active' : ''}`}
                  onClick={() => open(file.path)}
                  title={file.path}
                >
                  {fileLabel(file.path)}
                </div>
              ))}
            </div>
          ))}
        </div>

        <div className="panel editor-pane">
          {path === null ? (
            <div className="empty">左侧挑一个文件。</div>
          ) : (
            <>
              <div className="editor-toolbar">
                <span className="path">{path}</span>
                {dirty && <span className="tag warn">未保存</span>}
                <button className="ghost" onClick={() => setContent(original)} disabled={!dirty}>
                  还原
                </button>
                <button className="action" onClick={save} disabled={!dirty || busy || !writable}>
                  保存并生效
                </button>
              </div>
              <textarea
                value={content}
                spellCheck={false}
                readOnly={!writable}
                onChange={(e) => setContent(e.target.value)}
              />
              {findings.length > 0 && (
                <div className="findings">
                  {findings.map((f, i) => (
                    <div className="finding" key={i}>
                      <span className={`tag ${f.severity === 'ERROR' ? 'danger' : 'warn'}`}>
                        {f.severity}
                      </span>
                      <span className="mono" style={{ color: 'var(--muted)' }}>{f.rule}</span>
                      <span className="mono">{f.where}</span>
                      <span>{f.detail}</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </>
  )
}
