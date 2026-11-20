import express from "express";
import { HttpError, integer } from "./errors.js";
import path from "node:path";
import fs from "node:fs";
import { createConfig, lendingTopics } from "./config.js";
import { BrokerManager } from "./broker.js";
import { KafkaService } from "./kafka.js";
import { lendingPartitionMigration } from "./lending-partitions.js";
const config = createConfig(),
  broker = new BrokerManager(config),
  kafka = new KafkaService(config),
  app = express();
let migrating = false;
app.disable("x-powered-by");
app.use(express.json({ limit: "1mb" }));
app.use("/api", (req, res, next) => {
  const origin = req.headers.origin;
  if (origin && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin))
    return res
      .status(403)
      .json({ error: "Only local portal origins are permitted." });
  if (["POST", "DELETE"].includes(req.method) && !req.is("application/json"))
    return res.status(415).json({ error: "Use application/json." });
  if (migrating && ["POST", "DELETE"].includes(req.method))
    return res.status(409).json({ error: "Lending topic migration is running." });
  next();
});
const route = (fn) => (req, res, next) =>
  Promise.resolve(fn(req, res)).catch(next);
app.get(
  "/api/health",
  route(async (_req, res) => {
    let cluster = null,
      error = broker.lastError;
    try {
      cluster = await kafka.cluster();
    } catch (e) {
      error ||= e.message;
    }
    const managed = Boolean(await broker.owned());
    res.json({
      online: Boolean(cluster),
      status:
        broker.pending ||
        (cluster
          ? "running"
          : broker.startedAt && Date.now() - broker.startedAt < 60000
            ? "starting"
            : "stopped"),
      cluster,
      managed,
      initialized: broker.initialized,
      available: broker.available,
      error: cluster ? null : error,
      bootstrap: config.brokers.join(", "),
      version: config.version,
      kafkaHome: config.kafkaHome,
      runtime: config.runtime,
      local: config.local,
    });
  }),
);
app.post(
  "/api/kafka/initialize",
  route(async (_req, res) => res.json(await broker.initialize())),
);
app.post(
  "/api/kafka/start",
  route(async (_req, res) => res.status(202).json(await broker.start())),
);
app.post(
  "/api/kafka/stop",
  route(async (_req, res) => res.json(await broker.stop())),
);
app.get("/api/logs", (_req, res) => res.json({ lines: broker.logs() }));
app.get(
  "/api/topics",
  route(async (_req, res) => res.json({ topics: await kafka.topics() })),
);
app.post(
  "/api/topics",
  route(async (req, res) => res.status(201).json(await kafka.create(req.body))),
);
app.post(
  "/api/topics/lending-preset",
  route(async (req, res) => {
    const partitions = integer(req.body.partitions ?? 1, "Partitions", 1, 32);
    const current = await kafka.topics(lendingTopics);
    const existing = new Set(current.map((t) => t.name));
    let created = 0;
    for (const topic of lendingTopics)
      if (!existing.has(topic)) {
        await kafka.create({ topic, partitions });
        created++;
      }
    res.json({
      message: `${created} lending topics created. Existing topics preserved.`,
      undersized: current
        .filter((t) => t.partitions < partitions)
        .map((t) => t.name),
    });
  }),
);
app.get(
  "/api/topics/lending-migration",
  route(async (_req, res) => {
    res.json(await lendingPartitionMigration(kafka));
  }),
);
app.post(
  "/api/topics/lending-migration",
  route(async (req, res) => {
    if (!config.local)
      throw new HttpError(403, "Relay migration requires a local Kafka broker.");
    if (req.body?.confirmDrained !== true)
      throw new HttpError(
        400,
        "Confirm that outboxes are drained and services are stopped.",
      );
    migrating = true;
    try {
      res.json(await lendingPartitionMigration(kafka, { apply: true }));
    } finally {
      migrating = false;
    }
  }),
);
app.delete(
  "/api/topics/:topic",
  route(async (req, res) => res.json(await kafka.remove(req.params.topic))),
);
app.get(
  "/api/messages",
  route(async (req, res) =>
    res.json(
      await kafka.messages(req.query.topic, req.query.limit, req.query.mode),
    ),
  ),
);
app.post(
  "/api/messages",
  route(async (req, res) => res.json(await kafka.produce(req.body))),
);
app.get(
  "/api/groups",
  route(async (_req, res) => res.json({ groups: await kafka.groups() })),
);
app.use("/api", (_req, res) =>
  res.status(404).json({ error: "Unknown API route." }),
);
const dist = path.join(config.projectRoot, "dist");
if (fs.existsSync(dist)) {
  app.use(express.static(dist));
  app.get("*", (_req, res) => res.sendFile(path.join(dist, "index.html")));
}
app.use((error, _req, res, _next) =>
  res
    .status(error.status || 503)
    .json({
      error: error.message || "Kafka operation failed.",
      code: error.type,
    }),
);
app.listen(config.port, config.host, () =>
  console.log(`Relay Kafka portal: http://${config.host}:${config.port}`),
);
