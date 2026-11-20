import { Kafka, logLevel } from "kafkajs";
import { randomUUID } from "node:crypto";
import { HttpError, topicName, integer } from "./errors.js";
export class KafkaService {
  constructor(config) {
    this.client = new Kafka({
      clientId: "relay-portal",
      brokers: config.brokers,
      logLevel: logLevel.NOTHING,
      connectionTimeout: 1500,
      requestTimeout: 5000,
      retry: { retries: 0 },
    });
    this.reader = new Kafka({
      clientId: "relay-reader",
      brokers: config.brokers,
      logLevel: logLevel.NOTHING,
      connectionTimeout: 1500,
      requestTimeout: 5000,
      retry: { retries: 6, initialRetryTime: 300, maxRetryTime: 2000 },
    });
  }
  async admin(fn) {
    const a = this.client.admin();
    try {
      await a.connect();
      return await fn(a);
    } finally {
      await a.disconnect();
    }
  }
  async cluster() {
    return this.admin((a) => a.describeCluster());
  }
  async topics(only) {
    return this.admin(async (a) => {
      const names = (await a.listTopics()).filter(
        (name) => !name.startsWith("__") && (!only || only.includes(name)),
      );
      if (!names.length) return [];
      const { topics } = await a.fetchTopicMetadata({ topics: names });
      return Promise.all(
        topics.map(async (t) => {
          const offsets = await a.fetchTopicOffsets(t.name);
          return {
            name: t.name,
            partitions: t.partitions.length,
            replication: t.partitions[0]?.replicas.length || 0,
            inSync: t.partitions.every(
              (p) => p.isr.length === p.replicas.length,
            ),
            retained: offsets.reduce(
              (n, p) => n + Number(BigInt(p.high) - BigInt(p.low)),
              0,
            ),
            offsets,
            partitionDetails: t.partitions,
          };
        }),
      );
    });
  }
  async create(body) {
    const topic = topicName(body.topic),
      numPartitions = integer(body.partitions ?? 1, "Partitions", 1, 32);
    return this.admin(async (a) => {
      if ((await a.listTopics()).includes(topic))
        throw new HttpError(409, "This topic already exists.");
      await a.createTopics({
        waitForLeaders: true,
        topics: [{ topic, numPartitions, replicationFactor: 1 }],
      });
      return { message: `Created ${topic}.` };
    });
  }
  async remove(name) {
    topicName(name);
    return this.admin(async (a) => {
      await a.deleteTopics({ topics: [name] });
      return { message: `Deleted ${name}.` };
    });
  }
  async produce(body) {
    const topic = topicName(body.topic);
    if (typeof body.value !== "string" || !body.value.length)
      throw new HttpError(400, "Enter a message value.");
    if (Buffer.byteLength(body.value) > 900000)
      throw new HttpError(400, "Message must be smaller than 900 KB.");
    const p = this.client.producer({ allowAutoTopicCreation: false });
    try {
      await p.connect();
      const records = await p.send({
        topic,
        messages: [{ key: body.key || null, value: body.value }],
      });
      return { message: "Message published.", records };
    } finally {
      await p.disconnect();
    }
  }
  async messages(name, inputLimit = 25, mode = "latest") {
    const topic = topicName(name),
      limit = integer(inputLimit, "Limit", 1, 100);
    if (!["latest", "earliest"].includes(mode))
      throw new HttpError(400, "Invalid offset mode.");
    const offsets = await this.admin((a) => a.fetchTopicOffsets(topic));
    const ranges = offsets.map((p) => ({
      partition: p.partition,
      end: BigInt(p.high),
      start:
        mode === "earliest"
          ? BigInt(p.low)
          : BigInt(p.high) - BigInt(limit) > BigInt(p.low)
            ? BigInt(p.high) - BigInt(limit)
            : BigInt(p.low),
    }));
    if (ranges.every((p) => p.start === p.end))
      return { messages: [], truncated: false };
    const c = this.reader.consumer({
      groupId: `relay-peek-${randomUUID()}`,
      allowAutoTopicCreation: false,
      maxWaitTimeInMs: 250,
    });
    const records = [];
    let finish;
    let failure;
    const done = new Promise((r) => {
      finish = r;
    });
    const completed = new Set(
      ranges.filter((p) => p.start === p.end).map((p) => p.partition),
    );
    let timer;
    c.on(c.events.CRASH, ({ payload }) => {
      failure = payload.error;
      finish();
    });
    c.on(c.events.GROUP_JOIN, () => {
      for (const p of ranges)
        c.seek({ topic, partition: p.partition, offset: String(p.start) });
    });
    try {
      await c.connect();
      await c.subscribe({ topic, fromBeginning: true });
      await c.run({
        autoCommit: false,
        eachBatchAutoResolve: false,
        eachBatch: async ({ batch, resolveOffset, isStale }) => {
          if (isStale()) return;
          const range = ranges.find((p) => p.partition === batch.partition);
          for (const m of batch.messages) {
            const offset = BigInt(m.offset);
            if (offset < range.start) {
              resolveOffset(m.offset);
              continue;
            }
            if (offset >= range.end) {
              completed.add(batch.partition);
              break;
            }
            records.push({
              partition: batch.partition,
              offset: m.offset,
              timestamp: m.timestamp,
              key: m.key?.toString() ?? null,
              value: m.value?.toString() ?? null,
              headers: Object.fromEntries(
                Object.entries(m.headers || {}).map(([k, v]) => [
                  k,
                  v?.toString(),
                ]),
              ),
            });
            resolveOffset(m.offset);
            if (offset + 1n >= range.end) completed.add(batch.partition);
            if (records.length >= limit) {
              finish();
              break;
            }
          }
          if (completed.size === ranges.length) finish();
        },
      });
      // Group creation can take longer on a fresh broker while its coordinator
      // initializes. Bound the snapshot after joining, rather than during it.
      timer = setTimeout(finish, 8000);
      await done;
      if (failure) throw failure;
      return {
        messages: records
          .sort((a, b) => Number(b.timestamp) - Number(a.timestamp))
          .slice(0, limit),
        truncated: completed.size !== ranges.length,
      };
    } finally {
      clearTimeout(timer);
      await c.disconnect();
    }
  }
  async groups() {
    return this.admin(async (a) => {
      const { groups } = await a.listGroups();
      const names = groups
        .map((g) => g.groupId)
        .filter((g) => !g.startsWith("relay-peek-"));
      if (!names.length) return [];
      const result = await a.describeGroups(names);
      return result.groups.map((g) => ({
        groupId: g.groupId,
        state: g.state,
        members: g.members.length,
        protocol: g.protocolType,
      }));
    });
  }
}
