import net from "node:net";
import { KafkaService } from "./kafka.js";
import { createConfig, lendingTopics } from "./config.js";

// Run only after pausing producers, draining durable outboxes/consumer lag, and stopping services.
if (!process.argv.includes("--confirm-drained")) {
  throw new Error(
    "Drain all outboxes and consumer groups, stop all backend replicas and FIX adapters, then rerun with --confirm-drained. No topics are deleted.",
  );
}
async function listening(port) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    socket.setTimeout(500);
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("error", () => resolve(false));
    socket.once("timeout", () => {
      socket.destroy();
      resolve(false);
    });
  });
}
for (const port of [
  8080, 8081, 8082, 8083, 8084, 8085, 8086, 18081, 18084, 18085, 18086, 8090,
]) {
  if (await listening(port))
    throw new Error(`Stop services first: port ${port} is still listening.`);
}
const kafka = new KafkaService(createConfig());
await kafka.admin(async (admin) => {
  const { groups } = await admin.listGroups();
  const committedPartitions = new Set();
  const knownGroups = groups.map((group) => group.groupId);
  for (const groupId of knownGroups) {
    const offsets = await admin.fetchOffsets({
      groupId,
      topics: lendingTopics,
      resolveOffsets: false,
    });
    for (const topic of offsets) {
      if (!lendingTopics.includes(topic.topic)) continue;
      const heads = await admin.fetchTopicOffsets(topic.topic);
      for (const partition of topic.partitions) {
        if (BigInt(partition.offset) < 0) continue;
        committedPartitions.add(`${topic.topic}/${partition.partition}`);
        const high = heads.find(
          (head) => head.partition === partition.partition,
        )?.high;
        if (high !== undefined && BigInt(partition.offset) < BigInt(high)) {
          throw new Error(
            `Consumer lag remains for ${groupId}/${topic.topic}/${partition.partition}; resume the old configuration and drain it first.`,
          );
        }
      }
    }
  }
  const { topics } = await admin.fetchTopicMetadata({ topics: lendingTopics });
  for (const topic of topics) {
    const offsets = await admin.fetchTopicOffsets(topic.name);
    for (const partition of offsets) {
      if (
        BigInt(partition.high) > BigInt(partition.low) &&
        !committedPartitions.has(`${topic.name}/${partition.partition}`)
      ) {
        throw new Error(
          `Retained records without a committed consumer offset: ${topic.name}/${partition.partition}. Resolve them before migration.`,
        );
      }
    }
  }
  const additions = topics
    .filter((topic) => topic.partitions.length < 6)
    .map((topic) => ({ topic: topic.name, count: 6 }));
  if (additions.length)
    await admin.createPartitions({
      topicPartitions: additions,
      validateOnly: false,
    });
  console.log(
    `${additions.length} topics increased to six partitions. Existing records preserved.`,
  );
});
