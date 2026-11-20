import { useEffect, useState, useRef } from "react";
const pages = [
  "Overview",
  "Topics",
  "Messages",
  "Consumer groups",
  "Broker logs",
  "Settings",
];
const icons = ["◈", "▤", "⇄", "◎", "≡", "⚙"];
async function api(url, body, method = "POST") {
  const r = await fetch(
    "/api" + url,
    body !== undefined
      ? {
          method,
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      : {},
  );
  const data = await r.json();
  if (!r.ok) throw Error(data.error || "Request failed");
  return data;
}
const pretty = (v) => {
  try {
    return JSON.stringify(JSON.parse(v), null, 2);
  } catch {
    return v;
  }
};
export default function App() {
  const [page, setPage] = useState("Overview"),
    [health, setHealth] = useState(null),
    [topics, setTopics] = useState([]),
    [groups, setGroups] = useState([]),
    [logs, setLogs] = useState([]),
    [search, setSearch] = useState(""),
    [filter, setFilter] = useState("All topics"),
    [busy, setBusy] = useState(""),
    [notice, setNotice] = useState(null),
    [modal, setModal] = useState(null),
    [selected, setSelected] = useState(""),
    [messages, setMessages] = useState([]),
    [record, setRecord] = useState(null),
    [mode, setMode] = useState("latest"),
    [limit, setLimit] = useState(25),
    [loaded, setLoaded] = useState(false);
  async function refresh() {
    const h = await api("/health");
    setHealth(h);
    if (h.online) {
      const [t, g] = await Promise.all([api("/topics"), api("/groups")]);
      setTopics(t.topics);
      setGroups(g.groups);
    } else {
      setTopics([]);
      setGroups([]);
    }
  }
  useEffect(() => {
    let active = true;
    const poll = () =>
      refresh().catch(
        (e) => active && setNotice({ error: true, text: e.message }),
      );
    poll();
    const id = setInterval(poll, 7000);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, []);
  useEffect(() => {
    if (page === "Broker logs")
      api("/logs")
        .then((d) => setLogs(d.lines))
        .catch((e) => setNotice({ error: true, text: e.message }));
  }, [page]);
  useEffect(() => {
    if (!notice) return;
    const id = setTimeout(() => setNotice(null), 7000);
    return () => clearTimeout(id);
  }, [notice]);
  async function action(label, fn) {
    setBusy(label);
    try {
      const result = await fn();
      if (result?.message) setNotice({ text: result.message });
      await refresh();
      return true;
    } catch (e) {
      setNotice({ error: true, text: e.message });
      return false;
    } finally {
      setBusy("");
    }
  }
  async function start() {
    await action("Starting broker", async () => {
      if (!health.initialized) await api("/kafka/initialize", {});
      return api("/kafka/start", {});
    });
  }
  async function migrateLendingTopics(apply) {
    const label = apply ? "Migrating lending topics" : "Checking Kafka backlog";
    return action(label, async () => {
      const result = await api(
        "/topics/lending-migration",
        apply ? { confirmDrained: true } : undefined,
      );
      const { topicsToResize, preservedDeadLetters } = result;
      const summary = apply
        ? `${topicsToResize} lending topics migrated to six partitions.`
        : `Kafka backlog checks passed. ${topicsToResize} lending topics need resizing.`;
      const retained = preservedDeadLetters.length
        ? ` Retained dead-letter records preserved in ${preservedDeadLetters.length} partitions.`
        : "";
      return { message: summary + retained };
    });
  }
  async function browse(name = selected) {
    if (!name) return;
    setSelected(name);
    setPage("Messages");
    setRecord(null);
    setLoaded(false);
    await action("Reading messages", async () => {
      const d = await api(
        `/messages?topic=${encodeURIComponent(name)}&limit=${limit}&mode=${mode}`,
      );
      setMessages(d.messages);
      setLoaded(true);
      if (d.truncated)
        setNotice({
          text: "Bounded snapshot loaded. Some partitions may have more records.",
        });
    });
  }
  const online = health?.online,
    filtered = topics.filter(
      (t) =>
        t.name.toLowerCase().includes(search.toLowerCase()) &&
        (filter === "All topics" ||
          (filter === "Dead letters"
            ? t.name.endsWith(".DLT")
            : !t.name.endsWith(".DLT"))),
    ),
    count = topics.reduce((n, t) => n + t.retained, 0);
  function table(rows) {
    return (
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Topic name</th>
              <th>Partitions</th>
              <th>Replication</th>
              <th>Offset span</th>
              <th>Health</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.name}>
                <td>
                  <button
                    className="topic-link"
                    onClick={() => setModal({ type: "detail", topic: t })}
                  >
                    <span className="topic-icon">▤</span>
                    {t.name}
                  </button>
                </td>
                <td>{t.partitions}</td>
                <td>{t.replication}</td>
                <td>{t.retained.toLocaleString()}</td>
                <td>
                  <span className={"badge " + (t.inSync ? "green" : "amber")}>
                    {t.inSync ? "In sync" : "Under-replicated"}
                  </span>
                </td>
                <td>
                  <button
                    className="text-button"
                    onClick={() => browse(t.name)}
                  >
                    Browse ↗
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && (
          <Empty
            title={online ? "No topics to show" : "Your workspace is offline"}
            text={
              online
                ? "Create a topic or change your search to get started."
                : "Start your local broker to explore topics and messages."
            }
          />
        )}
      </div>
    );
  }
  return (
    <div className="shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setPage("Overview");
          }}
        >
          <span className="brand-mark">r</span>relay
          <span className="brand-dot">.</span>
        </a>
        <div className="workspace">
          <span className="workspace-icon">S</span>
          <div>
            <strong>Securities lending</strong>
            <small>Local workspace</small>
          </div>
          <span>⌄</span>
        </div>
        <div className="nav-caption">WORKSPACE</div>
        <nav>
          {pages.map((p, i) => (
            <button
              key={p}
              className={page === p ? "active" : ""}
              onClick={() => setPage(p)}
            >
              <span>{icons[i]}</span>
              {p}
              {p === "Topics" && <small>{topics.length}</small>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className={"status-dot " + (online ? "online" : "")}></span>
          <div>
            <strong>{online ? "Cluster connected" : "Cluster offline"}</strong>
            <small>{health?.bootstrap || "Connecting…"}</small>
          </div>
        </div>
        <div className="sidebar-foot">
          APACHE KAFKA CONSOLE <span>v1.0</span>
        </div>
      </aside>
      <main>
        <header className="topbar">
          <div>
            Workspace <span>/</span> <strong>{page}</strong>
          </div>
          <div className="topbar-right">
            <span className="environment">LOCAL DEVELOPMENT</span>
            <span className="avatar">SL</span>
          </div>
        </header>
        <div className="content">
          <div className="page-heading">
            <div>
              <div className="eyebrow">KAFKA WORKSPACE</div>
              <h1>{page === "Overview" ? "Cluster overview" : page}</h1>
              <p>
                {
                  {
                    Overview: "A clear view of your event infrastructure.",
                    Topics: "Manage the streams that connect your services.",
                    Messages:
                      "Inspect events and publish messages to your topics.",
                    "Consumer groups":
                      "Monitor the applications reading your event streams.",
                    "Broker logs":
                      "Recent output from your managed local broker.",
                    Settings: "Manage your portable Kafka workspace.",
                  }[page]
                }
              </p>
            </div>
            <div className="heading-actions">
              <button
                className="button"
                disabled={!!busy}
                onClick={() => action("Refreshing", refresh)}
              >
                ↻ Refresh
              </button>
              {["Overview", "Topics"].includes(page) && (
                <button
                  className="button primary"
                  disabled={!online || !!busy}
                  onClick={() => setModal({ type: "create" })}
                >
                  ＋ Create topic
                </button>
              )}
            </div>
          </div>
          {notice && (
            <div
              role="status"
              className={"toast " + (notice.error ? "error" : "")}
            >
              {notice.error ? "!" : "✓"} {notice.text}
              <button
                aria-label="Dismiss notification"
                onClick={() => setNotice(null)}
              >
                ×
              </button>
            </div>
          )}
          {!online && (
            <div className="connection-banner">
              <span className="banner-icon">◉</span>
              <div>
                <strong>
                  {health?.status === "starting"
                    ? "Your broker is starting"
                    : health?.initialized
                      ? "Bring your workspace online"
                      : "Set up your local Kafka broker"}
                </strong>
                <p>
                  {health?.status === "starting"
                    ? "Waiting for Kafka readiness. This updates automatically."
                    : "Topics and messages are kept in a dedicated portable runtime folder."}
                </p>
              </div>
              <button
                className="button primary"
                disabled={
                  !health ||
                  !!busy ||
                  health?.status === "starting" ||
                  !health?.local
                }
                onClick={start}
              >
                {busy ||
                  (health?.initialized
                    ? "Start broker"
                    : "Initialize & start")}{" "}
                →
              </button>
            </div>
          )}
          {page === "Overview" && (
            <>
              <section className="panel setup-card" style={{marginTop:0,marginBottom:24}}>
                <span>◈</span>
                <div><h2>Set up topics for all lending services</h2><p>Create the 16 borrower, lender, lifecycle, market-data and dead-letter topics. Existing topics are preserved.</p></div>
                <button className="button primary" disabled={!online||!!busy} onClick={()=>action('Creating lending topics',()=>api('/topics/lending-preset',{}))}>Set up lending topics →</button>
              </section>
              <div className="stats">
                {[
                  ["Topics", topics.length, "Streams in this workspace", "▤"],
                  [
                    "Partitions",
                    topics.reduce((n, t) => n + t.partitions, 0),
                    "Distributed across your topics",
                    "◫",
                  ],
                  [
                    "Retained offset span",
                    count.toLocaleString(),
                    "High minus low offsets; may include gaps",
                    "⇄",
                  ],
                  [
                    "Consumer groups",
                    groups.length,
                    "Application subscriptions",
                    "◎",
                  ],
                ].map(([label, value, note, icon]) => (
                  <section className="stat" key={label}>
                    <div>
                      {label}
                      <span>{icon}</span>
                    </div>
                    <strong>{online ? value : "—"}</strong>
                    <small>{note}</small>
                  </section>
                ))}
              </div>
              <div className="overview-grid">
                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Topic distribution</h2>
                      <p>Retained offset span by topic</p>
                    </div>
                    <span className="badge neutral">LIVE SNAPSHOT</span>
                  </div>
                  <div className="distribution">
                    {topics.length ? (
                      topics
                        .slice()
                        .sort((a, b) => b.retained - a.retained)
                        .slice(0, 5)
                        .map((t) => (
                          <div className="distribution-row" key={t.name}>
                            <div>
                              <span>{t.name}</span>
                              <strong>{t.retained.toLocaleString()}</strong>
                            </div>
                            <div className="bar-track">
                              <div
                                style={{
                                  width: `${count ? (t.retained / count) * 100 : 0}%`,
                                }}
                              />
                            </div>
                          </div>
                        ))
                    ) : (
                      <Empty
                        title="Your event streams start here"
                        text="Once your broker is running, topic activity will appear here."
                      />
                    )}
                  </div>
                </section>
                <section className="panel cluster-card">
                  <div className="panel-heading">
                    <h2>Cluster health</h2>
                    <span className={"badge " + (online ? "green" : "neutral")}>
                      {online ? "Healthy" : "Offline"}
                    </span>
                  </div>
                  <div className="cluster-illustration">
                    <span>◈</span>
                    <i />
                    <span>▤</span>
                    <i />
                    <span>◎</span>
                  </div>
                  <dl>
                    <dt>Bootstrap server</dt>
                    <dd>{health?.bootstrap || "—"}</dd>
                    <dt>Broker version</dt>
                    <dd>Apache Kafka {health?.version || "—"}</dd>
                    <dt>Connected brokers</dt>
                    <dd>{health?.cluster?.brokers.length ?? "—"}</dd>
                    <dt>Controller</dt>
                    <dd>{health?.cluster?.controller ?? "—"}</dd>
                  </dl>
                  <button
                    className="text-button"
                    onClick={() => setPage("Settings")}
                  >
                    Manage broker <span>→</span>
                  </button>
                </section>
              </div>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>
                      Your topics{" "}
                      <span className="number-pill">{topics.length}</span>
                    </h2>
                    <p>Explore the streams in your lending platform</p>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => setPage("Topics")}
                  >
                    View all topics →
                  </button>
                </div>
                {table(topics.slice(0, 6))}
              </section>
            </>
          )}
          {page === "Topics" && (
            <section className="panel">
              <div className="toolbar">
                <div className="tabs">
                  {["All topics", "Application", "Dead letters"].map((f) => (
                    <button
                      className={filter === f ? "selected" : ""}
                      onClick={() => setFilter(f)}
                      key={f}
                    >
                      {f}
                    </button>
                  ))}
                </div>
                <input
                  aria-label="Search topics"
                  placeholder="Search topics…"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>
              {table(filtered)}
              <div className="table-footer">
                {filtered.length} topics · Internal Kafka topics are hidden
                <button
                  className="text-button"
                  disabled={!online || !!busy}
                  onClick={() =>
                    action("Creating lending topics", () =>
                      api("/topics/lending-preset", {}),
                    )
                  }
                >
                  Set up lending topics →
                </button>
              </div>
            </section>
          )}
          {page === "Messages" && (
            <>
              <section className="panel message-controls">
                <label>
                  Topic
                  <select
                    aria-label="Message topic"
                    value={selected}
                    onChange={(e) => {
                      setSelected(e.target.value);
                      setMessages([]);
                      setRecord(null);
                      setLoaded(false);
                    }}
                  >
                    <option value="">Select a topic</option>
                    {topics.map((t) => (
                      <option key={t.name}>{t.name}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Read from
                  <select
                    value={mode}
                    onChange={(e) => setMode(e.target.value)}
                  >
                    <option value="latest">Latest offsets</option>
                    <option value="earliest">Earliest offsets</option>
                  </select>
                </label>
                <label>
                  Limit
                  <input
                    type="number"
                    min="1"
                    max="100"
                    value={limit}
                    onChange={(e) => setLimit(e.target.value)}
                  />
                </label>
                <button
                  className="button"
                  disabled={!online || !selected || !!busy}
                  onClick={() => browse()}
                >
                  Read messages
                </button>
                <button
                  className="button primary"
                  disabled={!online || !selected || !!busy}
                  onClick={() => setModal({ type: "produce" })}
                >
                  ＋ Publish message
                </button>
              </section>
              <div className="message-grid">
                <section className="panel">
                  <div className="panel-heading">
                    <h2>
                      Messages{" "}
                      <span className="number-pill">{messages.length}</span>
                    </h2>
                    <span className="muted">Read-only snapshot</span>
                  </div>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Offset</th>
                          <th>Partition</th>
                          <th>Key / value preview</th>
                          <th>Time</th>
                        </tr>
                      </thead>
                      <tbody>
                        {messages.map((m) => (
                          <tr
                            key={`${m.partition}-${m.offset}`}
                            className={record === m ? "selected-row" : ""}
                            onClick={() => setRecord(m)}
                            tabIndex="0"
                            onKeyDown={(e) => e.key === "Enter" && setRecord(m)}
                          >
                            <td>{m.offset}</td>
                            <td>
                              <span className="number-pill">{m.partition}</span>
                            </td>
                            <td className="message-preview">
                              <strong>{m.key || "No key"}</strong>
                              <small>{m.value}</small>
                            </td>
                            <td>
                              {new Date(
                                Number(m.timestamp),
                              ).toLocaleTimeString()}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {!messages.length && (
                      <Empty
                        title={
                          busy === "Reading messages"
                            ? "Reading topic…"
                            : loaded
                              ? "No messages in this snapshot"
                              : "Explore a topic’s messages"
                        }
                        text="Choose a topic and read a bounded snapshot. Application consumer offsets are never committed."
                      />
                    )}
                  </div>
                </section>
                <section className="panel inspector">
                  <div className="panel-heading">
                    <h2>Message inspector</h2>
                    {record && (
                      <button
                        className="text-button"
                        onClick={() =>
                          navigator.clipboard
                            .writeText(record.value)
                            .then(() => setNotice({ text: "Value copied." }))
                            .catch(() =>
                              setNotice({
                                error: true,
                                text: "Clipboard is unavailable.",
                              }),
                            )
                        }
                      >
                        Copy value
                      </button>
                    )}
                  </div>
                  {record ? (
                    <>
                      <dl>
                        <dt>Partition / offset</dt>
                        <dd>
                          {record.partition} / {record.offset}
                        </dd>
                        <dt>Key</dt>
                        <dd>{record.key || "null"}</dd>
                      </dl>
                      <div className="code-label">VALUE</div>
                      <pre>{pretty(record.value)}</pre>
                      <div className="code-label">HEADERS</div>
                      <pre>{JSON.stringify(record.headers, null, 2)}</pre>
                    </>
                  ) : (
                    <Empty
                      title="Select a message"
                      text="Inspect its full payload, key, and headers here."
                    />
                  )}
                </section>
              </div>
            </>
          )}
          {page === "Consumer groups" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Application subscriptions</h2>
                  <p>Temporary portal readers are excluded</p>
                </div>
                <span className="badge neutral">{groups.length} GROUPS</span>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Group ID</th>
                      <th>State</th>
                      <th>Members</th>
                      <th>Protocol</th>
                    </tr>
                  </thead>
                  <tbody>
                    {groups.map((g) => (
                      <tr key={g.groupId}>
                        <td className="mono">{g.groupId}</td>
                        <td>
                          <span
                            className={
                              "badge " +
                              (g.state === "Stable" ? "green" : "neutral")
                            }
                          >
                            {g.state}
                          </span>
                        </td>
                        <td>{g.members}</td>
                        <td>{g.protocol || "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!groups.length && (
                  <Empty
                    title="No application groups yet"
                    text="Start your borrower, lender, or adapter service to see its Kafka subscriptions."
                  />
                )}
              </div>
            </section>
          )}
          {page === "Broker logs" && (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Broker output</h2>
                  <p>Last 200 lines · Managed local broker</p>
                </div>
                <button
                  className="button"
                  onClick={() =>
                    action("Loading logs", async () =>
                      setLogs((await api("/logs")).lines),
                    )
                  }
                >
                  ↻ Reload logs
                </button>
              </div>
              <pre className="log-view">
                {logs.join("\n") ||
                  "No broker output yet. Start the managed broker to capture logs."}
              </pre>
            </section>
          )}
          {page === "Settings" && (
            <>
              <section className="panel settings">
                <div className="panel-heading">
                  <div>
                    <h2>Portable broker</h2>
                    <p>One local KRaft broker, managed from this workspace</p>
                  </div>
                  <span className={"badge " + (online ? "green" : "neutral")}>
                    {health?.status || "Connecting"}
                  </span>
                </div>
                <dl>
                  <dt>Kafka distribution</dt>
                  <dd>{health?.kafkaHome}</dd>
                  <dt>Runtime & topic data</dt>
                  <dd>{health?.runtime}</dd>
                  <dt>Bootstrap servers</dt>
                  <dd>{health?.bootstrap}</dd>
                  <dt>Storage</dt>
                  <dd>
                    {health?.initialized
                      ? "Initialized · Existing data preserved"
                      : "Not initialized"}
                  </dd>
                  <dt>Process ownership</dt>
                  <dd>
                    {health?.managed
                      ? "Managed by Relay"
                      : online
                        ? "External broker · Cannot stop from Relay"
                        : "No managed broker connected"}
                  </dd>
                </dl>
                <div className="settings-actions">
                  <button
                    className="button primary"
                    disabled={
                      !health ||
                      online ||
                      !!busy ||
                      health.status === "starting" ||
                      !health.local
                    }
                    onClick={start}
                  >
                    {health?.initialized
                      ? "Start broker"
                      : "Initialize & start"}
                  </button>
                  <button
                    className="button danger"
                    disabled={!health?.managed || !!busy}
                    onClick={() => setModal({ type: "stop" })}
                  >
                    Stop broker
                  </button>
                </div>
              </section>
              <section className="panel setup-card">
                <span>◈</span>
                <div>
                  <h2>Connect your lending services</h2>
                  <p>
                    Create borrower, lender, lifecycle, market data, and
                    dead-letter topics.
                  </p>
                </div>
                <button
                  className="button"
                  disabled={!online || !!busy}
                  onClick={() =>
                    action("Creating lending topics", () =>
                      api("/topics/lending-preset", {}),
                    )
                  }
                >
                  Set up lending topics →
                </button>
              </section>
              <section className="panel settings">
                <div className="panel-heading">
                  <div>
                    <h2>Lending topic migration</h2>
                    <p>
                      Increase existing lending topics to six partitions for
                      discovery mode.
                    </p>
                  </div>
                </div>
                <div className="settings-actions">
                  <button
                    className="button"
                    disabled={!online || !!busy}
                    onClick={() => migrateLendingTopics(false)}
                  >
                    Check Kafka backlog
                  </button>
                  <button
                    className="button primary"
                    disabled={!online || !health?.local || !!busy}
                    onClick={() => setModal({ type: "migrate" })}
                  >
                    Migrate lending topics
                  </button>
                </div>
              </section>
            </>
          )}
          <footer className="page-footer">
            <span>
              <span className={"status-dot " + (online ? "online" : "")}></span>
              {online
                ? "Connected to local Kafka"
                : "Waiting for broker connection"}
            </span>
            <span>Relay · Your event-driven workspace</span>
          </footer>
        </div>
      </main>
      {busy && (
        <div className="working" role="status">
          <span className="spinner" />
          {busy}…
        </div>
      )}
      {modal && (
        <Modal
          title={
            {
              create: "Create a topic",
              produce: "Publish message",
              detail: "Topic details",
              stop: "Stop local broker",
              delete: "Delete topic",
              migrate: "Migrate lending topics",
            }[modal.type]
          }
          close={() => !busy && setModal(null)}
        >
          {modal.type === "migrate" && (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                setModal(null);
                await migrateLendingTopics(true);
              }}
            >
              <p className="form-intro">
                Relay rechecks Kafka backlog before resizing. Existing records,
                including dead-letter records, are preserved.
              </p>
              <label className="migration-confirmation">
                <input type="checkbox" required />
                I paused producers, drained database outboxes and FIX queues,
                and stopped the backends, FIX adapters, and gateway. Kafka is
                still running.
              </label>
              <div className="modal-actions">
                <button
                  type="button"
                  className="button"
                  onClick={() => setModal(null)}
                >
                  Cancel
                </button>
                <button className="button primary" disabled={!!busy}>
                  Start migration
                </button>
              </div>
            </form>
          )}
          {modal.type === "create" && (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const d = new FormData(e.currentTarget);
                if (
                  await action("Creating topic", () =>
                    api("/topics", {
                      topic: d.get("topic"),
                      partitions: Number(d.get("partitions")),
                    }),
                  )
                )
                  setModal(null);
              }}
            >
              <p className="form-intro">
                Create a stream for your application events.
              </p>
              <label>
                Topic name
                <input
                  name="topic"
                  required
                  maxLength="249"
                  pattern="[a-zA-Z0-9._-]+"
                  placeholder="e.g. borrower.events.v1"
                  autoFocus
                />
              </label>
              <label>
                Partitions
                <input
                  name="partitions"
                  type="number"
                  min="1"
                  max="32"
                  defaultValue="1"
                  required
                />
              </label>
              <p className="help">
                Replication factor is 1 for this single-broker workspace.
              </p>
              <div className="modal-actions">
                <button
                  type="button"
                  className="button"
                  onClick={() => setModal(null)}
                >
                  Cancel
                </button>
                <button className="button primary" disabled={!!busy}>
                  Create topic
                </button>
              </div>
            </form>
          )}
          {modal.type === "produce" && (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const d = new FormData(e.currentTarget);
                if (
                  await action("Publishing message", () =>
                    api("/messages", {
                      topic: selected,
                      key: d.get("key"),
                      value: d.get("value"),
                    }),
                  )
                ) {
                  setModal(null);
                  await browse();
                }
              }}
            >
              <p className="form-intro mono">{selected}</p>
              <label>
                Key <span className="muted">(optional)</span>
                <input name="key" placeholder="e.g. loan-1042" />
              </label>
              <label>
                Message value
                <textarea
                  name="value"
                  rows="10"
                  required
                  placeholder={'{\n  "eventType": "LoanRequested"\n}'}
                />
              </label>
              <p className="help">
                JSON or plain text. Multiline content is published as one
                record.
              </p>
              <div className="modal-actions">
                <button
                  type="button"
                  className="button"
                  onClick={() => setModal(null)}
                >
                  Cancel
                </button>
                <button className="button primary" disabled={!!busy}>
                  Publish message
                </button>
              </div>
            </form>
          )}
          {modal.type === "detail" && (
            <>
              <p className="form-intro mono">{modal.topic.name}</p>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Partition</th>
                      <th>Low offset</th>
                      <th>High offset</th>
                      <th>Leader</th>
                    </tr>
                  </thead>
                  <tbody>
                    {modal.topic.offsets.map((p) => (
                      <tr key={p.partition}>
                        <td>{p.partition}</td>
                        <td>{p.low}</td>
                        <td>{p.high}</td>
                        <td>
                          {
                            modal.topic.partitionDetails.find(
                              (d) => d.partitionId === p.partition,
                            )?.leader
                          }
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="modal-actions">
                <button
                  className="button danger"
                  onClick={() =>
                    setModal({ type: "delete", topic: modal.topic })
                  }
                >
                  Delete topic
                </button>
                <button
                  className="button primary"
                  onClick={() => {
                    browse(modal.topic.name);
                    setModal(null);
                  }}
                >
                  Browse messages →
                </button>
              </div>
            </>
          )}
          {["stop", "delete"].includes(modal.type) && (
            <>
              <p className="form-intro">
                {modal.type === "stop"
                  ? "Stop the managed broker gracefully? Connected services will lose their Kafka connection. Topic data is preserved."
                  : `Permanently delete ${modal.topic.name} and its records? This cannot be undone.`}
              </p>
              <div className="modal-actions">
                <button className="button" onClick={() => setModal(null)}>
                  Cancel
                </button>
                <button
                  className="button danger"
                  disabled={!!busy}
                  onClick={async () => {
                    if (
                      await action(
                        modal.type === "stop"
                          ? "Stopping broker"
                          : "Deleting topic",
                        () =>
                          modal.type === "stop"
                            ? api("/kafka/stop", {})
                            : api(
                                "/topics/" +
                                  encodeURIComponent(modal.topic.name),
                                {},
                                "DELETE",
                              ),
                      )
                    )
                      setModal(null);
                  }}
                >
                  Confirm {modal.type === "stop" ? "stop" : "delete"}
                </button>
              </div>
            </>
          )}
        </Modal>
      )}
    </div>
  );
}
function Empty({ title, text }) {
  return (
    <div className="empty">
      <span>◈</span>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
function Modal({ title, close, children }) {
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    const previous = document.activeElement;
    const handler = (e) => {
      if (e.key === "Escape") closeRef.current();
      if (e.key === "Tab") {
        const items = [
          ...document
            .querySelector(".modal")
            .querySelectorAll("button,input,textarea,select"),
        ].filter((el) => !el.disabled);
        if (e.shiftKey && document.activeElement === items[0]) {
          e.preventDefault();
          items.at(-1)?.focus();
        } else if (!e.shiftKey && document.activeElement === items.at(-1)) {
          e.preventDefault();
          items[0]?.focus();
        }
      }
    };
    document.addEventListener("keydown", handler);
    document.querySelector(".modal input,.modal button")?.focus();
    return () => {
      document.removeEventListener("keydown", handler);
      previous?.focus();
    };
  }, []);
  return (
    <div
      className="modal-backdrop"
      onClick={(e) => e.target === e.currentTarget && close()}
    >
      <section
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="modal-heading">
          <h2>{title}</h2>
          <button aria-label="Close dialog" onClick={close}>
            ×
          </button>
        </div>
        {children}
      </section>
    </div>
  );
}
