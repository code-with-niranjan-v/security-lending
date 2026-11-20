import net from "node:net";
import { setTimeout as delay } from "node:timers/promises";
import { lendingTopics } from "./config.js";

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

async function waitForPartitionLeaders(kafka, additions) {
  const deadline = Date.now() + 10000;
  do {
    try {
      const offsets = await kafka.admin((admin) =>
        Promise.all(
          additions.map(({ topic }) => admin.fetchTopicOffsets(topic)),
        ),
      );
      if (
        offsets.every(
          (partitions, i) => partitions.length >= additions[i].count,
        )
      )
        return;
    } catch (error) {
      if (
        !["LEADER_NOT_AVAILABLE", "NOT_LEADER_FOR_PARTITION"].includes(
          error.type,
        )
      )
        throw error;
    }
    await delay(250);
  } while (Date.now() < deadline);
  throw new Error(
    "Kafka accepted the resize but new partitions are still coming online. Check Kafka backlog again before restarting services.",
  );
}

// A read-only check can run while the old services are draining.
export async function lendingPartitionMigration(kafka, { apply = false } = {}) {
  if (apply) {
    for (const port of [
      8080, 8081, 8082, 8083, 8084, 8085, 8086, 18081, 18084, 18085, 18086,
      8090,
    ]) {
      if (await listening(port))
        throw new Error(
          `Stop services first: port ${port} is still listening.`,
        );
    }
  }
  const { additions, preservedDeadLetters } = await kafka.admin(
    async (admin) => {
      const preservedDeadLetters = [];
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
      const { topics } = await admin.fetchTopicMetadata({
        topics: lendingTopics,
      });
      for (const topic of topics) {
        const offsets = await admin.fetchTopicOffsets(topic.name);
        for (const partition of offsets) {
          if (
            BigInt(partition.high) > BigInt(partition.low) &&
            !committedPartitions.has(`${topic.name}/${partition.partition}`)
          ) {
            // The application publishes to DLTs for inspection and does not consume them.
            if (topic.name.endsWith(".DLT")) {
              preservedDeadLetters.push(`${topic.name}/${partition.partition}`);
              continue;
            }
            throw new Error(
              `Retained records without a committed consumer offset: ${topic.name}/${partition.partition}. Resolve them before migration.`,
            );
          }
        }
      }
      const additions = topics
        .filter((topic) => topic.partitions.length < 6)
        .map((topic) => ({ topic: topic.name, count: 6 }));
      if (apply && additions.length) {
        await admin.createPartitions({
          topicPartitions: additions,
          validateOnly: false,
        });
      }
      return { additions, preservedDeadLetters };
    },
  );
  if (apply && additions.length)
    await waitForPartitionLeaders(kafka, additions);
  return { topicsToResize: additions.length, preservedDeadLetters };
}
