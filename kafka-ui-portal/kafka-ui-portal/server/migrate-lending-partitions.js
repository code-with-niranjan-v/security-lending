import { KafkaService } from "./kafka.js";
import { createConfig } from "./config.js";
import { lendingPartitionMigration } from "./lending-partitions.js";

// Run only after pausing producers, draining durable outboxes/consumer lag, and stopping services.
if (!process.argv.includes("--confirm-drained")) {
  throw new Error(
    "Drain all outboxes and consumer groups, stop all backend replicas and FIX adapters, then rerun with --confirm-drained. No topics are deleted.",
  );
}

const kafka = new KafkaService(createConfig());
const result = await lendingPartitionMigration(kafka, { apply: true });
for (const partition of result.preservedDeadLetters)
  console.warn(`Preserving retained dead-letter records: ${partition}.`);
console.log(
  `${result.topicsToResize} topics increased to six partitions. Existing records preserved.`,
);
