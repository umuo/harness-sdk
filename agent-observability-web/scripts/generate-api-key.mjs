import { randomBytes } from "node:crypto";

// 32 random bytes = 256 bits; hex encoding produces a 64-character secret.
process.stdout.write(`${randomBytes(32).toString("hex")}\n`);
